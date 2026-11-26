package com.stamper.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stamper.app.domain.stamper.CaptureSource
import com.stamper.app.domain.stamper.StamperSettings
import com.stamper.app.ui.camera.CameraScreen
import com.stamper.app.ui.components.VoiceNoteInputCard
import com.stamper.app.ui.home.HomeScreen
import com.stamper.app.ui.project.ProjectDetailScreen
import com.stamper.app.ui.settings.SettingsScreen
import com.stamper.app.ui.theme.StamperTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

private data class QueuedImportPhoto(
    val uri: Uri,
    val capturedAtMillis: Long,
    val source: CaptureSource
)

class MainActivity : ComponentActivity() {

    private val incomingIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 8)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as StamperApp).repository

        setContent {
            StamperTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()

                val projects by repository.projects.collectAsState()
                val activeProjectId by repository.settingsRepository.activeProjectIdFlow
                    .collectAsState(initial = 1L)
                val settings by repository.settingsRepository.settingsFlow
                    .collectAsState(initial = StamperSettings())

                val importQueue = remember { mutableStateListOf<QueuedImportPhoto>() }
                var isSavingImport by remember { mutableStateOf(false) }
                var isExportingZip by remember { mutableStateOf(false) }

                fun handleIncomingIntent(intent: Intent?) {
                    if (intent == null) return
                    when (intent.action) {
                        Intent.ACTION_SEND -> {
                            if (intent.type?.startsWith("image/") == true) {
                                extractSingleStreamUri(intent)?.let { uri ->
                                    importQueue.add(
                                        QueuedImportPhoto(
                                            uri = uri,
                                            capturedAtMillis = System.currentTimeMillis(),
                                            source = CaptureSource.GALLERY_IMPORT
                                        )
                                    )
                                }
                            }
                        }

                        Intent.ACTION_SEND_MULTIPLE -> {
                            if (intent.type?.startsWith("image/") == true) {
                                extractMultipleStreamUris(intent).forEach { uri ->
                                    importQueue.add(
                                        QueuedImportPhoto(
                                            uri = uri,
                                            capturedAtMillis = System.currentTimeMillis(),
                                            source = CaptureSource.GALLERY_IMPORT
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (savedInstanceState == null) {
                        handleIncomingIntent(intent)
                    }
                    incomingIntents.collect { newIntent ->
                        handleIncomingIntent(newIntent)
                    }
                }

                fun exportAndShareZip(projectId: Long) {
                    if (isExportingZip) return
                    isExportingZip = true
                    scope.launch {
                        repository.exportProjectZip(projectId)
                            .onSuccess { zipUri ->
                                isExportingZip = false
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, zipUri)
                                    clipData = ClipData.newRawUri("project_export.zip", zipUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(
                                    Intent.createChooser(shareIntent, "导出项目照片压缩包")
                                )
                            }
                            .onFailure { err ->
                                isExportingZip = false
                                Toast.makeText(
                                    this@MainActivity,
                                    "导出失败: ${err.localizedMessage}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        composable("home") {
                            HomeScreen(
                                repository = repository,
                                onOpenCamera = { navController.navigate("camera") },
                                onOpenProject = { projId ->
                                    navController.navigate("project/$projId")
                                },
                                onOpenSettings = { navController.navigate("settings") },
                                onImportUrisSelected = { uris ->
                                    val now = System.currentTimeMillis()
                                    uris.forEach { uri ->
                                        importQueue.add(
                                            QueuedImportPhoto(
                                                uri = uri,
                                                capturedAtMillis = now,
                                                source = CaptureSource.GALLERY_IMPORT
                                            )
                                        )
                                    }
                                },
                                onExportProjectZip = { project ->
                                    exportAndShareZip(project.id)
                                }
                            )
                        }

                        composable("camera") {
                            CameraScreen(
                                repository = repository,
                                onNavigateBack = { navController.popBackStack() },
                                onOpenActiveProject = { projId ->
                                    navController.navigate("project/$projId")
                                }
                            )
                        }

                        composable(
                            route = "project/{projectId}",
                            arguments = listOf(
                                navArgument("projectId") { type = NavType.LongType }
                            )
                        ) { backStackEntry ->
                            val projId = backStackEntry.arguments?.getLong("projectId") ?: 1L
                            ProjectDetailScreen(
                                projectId = projId,
                                repository = repository,
                                onNavigateBack = { navController.popBackStack() },
                                onOpenCamera = { navController.navigate("camera") },
                                onExportProjectZip = { id -> exportAndShareZip(id) }
                            )
                        }

                        composable("settings") {
                            SettingsScreen(
                                settingsRepository = repository.settingsRepository,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }

                    // Batch Gallery Import / Fallback Notification Annotation Sheet
                    val currentImport = importQueue.firstOrNull()
                    if (currentImport != null) {
                        ModalBottomSheet(
                            onDismissRequest = {
                                if (!isSavingImport) {
                                    importQueue.removeFirstOrNull()
                                }
                            }
                        ) {
                            Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                                VoiceNoteInputCard(
                                    photoUri = currentImport.uri,
                                    capturedAtMillis = currentImport.capturedAtMillis,
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
                                    isSaving = isSavingImport,
                                    queueLabel = if (importQueue.size > 1) {
                                        "待补录 (${importQueue.size} 张)"
                                    } else {
                                        null
                                    },
                                    saveButtonLabel = if (importQueue.size > 1) {
                                        "保存并下一张"
                                    } else {
                                        "保存归档"
                                    },
                                    dismissButtonLabel = "跳过这张",
                                    onSave = { note, targetProjectId ->
                                        if (isSavingImport) return@VoiceNoteInputCard
                                        isSavingImport = true
                                        scope.launch {
                                            runCatching {
                                                repository.stampAndSavePhoto(
                                                    sourceUri = currentImport.uri,
                                                    note = note,
                                                    projectId = targetProjectId,
                                                    source = currentImport.source,
                                                    fallbackCapturedAtMillis = currentImport.capturedAtMillis
                                                )
                                            }.onSuccess { saved ->
                                                isSavingImport = false
                                                importQueue.removeFirstOrNull()
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "已归档: ${saved.fileName}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }.onFailure { err ->
                                                isSavingImport = false
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "处理失败: ${err.localizedMessage}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    },
                                    onDismiss = {
                                        importQueue.removeFirstOrNull()
                                    }
                                )
                            }
                        }
                    }

                    if (isExportingZip) {
                        Dialog(onDismissRequest = {}) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntents.tryEmit(intent)
    }

    private fun extractSingleStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun extractMultipleStreamUris(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }
}
