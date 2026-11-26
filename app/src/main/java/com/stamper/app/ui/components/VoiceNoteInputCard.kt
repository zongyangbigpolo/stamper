package com.stamper.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.SettingsVoice
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color
import com.stamper.app.ui.theme.StamperPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
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

    fun saveNow() {
        val mergedNote = if (speechState.partialTranscript.isNotBlank()) {
            SystemSpeechHelper.appendRecognizedSegment(noteText, speechState.partialTranscript)
        } else {
            noteText.trim()
        }
        speechHelper.release()
        onSave(mergedNote, selectedProject?.id ?: localSelectedProjectId)
    }

    val wave = rememberInfiniteTransition(label = "wave")
    val phase by wave.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "phase"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .imePadding(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (photoUri != null) {
                        AsyncImage(
                            model = photoUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    WithHint("归档分组") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { showProjectSelector = !showProjectSelector }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(StamperPalette.Gold)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = selectedProject?.name.orEmpty(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 180.dp)
                            )
                            Icon(
                                if (showProjectSelector) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (!queueLabel.isNullOrBlank()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Text(queueLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (showProjectSelector) {
                    Spacer(modifier = Modifier.height(10.dp))
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
                            FilterChip(
                                selected = showInlineCreateProject,
                                onClick = {
                                    inlineProjectName = ""
                                    showInlineCreateProject = !showInlineCreateProject
                                },
                                label = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建分组", modifier = Modifier.size(18.dp)) }
                            )
                        }
                    }
                    if (showInlineCreateProject && onCreateProject != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = inlineProjectName,
                                onValueChange = { inlineProjectName = it },
                                singleLine = true,
                                placeholder = { Text("分组名") },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            if (isCreatingProject) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                HintIconButton(
                                    icon = Icons.Rounded.Check,
                                    hint = "创建",
                                    enabled = inlineProjectName.isNotBlank(),
                                    colors = IconButtonDefaults.filledIconButtonColors(),
                                    onClick = {
                                        val clean = inlineProjectName.trim()
                                        if (clean.isNotEmpty()) {
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
                                    }
                                )
                            }
                        }
                    }
                }

                // Live waveform: bars bounce with the mic level while listening
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    repeat(17) { i ->
                        val factor = 0.35f + 0.65f * abs(sin(phase + i * 0.55f))
                        val barHeight = if (speechState.isListening) 6.dp + 30.dp * normalizedLevel * factor else 4.dp
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(barHeight)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (speechState.isListening) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    placeholder = {
                        if (speechState.partialTranscript.isNotEmpty()) {
                            Text(speechState.partialTranscript, style = MaterialTheme.typography.titleMedium)
                        } else {
                            Icon(Icons.Outlined.Keyboard, contentDescription = null)
                        }
                    },
                    supportingText = if (noteText.isNotEmpty() && speechState.partialTranscript.isNotEmpty()) {
                        { Text(speechState.partialTranscript, color = MaterialTheme.colorScheme.primary) }
                    } else if (!speechState.errorMessage.isNullOrBlank()) {
                        { Text(speechState.errorMessage!!, color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.Transparent
                    ),
                    trailingIcon = {
                        if (noteText.isNotEmpty()) {
                            HintIconButton(icon = Icons.Rounded.Close, hint = "清空", onClick = { noteText = "" }, iconSize = 20.dp)
                        } else if (speechState.needsVoiceSetup) {
                            HintIconButton(
                                icon = Icons.Outlined.SettingsVoice,
                                hint = "语音设置",
                                onClick = { SystemSpeechHelper.openSystemVoiceInputSettings(context) }
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                HintIconButton(
                    icon = Icons.Rounded.Close,
                    hint = dismissButtonLabel,
                    enabled = !isSaving && !isCreatingProject,
                    onClick = {
                        speechHelper.release()
                        onDismiss()
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.size(58.dp)
                )

                val micColor by animateColorAsState(
                    targetValue = if (speechState.isListening) Color(0xFFE5484D) else MaterialTheme.colorScheme.primary,
                    label = "micColor"
                )
                val halo by animateDpAsState(if (speechState.isListening) 10.dp * (0.4f + normalizedLevel) else 0.dp, label = "halo")
                WithHint(if (speechState.isListening) "停止" else "说话") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(micColor.copy(alpha = if (speechState.isListening) 0.18f else 0f))
                            .padding(10.dp - halo / 2)
                            .clip(CircleShape)
                            .background(micColor)
                            .clickable(enabled = !isSaving) { triggerVoiceCapture() }
                    ) {
                        Icon(
                            imageVector = if (speechState.isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                            contentDescription = if (speechState.isListening) "停止" else "说话",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                WithHint(saveButtonLabel) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(enabled = !isSaving && !isCreatingProject) { saveNow() }
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Rounded.Check, contentDescription = saveButtonLabel, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}
