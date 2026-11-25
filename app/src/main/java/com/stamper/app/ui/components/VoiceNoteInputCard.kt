package com.stamper.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.stamper.app.data.ProjectItem
import com.stamper.app.domain.stamper.FileNameSanitizer
import com.stamper.app.speech.OfflineSpeechSupport
import com.stamper.app.speech.SpeechEngine
import com.stamper.app.speech.SystemSpeechHelper
import kotlinx.coroutines.launch

/**
 * Reusable voice + text annotation panel shared across:
 * 1. In-app rapid CameraX capture sheet
 * 2. Gallery batch photo import sheet
 * 3. System camera floating overlay window
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceNoteInputCard(
    photoUri: Any?,
    capturedAtMillis: Long,
    projects: List<ProjectItem>,
    selectedProjectId: Long,
    onSelectProject: (Long) -> Unit,
    onCreateProject: (suspend (String) -> ProjectItem)? = null,
    autoStartSpeech: Boolean,
    isSaving: Boolean,
    queueLabel: String? = null,
    saveButtonLabel: String = "保存归档",
    dismissButtonLabel: String = "忽略",
    allowRuntimePermissionLaunchers: Boolean = true,
    onSave: (note: String, targetProjectId: Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var noteText by remember(photoUri) { mutableStateOf("") }
    var localSelectedProjectId by remember { mutableLongStateOf(selectedProjectId) }
    var isCreatingProject by remember { mutableStateOf(false) }
    var showProjectSelector by remember { mutableStateOf(false) }
    var showInlineCreateProject by remember { mutableStateOf(false) }
    var inlineProjectName by remember { mutableStateOf("") }

    LaunchedEffect(selectedProjectId) {
        if (!isCreatingProject) {
            localSelectedProjectId = selectedProjectId
        }
    }

    val dialogRecognizerLauncher = if (allowRuntimePermissionLaunchers) {
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    .orEmpty()
                if (spoken.isNotBlank()) {
                    noteText = SystemSpeechHelper.appendRecognizedSegment(noteText, spoken)
                }
            }
        }
    } else {
        null
    }

    val speechHelper: SpeechEngine = remember(photoUri) {
        val appendFinal: (String) -> Unit = { recognized ->
            noteText = SystemSpeechHelper.appendRecognizedSegment(noteText, recognized)
        }
        if (OfflineSpeechSupport.isAvailable(context)) {
            OfflineSpeechSupport.create(context, appendFinal)
        } else {
            SystemSpeechHelper(
                context = context,
                onRequestDialogFallback = {
                    val launcher = dialogRecognizerLauncher ?: return@SystemSpeechHelper false
                    runCatching {
                        launcher.launch(SystemSpeechHelper.buildRecognizerDialogIntent())
                    }.isSuccess
                },
                onFinalResult = appendFinal
            )
        }
    }
    val speechState by speechHelper.state.collectAsState()

    DisposableEffect(speechHelper) {
        onDispose { speechHelper.release() }
    }

    val micPermissionLauncher = if (allowRuntimePermissionLaunchers) {
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                speechHelper.startListening()
            }
        }
    } else {
        null
    }

    fun triggerVoiceCapture() {
        val hasAudioPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasAudioPerm) {
            if (micPermissionLauncher != null) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                speechHelper.startListening()
            }
            return
        }

        if (speechState.isListening) {
            speechHelper.stopListening()
        } else {
            speechHelper.startListening()
        }
    }

    LaunchedEffect(photoUri, autoStartSpeech) {
        if (autoStartSpeech &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            speechHelper.startListening()
        }
    }

    val selectedProject = projects.firstOrNull { it.id == localSelectedProjectId }
        ?: projects.firstOrNull { it.id == selectedProjectId }
        ?: projects.firstOrNull()

    val effectivePreviewNote = remember(noteText, speechState.partialTranscript) {
        if (speechState.partialTranscript.isNotBlank()) {
            SystemSpeechHelper.appendRecognizedSegment(noteText, speechState.partialTranscript)
        } else {
            noteText
        }
    }

    val previewFileName = remember(effectivePreviewNote, capturedAtMillis) {
        FileNameSanitizer.buildPhotoFileName(
            note = effectivePreviewNote,
            capturedAtMillis = capturedAtMillis
        )
    }

    val normalizedLevel by animateFloatAsState(
        targetValue = if (speechState.isListening) {
            ((speechState.rmsDb.coerceIn(0f, 12f) + 1.5f) / 13.5f).coerceIn(0.12f, 1f)
        } else {
            0f
        },
        label = "voiceLevel"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .imePadding(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (photoUri != null) {
                        AsyncImage(
                            model = photoUri,
                            contentDescription = "照片预览",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "语音录入配件备注",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (!queueLabel.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = queueLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        AssistChip(
                            onClick = { showProjectSelector = !showProjectSelector },
                            label = {
                                Text(
                                    text = "归档分组：${selectedProject?.name ?: "默认项目"} ▾",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }

                if (showProjectSelector) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "选择归档项目分组（不印入画面水印）：",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                projects.forEach { proj ->
                                    FilterChip(
                                        selected = proj.id == selectedProject?.id,
                                        onClick = {
                                            localSelectedProjectId = proj.id
                                            onSelectProject(proj.id)
                                            showProjectSelector = false
                                            showInlineCreateProject = false
                                        },
                                        label = { Text(proj.name) }
                                    )
                                }
                                if (onCreateProject != null) {
                                    AssistChip(
                                        onClick = {
                                            inlineProjectName = ""
                                            showInlineCreateProject = !showInlineCreateProject
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.CreateNewFolder,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        label = { Text("新建分组") }
                                    )
                                }
                            }

                            if (showInlineCreateProject && onCreateProject != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        value = inlineProjectName,
                                        onValueChange = { inlineProjectName = it },
                                        singleLine = true,
                                        label = { Text("新项目名（如：实验楼弱电）") },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            val clean = inlineProjectName.trim()
                                            if (clean.isNotEmpty() && !isCreatingProject) {
                                                isCreatingProject = true
                                                scope.launch {
                                                    runCatching { onCreateProject(clean) }
                                                        .onSuccess { created ->
                                                            localSelectedProjectId = created.id
                                                            inlineProjectName = ""
                                                            showInlineCreateProject = false
                                                            showProjectSelector = false
                                                        }
                                                    isCreatingProject = false
                                                }
                                            }
                                        },
                                        enabled = inlineProjectName.isNotBlank() && !isCreatingProject,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        if (isCreatingProject) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        } else {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("创建")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (speechState.isListening) {
                    LinearProgressIndicator(
                        progress = { normalizedLevel },
                        color = MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        label = { Text("配件位置说明（如：302教室东南角投影仪）") },
                        placeholder = {
                            if (speechState.isListening) {
                                Text(
                                    speechState.partialTranscript.ifEmpty { "正在聆听，请直接说出配件位置…" }
                                )
                            } else {
                                Text("点击右侧麦克风口述，或直接键盘输入")
                            }
                        },
                        supportingText = {
                            if (speechState.partialTranscript.isNotEmpty()) {
                                Text(
                                    text = (if (speechState.isListening) "实时听写：" else "识别中：") +
                                        speechState.partialTranscript,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            } else if (!speechState.errorMessage.isNullOrBlank()) {
                                Text(
                                    text = speechState.errorMessage!!,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text(
                                    text = "保存文件名：$previewFileName",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        maxLines = 3,
                        trailingIcon = {
                            if (noteText.isNotEmpty()) {
                                IconButton(onClick = { noteText = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "清空文字")
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    val micColor by animateColorAsState(
                        targetValue = if (speechState.isListening) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        label = "micColor"
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = { triggerVoiceCapture() },
                            enabled = !isSaving,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = micColor
                            ),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = if (speechState.isListening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = if (speechState.isListening) "停止说话" else "开始说话",
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (speechState.isListening) "点击结束" else if (noteText.isEmpty()) "点此说话" else "继续追加",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (speechState.isListening) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                if (noteText.isNotEmpty() && !speechState.isListening) {
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {
                                noteText = ""
                                triggerVoiceCapture()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            },
                            label = { Text("清空并重说", style = MaterialTheme.typography.labelSmall) }
                        )
                        AssistChip(
                            onClick = { triggerVoiceCapture() },
                            leadingIcon = {
                                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(15.dp))
                            },
                            label = { Text("口述追加补充", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                if (speechState.needsVoiceSetup) {
                    Spacer(modifier = Modifier.height(6.dp))
                    AssistChip(
                        onClick = { SystemSpeechHelper.openSystemVoiceInputSettings(context) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.SettingsVoice,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text("去系统设置开启「语音输入」引擎") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = {
                        speechHelper.release()
                        onDismiss()
                    },
                    enabled = !isSaving && !isCreatingProject,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(dismissButtonLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Button(
                    onClick = {
                        val mergedNote = if (speechState.partialTranscript.isNotBlank()) {
                            SystemSpeechHelper.appendRecognizedSegment(noteText, speechState.partialTranscript)
                        } else {
                            noteText.trim()
                        }
                        speechHelper.release()
                        val targetId = selectedProject?.id ?: localSelectedProjectId
                        onSave(mergedNote, targetId)
                    },
                    enabled = !isSaving && !isCreatingProject,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1.45f)
                        .height(48.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSaving) "合成保存中…" else saveButtonLabel,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
