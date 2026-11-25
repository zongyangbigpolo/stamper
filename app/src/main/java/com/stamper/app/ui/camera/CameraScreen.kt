package com.stamper.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.stamper.app.data.StamperRepository
import com.stamper.app.domain.stamper.CaptureSource
import com.stamper.app.domain.stamper.StamperSettings
import com.stamper.app.ui.components.VoiceNoteInputCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private data class TempShot(
    val file: File,
    val uri: Uri,
    val capturedAtMillis: Long
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraScreen(
    repository: StamperRepository,
    onNavigateBack: () -> Unit,
    onOpenActiveProject: (Long) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val projects by repository.projects.collectAsState()
    val activeProjectId by repository.settingsRepository.activeProjectIdFlow.collectAsState(initial = 1L)
    val settings by repository.settingsRepository.settingsFlow.collectAsState(initial = StamperSettings())

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCameraPerm = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        val toRequest = buildList {
            if (!hasCameraPerm) add(Manifest.permission.CAMERA)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (toRequest.isNotEmpty()) {
            permLauncher.launch(toRequest.toTypedArray())
        }
    }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var isCapturingShutter by remember { mutableStateOf(false) }
    var pendingShot by remember { mutableStateOf<TempShot?>(null) }
    var isSavingStamped by remember { mutableStateOf(false) }
    var showProjectSwitcherDialog by remember { mutableStateOf(false) }
    var newProjectFolderName by remember { mutableStateOf("") }

    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(4f) }
    var focusTapOffset by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(focusTapOffset) {
        if (focusTapOffset != null) {
            delay(1100L)
            focusTapOffset = null
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    LaunchedEffect(flashMode) {
        imageCapture.flashMode = flashMode
    }

    LaunchedEffect(hasCameraPerm, lensFacing) {
        if (!hasCameraPerm) return@LaunchedEffect
        runCatching {
            val cameraProvider = ProcessCameraProvider.getInstance(context).await()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()
            cameraProvider.unbindAll()
            val cam = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageCapture
            )
            boundCamera = cam
            zoomRatio = 1f
            maxZoomRatio = (cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 4f).coerceAtLeast(1f)
        }.onFailure { err ->
            Toast.makeText(context, "相机初始化失败: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    val activeProject = projects.firstOrNull { it.id == activeProjectId } ?: projects.firstOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (!hasCameraPerm) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
            ) {
                Text(
                    text = "需要相机权限以拍摄施工现场照片",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        permLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        )
                    }
                ) {
                    Text("授予相机与麦克风权限")
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(boundCamera, pendingShot == null) {
                        if (pendingShot != null) return@pointerInput
                        detectTapGestures { tapOffset ->
                            val cam = boundCamera ?: return@detectTapGestures
                            focusTapOffset = tapOffset
                            val point = previewView.meteringPointFactory.createPoint(
                                tapOffset.x,
                                tapOffset.y
                            )
                            val action = FocusMeteringAction.Builder(point)
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()
                            cam.cameraControl.startFocusAndMetering(action)
                        }
                    }
                    .pointerInput(boundCamera, pendingShot == null) {
                        if (pendingShot != null) return@pointerInput
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) {
                                val cam = boundCamera ?: return@detectTransformGestures
                                val target = (zoomRatio * zoom).coerceIn(1f, maxZoomRatio.coerceAtMost(8f))
                                zoomRatio = target
                                cam.cameraControl.setZoomRatio(target)
                            }
                        }
                    }
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                // Animated Tap-to-Focus Reticle
                focusTapOffset?.let { pt ->
                    val halfSizePx = with(density) { 32.dp.roundToPx() }
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (pt.x.roundToInt() - halfSizePx).coerceAtLeast(0),
                                    (pt.y.roundToInt() - halfSizePx).coerceAtLeast(0)
                                )
                            }
                            .size(64.dp)
                            .border(2.dp, Color(0xFFFFD54F), RoundedCornerShape(12.dp))
                    )
                }
            }

            // Frozen preview while speaking note
            pendingShot?.let { shot ->
                AsyncImage(
                    model = shot.uri,
                    contentDescription = "刚拍摄的照片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }
        }

        // Top Control Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            IconButton(
                onClick = {
                    pendingShot?.file?.delete()
                    onNavigateBack()
                },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.45f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }

            Surface(
                onClick = {
                    newProjectFolderName = ""
                    showProjectSwitcherDialog = true
                },
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "分组：${activeProject?.name ?: "默认项目"} ▾",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = {
                        flashMode = when (flashMode) {
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                            else -> ImageCapture.FLASH_MODE_AUTO
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.45f),
                        contentColor = Color.White
                    )
                ) {
                    val icon = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                        ImageCapture.FLASH_MODE_OFF -> Icons.Default.FlashOff
                        else -> Icons.Default.FlashAuto
                    }
                    Icon(icon, contentDescription = "闪光灯")
                }

                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.45f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "切换前后摄像头")
                }
            }
        }

        // Bottom Area: Either Shutter Controls OR Instant Voice Annotation Card
        val currentShot = pendingShot
        if (currentShot == null && hasCameraPerm) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 26.dp, start = 24.dp, end = 24.dp)
            ) {
                // Quick Zoom Presets (1x / 2x) for ceiling projectors & high wall boxes
                if (maxZoomRatio >= 1.8f) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            listOf(1f to "1x", 2f.coerceAtMost(maxZoomRatio) to "2x").forEach { (ratio, label) ->
                                val active = kotlin.math.abs(zoomRatio - ratio) < 0.25f
                                Surface(
                                    onClick = {
                                        zoomRatio = ratio
                                        boundCamera?.cameraControl?.setZoomRatio(ratio)
                                    },
                                    color = if (active) Color(0xFFFFD54F) else Color.Transparent,
                                    shape = CircleShape
                                ) {
                                    Text(
                                        text = if (active && kotlin.math.abs(zoomRatio - ratio) >= 0.05f) {
                                            String.format(java.util.Locale.US, "%.1fx", zoomRatio)
                                        } else {
                                            label
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (active) Color.Black else Color.White,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .border(1.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                                .clickable { activeProject?.let { onOpenActiveProject(it.id) } }
                        ) {
                            if (!activeProject?.latestCoverUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = activeProject?.latestCoverUri,
                                    contentDescription = "查看当前分组照片",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    Icons.Default.PhotoLibrary,
                                    contentDescription = "查看已拍照片",
                                    tint = Color.White
                                )
                            }
                        }
                        val count = activeProject?.photoCount ?: 0
                        if (count > 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                                modifier = Modifier.offset(x = 4.dp, y = (-4).dp)
                            ) {
                                Text(
                                    text = count.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    FilledIconButton(
                        onClick = {
                            if (isCapturingShutter) return@FilledIconButton
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isCapturingShutter = true
                            val capturesDir = File(context.cacheDir, "captures").apply { mkdirs() }
                            val tempFile = File(capturesDir, "shot_${System.currentTimeMillis()}.jpg")
                            val outputOpts = ImageCapture.OutputFileOptions.Builder(tempFile).build()

                            imageCapture.takePicture(
                                outputOpts,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        isCapturingShutter = false
                                        pendingShot = TempShot(
                                            file = tempFile,
                                            uri = Uri.fromFile(tempFile),
                                            capturedAtMillis = System.currentTimeMillis()
                                        )
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        isCapturingShutter = false
                                        tempFile.delete()
                                        Toast.makeText(
                                            context,
                                            "拍照失败: ${exception.localizedMessage}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                        },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White
                        ),
                        modifier = Modifier
                            .size(78.dp)
                            .border(4.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                            .semantics { contentDescription = "拍照快门" }
                    ) {
                        if (isCapturingShutter) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(30.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.size(54.dp))
                }
            }
        } else if (currentShot != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp)
            ) {
                VoiceNoteInputCard(
                    photoUri = currentShot.uri,
                    capturedAtMillis = currentShot.capturedAtMillis,
                    projects = projects,
                    selectedProjectId = activeProjectId,
                    onSelectProject = { newId ->
                        scope.launch {
                            repository.settingsRepository.setActiveProjectId(newId)
                        }
                    },
                    onCreateProject = { newName ->
                        repository.createAndSelectProject(newName)
                    },
                    autoStartSpeech = settings.autoStartSpeech,
                    isSaving = isSavingStamped,
                    saveButtonLabel = "保存并继续拍",
                    dismissButtonLabel = "重拍",
                    onSave = { note, targetProjectId ->
                        if (isSavingStamped) return@VoiceNoteInputCard
                        isSavingStamped = true
                        scope.launch {
                            runCatching {
                                repository.stampAndSavePhoto(
                                    sourceUri = currentShot.uri,
                                    note = note,
                                    projectId = targetProjectId,
                                    source = CaptureSource.IN_APP_CAMERA,
                                    fallbackCapturedAtMillis = currentShot.capturedAtMillis
                                )
                            }.onSuccess { saved ->
                                currentShot.file.delete()
                                isSavingStamped = false
                                pendingShot = null
                                Toast.makeText(
                                    context,
                                    "已保存: ${saved.fileName}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }.onFailure { err ->
                                isSavingStamped = false
                                Toast.makeText(
                                    context,
                                    "保存失败: ${err.localizedMessage}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onDismiss = {
                        currentShot.file.delete()
                        pendingShot = null
                    }
                )
            }
        }
    }

    if (showProjectSwitcherDialog) {
        AlertDialog(
            onDismissRequest = { showProjectSwitcherDialog = false },
            title = { Text("切换或新建归档分组") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "点击切换当前拍照归档分组：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        projects.forEach { proj ->
                            FilterChip(
                                selected = proj.id == activeProject?.id,
                                onClick = {
                                    scope.launch {
                                        repository.settingsRepository.setActiveProjectId(proj.id)
                                    }
                                    showProjectSwitcherDialog = false
                                },
                                label = { Text("${proj.name} (${proj.photoCount})") }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = newProjectFolderName,
                        onValueChange = { newProjectFolderName = it },
                        singleLine = true,
                        label = { Text("新建项目名（如：教学楼3层弱电）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = newProjectFolderName.trim()
                        if (clean.isNotEmpty()) {
                            scope.launch {
                                val created = repository.createAndSelectProject(clean)
                                Toast.makeText(
                                    context,
                                    "已新建并切换到: ${created.name}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            newProjectFolderName = ""
                            showProjectSwitcherDialog = false
                        }
                    },
                    enabled = newProjectFolderName.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新建并启用")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProjectSwitcherDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}
