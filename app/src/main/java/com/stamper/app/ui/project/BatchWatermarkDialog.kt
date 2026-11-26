package com.stamper.app.ui.project

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stamper.app.data.PhotoRecord
import com.stamper.app.data.PhotoStorageManager
import com.stamper.app.data.ProjectItem
import com.stamper.app.data.StamperRepository
import com.stamper.app.domain.stamper.CardHeaderColor
import com.stamper.app.domain.stamper.CardPosition
import com.stamper.app.domain.stamper.InfoCard
import com.stamper.app.domain.stamper.InfoCardRenderer
import com.stamper.app.domain.stamper.InfoCardStyle
import com.stamper.app.domain.stamper.StamperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchWatermarkDialog(
    project: ProjectItem,
    photos: List<PhotoRecord>,
    repository: StamperRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(project.name) }
    var style by remember { mutableStateOf(InfoCardStyle()) }
    LaunchedEffect(project.id) {
        style = repository.settingsRepository.infoCardStyleFlow.first()
        title = repository.settingsRepository.getInfoCardTitle(project.id) ?: project.name
        loaded = true
    }

    var previewIndex by remember { mutableIntStateOf(0) }
    val previewPhoto = photos.getOrNull(previewIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)))
    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(previewPhoto?.id) {
        baseBitmap = null
        val photo = previewPhoto ?: return@LaunchedEffect
        baseBitmap = runCatching {
            StamperEngine.decodeUprightBitmap(context, Uri.parse(photo.mediaUri), 1280)
        }.getOrNull()
    }

    LaunchedEffect(baseBitmap, title, style, previewPhoto?.id) {
        val base = baseBitmap ?: return@LaunchedEffect
        val photo = previewPhoto ?: return@LaunchedEffect
        delay(80)
        previewBitmap = withContext(Dispatchers.Default) {
            val content = InfoCard.buildContent(title, photo.capturedAt, photo.note, photo.fileName, style)
            InfoCardRenderer.render(base, content, style)
        }
    }

    var isLocating by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var progressDone by remember { mutableIntStateOf(0) }

    // Slider positions are kept locally so dragging is smooth; style follows them.
    var opacitySlider by remember { mutableFloatStateOf(style.opacity) }
    var scaleSlider by remember { mutableFloatStateOf(style.scale) }
    LaunchedEffect(loaded) {
        opacitySlider = style.opacity
        scaleSlider = style.scale
    }

    Dialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    IconButton(onClick = onDismiss, enabled = !isRunning) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("批量添加信息水印", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${photos.size} 张 · 另存到 ${project.folderName}/${PhotoStorageManager.WATERMARK_SUBFOLDER}/，原图不变",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Live preview
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp)
                                .background(Color.Black)
                        ) {
                            val shown = previewBitmap
                            if (shown != null) {
                                Image(
                                    bitmap = shown.asImageBitmap(),
                                    contentDescription = "水印效果预览",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                CircularProgressIndicator(color = Color.White)
                            }
                            if (photos.size > 1) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(8.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color.Black.copy(alpha = 0.55f))
                                ) {
                                    IconButton(
                                        onClick = { previewIndex = (previewIndex - 1 + photos.size) % photos.size },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一张", tint = Color.White)
                                    }
                                    Text(
                                        "${previewIndex + 1} / ${photos.size}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    IconButton(
                                        onClick = { previewIndex = (previewIndex + 1) % photos.size },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一张", tint = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("标题（蓝色栏，默认项目名）") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = style.location,
                        onValueChange = { style = style.copy(location = it) },
                        label = { Text("地点（如：马鞍山市花山区·绿地臻城）") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                enabled = !isLocating,
                                onClick = {
                                    isLocating = true
                                    scope.launch {
                                        val suggestion = repository.suggestLocationFromPhotos(photos.map { it.id })
                                        isLocating = false
                                        when {
                                            suggestion == null -> Toast.makeText(
                                                context, "所选照片没有 GPS 信息，请手动填写地点", Toast.LENGTH_LONG
                                            ).show()
                                            else -> {
                                                style = style.copy(location = suggestion.text, showLocation = true)
                                                if (suggestion.coordinatesOnly) {
                                                    Toast.makeText(
                                                        context, "手机无法解析地址，已填入坐标，可手动修改", Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                }
                            ) {
                                if (isLocating) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.MyLocation, contentDescription = "从照片 GPS 识别地点")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    SectionLabel("显示内容")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = style.showTime,
                            onClick = { style = style.copy(showTime = !style.showTime) },
                            label = { Text(InfoCard.LABEL_TIME) }
                        )
                        FilterChip(
                            selected = style.showContent,
                            onClick = { style = style.copy(showContent = !style.showContent) },
                            label = { Text("${InfoCard.LABEL_CONTENT}（照片名称）") }
                        )
                        FilterChip(
                            selected = style.showLocation,
                            onClick = { style = style.copy(showLocation = !style.showLocation) },
                            label = { Text(InfoCard.LABEL_LOCATION) }
                        )
                    }

                    SectionLabel("位置")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            CardPosition.TOP_LEFT,
                            CardPosition.TOP_RIGHT,
                            CardPosition.BOTTOM_LEFT,
                            CardPosition.BOTTOM_RIGHT
                        ).forEach { pos ->
                            FilterChip(
                                selected = style.position == pos,
                                onClick = { style = style.copy(position = pos) },
                                label = { Text(pos.label) }
                            )
                        }
                    }

                    SectionLabel("透明度 ${(opacitySlider * 100).roundToInt()}%")
                    Slider(
                        value = opacitySlider,
                        onValueChange = { opacitySlider = it },
                        onValueChangeFinished = { style = style.copy(opacity = opacitySlider) },
                        valueRange = 0.3f..1f
                    )

                    SectionLabel("大小 ${(scaleSlider * 100).roundToInt()}%")
                    Slider(
                        value = scaleSlider,
                        onValueChange = { scaleSlider = it },
                        onValueChangeFinished = { style = style.copy(scale = scaleSlider) },
                        valueRange = 0.6f..1.6f
                    )

                    SectionLabel("标题栏颜色")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CardHeaderColor.entries.forEach { color ->
                            val selected = style.headerColor == color
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(color.argb))
                                    .border(
                                        width = if (selected) 3.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape
                                    )
                                    .clickable { style = style.copy(headerColor = color) }
                            ) {
                                if (selected) {
                                    Icon(Icons.Default.Check, contentDescription = color.label, tint = Color.White)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    if (isRunning) {
                        LinearProgressIndicator(
                            progress = { if (photos.isEmpty()) 0f else progressDone / photos.size.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Button(
                        enabled = loaded && !isRunning && photos.isNotEmpty(),
                        onClick = {
                            isRunning = true
                            progressDone = 0
                            val finalStyle = style.copy(opacity = opacitySlider, scale = scaleSlider)
                            scope.launch {
                                val result = repository.renderInfoCardCopies(
                                    projectId = project.id,
                                    photoIds = photos.map { it.id },
                                    title = title,
                                    style = finalStyle
                                ) { done, _ -> scope.launch { progressDone = done } }
                                isRunning = false
                                result.onSuccess { r ->
                                    val msg = if (r.failed == 0) {
                                        "已生成 ${r.succeeded} 张，保存在 ${r.folderDisplayPath}"
                                    } else {
                                        "已生成 ${r.succeeded} 张，${r.failed} 张失败（原图可能已被删除）"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    onDismiss()
                                }.onFailure { err ->
                                    Toast.makeText(context, "生成失败：${err.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(
                            text = if (isRunning) "正在生成 $progressDone / ${photos.size}…" else "生成 ${photos.size} 张水印照片",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(320.dp)
    )
}
