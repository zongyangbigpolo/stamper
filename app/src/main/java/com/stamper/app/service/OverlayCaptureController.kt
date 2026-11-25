package com.stamper.app.service

import android.content.Context
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.stamper.app.data.StamperRepository
import com.stamper.app.domain.stamper.CaptureSource
import com.stamper.app.domain.stamper.StamperSettings
import com.stamper.app.ui.components.VoiceNoteInputCard
import com.stamper.app.ui.theme.StamperTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class PendingOverlayPhoto(
    val uri: Uri,
    val capturedAtMillis: Long
)

/**
 * Displays a floating Jetpack Compose voice-annotation card right on top of the native
 * system camera app as soon as a new photo is captured.
 */
class OverlayCaptureController(
    private val context: Context,
    private val repository: StamperRepository
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val queue = ArrayDeque<PendingOverlayPhoto>()
    private var currentItem by mutableStateOf<PendingOverlayPhoto?>(null)
    private var queueTotalRemaining by mutableStateOf(0)
    private var isSaving by mutableStateOf(false)

    private var composeView: ComposeView? = null
    private var overlayOwner: OverlayLifecycleOwner? = null

    fun canShowOverlay(): Boolean = Settings.canDrawOverlays(context)

    fun enqueuePhoto(uri: Uri, capturedAtMillis: Long) {
        mainScope.launch {
            if (!canShowOverlay()) return@launch
            if (currentItem?.uri == uri || queue.any { it.uri == uri }) return@launch

            val item = PendingOverlayPhoto(uri, capturedAtMillis)
            if (currentItem == null) {
                currentItem = item
                queueTotalRemaining = 1
                attachOverlayIfNeeded()
            } else {
                queue.addLast(item)
                queueTotalRemaining = 1 + queue.size
            }
        }
    }

    fun dismissAllAndDestroy() {
        mainScope.launch {
            queue.clear()
            currentItem = null
            detachOverlay()
            mainScope.cancel()
        }
    }

    private fun advanceQueue() {
        val next = queue.removeFirstOrNull()
        currentItem = next
        queueTotalRemaining = (if (next != null) 1 else 0) + queue.size
        if (next == null) {
            detachOverlay()
        }
    }

    private fun attachOverlayIfNeeded() {
        if (composeView != null) return

        val owner = OverlayLifecycleOwner().apply { onCreateAndResume() }
        overlayOwner = owner

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)

            setContent {
                StamperTheme {
                    val item = currentItem
                    val projects by repository.projects.collectAsState()
                    val activeProjectId by repository.settingsRepository.activeProjectIdFlow
                        .collectAsState(initial = 1L)
                    val settings by repository.settingsRepository.settingsFlow
                        .collectAsState(initial = StamperSettings())

                    if (item != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 16.dp)
                        ) {
                            VoiceNoteInputCard(
                                photoUri = item.uri,
                                capturedAtMillis = item.capturedAtMillis,
                                projects = projects,
                                selectedProjectId = activeProjectId,
                                onSelectProject = { newId ->
                                    mainScope.launch {
                                        repository.settingsRepository.setActiveProjectId(newId)
                                    }
                                },
                                onCreateProject = { newName ->
                                    repository.createAndSelectProject(newName)
                                },
                                autoStartSpeech = settings.autoStartSpeech,
                                isSaving = isSaving,
                                queueLabel = if (queueTotalRemaining > 1) {
                                    "剩余 $queueTotalRemaining 张"
                                } else {
                                    "系统相机新拍"
                                },
                                allowRuntimePermissionLaunchers = false,
                                onSave = { note, targetProjectId ->
                                    if (isSaving) return@VoiceNoteInputCard
                                    isSaving = true
                                    mainScope.launch {
                                        runCatching {
                                            repository.stampAndSavePhoto(
                                                sourceUri = item.uri,
                                                note = note,
                                                projectId = targetProjectId,
                                                source = CaptureSource.SYSTEM_CAMERA_OVERLAY,
                                                fallbackCapturedAtMillis = item.capturedAtMillis
                                            )
                                        }.onSuccess { saved ->
                                            Toast.makeText(
                                                context,
                                                "已归档: ${saved.fileName}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            isSaving = false
                                            advanceQueue()
                                        }.onFailure { err ->
                                            Toast.makeText(
                                                context,
                                                "保存失败: ${err.localizedMessage}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            isSaving = false
                                        }
                                    }
                                },
                                onDismiss = {
                                    advanceQueue()
                                }
                            )
                        }
                    }
                }
            }
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        runCatching {
            windowManager.addView(view, params)
            composeView = view
        }.onFailure {
            owner.onDestroy()
            overlayOwner = null
        }
    }

    private fun detachOverlay() {
        composeView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        composeView = null
        overlayOwner?.onDestroy()
        overlayOwner = null
    }

    private class OverlayLifecycleOwner :
        LifecycleOwner,
        SavedStateRegistryOwner,
        ViewModelStoreOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val viewModelStore: ViewModelStore = ViewModelStore()

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun onCreateAndResume() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun onDestroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
