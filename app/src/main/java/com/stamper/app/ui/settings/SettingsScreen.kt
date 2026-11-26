package com.stamper.app.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stamper.app.data.SettingsRepository
import com.stamper.app.domain.stamper.StamperSettings
import com.stamper.app.domain.stamper.WatermarkCorner
import com.stamper.app.domain.stamper.WatermarkRenderer
import com.stamper.app.domain.stamper.WatermarkScale
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settingsFlow.collectAsState(initial = StamperSettings())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Live Watermark Preview rendered by actual WatermarkRenderer
            val previewBitmap = remember(
                settings.watermarkEnabled,
                settings.watermarkCorner,
                settings.watermarkScale
            ) {
                val base = buildSampleSiteBackdropBitmap()
                if (settings.watermarkEnabled) {
                    WatermarkRenderer.renderWatermark(
                        source = base,
                        note = "302教室东南角投影仪",
                        corner = settings.watermarkCorner,
                        scale = settings.watermarkScale
                    )
                } else {
                    base
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "画面边角水印真实像素预览",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "实际效果预览",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "边角水印真实效果预览",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }

            // Core Switches
            OutlinedCard(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingSwitchRow(
                        title = "拍照时印角落文字水印",
                        subtitle = "拍照时把备注印在照片角落",
                        checked = settings.watermarkEnabled,
                        onCheckedChange = {
                            scope.launch { settingsRepository.setWatermarkEnabled(it) }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    SettingSwitchRow(
                        title = "在照片里保存备注信息",
                        subtitle = "电脑上可读取拍摄时间、分组和备注",
                        checked = settings.exifEnabled,
                        onCheckedChange = {
                            scope.launch { settingsRepository.setExifEnabled(it) }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    SettingSwitchRow(
                        title = "拍完自动开始语音听写",
                        subtitle = "拍完自动开始听写",
                        checked = settings.autoStartSpeech,
                        onCheckedChange = {
                            scope.launch { settingsRepository.setAutoStartSpeech(it) }
                        }
                    )
                }
            }

            // Corner & Font Scale
            OutlinedCard(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "水印角落位置",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WatermarkCorner.entries.forEach { corner ->
                            FilterChip(
                                selected = settings.watermarkCorner == corner,
                                onClick = {
                                    scope.launch { settingsRepository.setWatermarkCorner(corner) }
                                },
                                label = { Text(corner.label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "水印字号比例",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WatermarkScale.entries.forEach { scale ->
                            FilterChip(
                                selected = settings.watermarkScale == scale,
                                onClick = {
                                    scope.launch { settingsRepository.setWatermarkScale(scale) }
                                },
                                label = { Text(scale.label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "成品 JPEG 画质：${settings.jpegQuality}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = settings.jpegQuality.toFloat(),
                        onValueChange = { value ->
                            scope.launch {
                                settingsRepository.setJpegQuality(value.roundToInt())
                            }
                        },
                        valueRange = 75f..100f,
                        steps = 4
                    )
                }
            }

            // PC AI Agent Guide Card
            OutlinedCard(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "导出到电脑",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "用数据线连接电脑，打开 内部存储/Pictures/Stamper 即可复制；也可在项目页点 ZIP 按钮打包分享。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun buildSampleSiteBackdropBitmap(): Bitmap {
    val width = 960
    val height = 540
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(0xFF2B3A42.toInt(), 0xFF475C66.toInt(), 0xFF677E88.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    // Subtle architectural grid lines simulating ceiling / wall tiles on a construction site
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26FFFFFF
        strokeWidth = 2f
    }
    var x = 120f
    while (x < width) {
        canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
        x += 120f
    }
    var y = 108f
    while (y < height) {
        canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        y += 108f
    }

    val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x70FFFFFF
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText("示例照片", width / 2f, height / 2f + 10f, hintPaint)
    return bmp
}

