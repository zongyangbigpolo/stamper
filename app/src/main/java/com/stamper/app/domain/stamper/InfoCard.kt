package com.stamper.app.domain.stamper

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CardPosition(val label: String) {
    BOTTOM_LEFT("左下"),
    BOTTOM_RIGHT("右下"),
    TOP_LEFT("左上"),
    TOP_RIGHT("右上");

    val isLeft: Boolean get() = this == BOTTOM_LEFT || this == TOP_LEFT
    val isTop: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT

    companion object {
        fun fromNameOrDefault(name: String?): CardPosition =
            entries.firstOrNull { it.name == name } ?: BOTTOM_LEFT
    }
}

enum class CardHeaderColor(val label: String, val argb: Long) {
    BLUE("工程蓝", 0xFF2F5FC4),
    GREEN("森林绿", 0xFF1E7F5C),
    ORANGE("安全橙", 0xFFD9731A),
    RED("警示红", 0xFFC0392B),
    GRAPHITE("石墨灰", 0xFF3D4650);

    companion object {
        fun fromNameOrDefault(name: String?): CardHeaderColor =
            entries.firstOrNull { it.name == name } ?: BLUE
    }
}

data class InfoCardStyle(
    val position: CardPosition = CardPosition.BOTTOM_LEFT,
    /** Whole-card opacity, 0.3 – 1.0. */
    val opacity: Float = 0.92f,
    /** Size multiplier relative to the photo's short edge, 0.6 – 1.6. */
    val scale: Float = 1.0f,
    val headerColor: CardHeaderColor = CardHeaderColor.BLUE,
    val showTime: Boolean = true,
    val showContent: Boolean = true,
    val showLocation: Boolean = true,
    val location: String = ""
)

data class InfoCardRow(val label: String, val value: String)

data class InfoCardContent(val title: String, val rows: List<InfoCardRow>)

object InfoCard {
    const val LABEL_TIME = "拍摄时间"
    const val LABEL_CONTENT = "拍摄内容"
    const val LABEL_LOCATION = "地点"

    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.US)
    private val FILE_SUFFIX = Regex("""_\d{8}(_\d{6})?(_\d+)?$""")

    fun formatCaptureTime(capturedAtMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        TIME_FORMATTER.format(Instant.ofEpochMilli(capturedAtMillis).atZone(zoneId))

    /**
     * What the photo shows: the dictated note, or failing that the photo's file name minus the
     * `_yyyyMMdd[_HHmmss][_n]` suffix (e.g. `路由器_20261125.jpg` → `路由器`). Unannotated photos
     * (`未备注_…`) yield an empty string.
     */
    fun resolveContent(note: String, fileName: String): String {
        val trimmed = note.trim()
        if (trimmed.isNotEmpty()) return trimmed
        val stem = fileName.substringBeforeLast('.').replace(FILE_SUFFIX, "").trim()
        return if (stem.isEmpty() || stem == FileNameSanitizer.EMPTY_NOTE_PREFIX) "" else stem
    }

    fun buildContent(
        title: String,
        capturedAtMillis: Long,
        note: String,
        fileName: String,
        style: InfoCardStyle,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): InfoCardContent {
        val rows = buildList {
            if (style.showTime) add(InfoCardRow(LABEL_TIME, formatCaptureTime(capturedAtMillis, zoneId)))
            if (style.showContent) {
                val content = resolveContent(note, fileName)
                if (content.isNotEmpty()) add(InfoCardRow(LABEL_CONTENT, content))
            }
            if (style.showLocation && style.location.isNotBlank()) {
                add(InfoCardRow(LABEL_LOCATION, style.location.trim()))
            }
        }
        return InfoCardContent(title = title.trim(), rows = rows)
    }
}
