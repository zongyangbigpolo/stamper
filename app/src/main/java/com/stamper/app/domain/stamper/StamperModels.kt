package com.stamper.app.domain.stamper

enum class WatermarkCorner(val label: String) {
    BOTTOM_LEFT("左下角"),
    BOTTOM_RIGHT("右下角"),
    TOP_LEFT("左上角"),
    TOP_RIGHT("右上角");

    companion object {
        fun fromNameOrDefault(name: String?): WatermarkCorner =
            entries.firstOrNull { it.name == name } ?: BOTTOM_LEFT
    }
}

enum class WatermarkScale(val label: String, val factor: Float) {
    SMALL("偏小", 0.8f),
    NORMAL("标准", 1.0f),
    LARGE("偏大", 1.25f);

    companion object {
        fun fromNameOrDefault(name: String?): WatermarkScale =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

enum class CaptureSource(val wireValue: String, val label: String) {
    IN_APP_CAMERA("in_app_camera", "应用内连拍"),
    SYSTEM_CAMERA_OVERLAY("system_camera_overlay", "系统相机监听"),
    GALLERY_IMPORT("gallery_import", "相册补录");

    companion object {
        fun fromWireValue(value: String?): CaptureSource =
            entries.firstOrNull { it.wireValue == value } ?: IN_APP_CAMERA
    }
}

data class StamperSettings(
    val watermarkEnabled: Boolean = true,
    val exifEnabled: Boolean = true,
    val watermarkCorner: WatermarkCorner = WatermarkCorner.BOTTOM_LEFT,
    val watermarkScale: WatermarkScale = WatermarkScale.NORMAL,
    val autoStartSpeech: Boolean = true,
    val jpegQuality: Int = 92,
    val cameraWatchEnabled: Boolean = false
)

/**
 * Structured metadata stored inside EXIF UserComment (as ASCII-safe RFC 8259 JSON so no EXIF
 * reader suffers from UTF-16/Latin-1 Chinese mojibake) and mirrored into UTF-8 XMP tags.
 */
data class StamperMetadata(
    val schema: String = SCHEMA_V1,
    val project: String,
    val capturedAt: String,
    val note: String,
    val watermarkApplied: Boolean,
    val watermarkCorner: String,
    val source: String,
    val gpsLat: Double? = null,
    val gpsLng: Double? = null
) {
    fun toExifAsciiSafeJson(): String = buildJson(escapeNonAscii = true)

    fun toPrettyUtf8Json(): String = buildJson(escapeNonAscii = false)

    private fun buildJson(escapeNonAscii: Boolean): String = buildString {
        append('{')
        appendJsonField("schema", schema, escapeNonAscii, isFirst = true)
        appendJsonField("project", project, escapeNonAscii)
        appendJsonField("capturedAt", capturedAt, escapeNonAscii)
        appendJsonField("note", note, escapeNonAscii)
        append(",\"watermarkApplied\":").append(watermarkApplied)
        appendJsonField("watermarkCorner", watermarkCorner, escapeNonAscii)
        appendJsonField("source", source, escapeNonAscii)
        if (gpsLat != null && gpsLng != null) {
            append(",\"gps\":{\"lat\":").append(gpsLat).append(",\"lng\":").append(gpsLng).append('}')
        }
        append('}')
    }

    companion object {
        const val SCHEMA_V1 = "stamper.v1"

        private fun StringBuilder.appendJsonField(
            key: String,
            value: String,
            escapeNonAscii: Boolean,
            isFirst: Boolean = false
        ) {
            if (!isFirst) append(',')
            append('"').append(escapeJsonString(key, escapeNonAscii = false)).append("\":\"")
            append(escapeJsonString(value, escapeNonAscii = escapeNonAscii))
            append('"')
        }

        fun escapeJsonString(input: String, escapeNonAscii: Boolean): String = buildString(input.length + 16) {
            for (ch in input) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.code < 0x20 || (escapeNonAscii && ch.code > 0x7E)) {
                            append("\\u")
                            append(ch.code.toString(16).padStart(4, '0'))
                        } else {
                            append(ch)
                        }
                    }
                }
            }
        }

        /**
         * Parses a flat StamperMetadata JSON object (including \uXXXX escaped Chinese strings and
         * optional ExifInterface "ASCII\0\0\0" / "UNICODE\0" prefixes).
         */
        fun parseFromUserComment(raw: String?): StamperMetadata? {
            if (raw.isNullOrBlank()) return null
            val startIdx = raw.indexOf('{')
            val endIdx = raw.lastIndexOf('}')
            if (startIdx < 0 || endIdx <= startIdx) return null
            val json = raw.substring(startIdx, endIdx + 1)
            val schema = extractJsonString(json, "schema") ?: return null
            if (!schema.startsWith("stamper.")) return null
            return StamperMetadata(
                schema = schema,
                project = extractJsonString(json, "project").orEmpty(),
                capturedAt = extractJsonString(json, "capturedAt").orEmpty(),
                note = extractJsonString(json, "note").orEmpty(),
                watermarkApplied = extractJsonBoolean(json, "watermarkApplied") ?: false,
                watermarkCorner = extractJsonString(json, "watermarkCorner") ?: WatermarkCorner.BOTTOM_LEFT.name,
                source = extractJsonString(json, "source") ?: CaptureSource.IN_APP_CAMERA.wireValue,
                gpsLat = extractJsonNumber(json, "lat"),
                gpsLng = extractJsonNumber(json, "lng")
            )
        }

        private fun extractJsonString(json: String, key: String): String? {
            val token = "\"$key\""
            var idx = json.indexOf(token)
            while (idx >= 0) {
                var cursor = idx + token.length
                while (cursor < json.length && json[cursor].isWhitespace()) cursor++
                if (cursor < json.length && json[cursor] == ':') {
                    cursor++
                    while (cursor < json.length && json[cursor].isWhitespace()) cursor++
                    if (cursor < json.length && json[cursor] == '"') {
                        return decodeJsonStringLiteral(json, cursor + 1)
                    }
                }
                idx = json.indexOf(token, idx + token.length)
            }
            return null
        }

        private fun decodeJsonStringLiteral(json: String, contentStart: Int): String {
            val sb = StringBuilder()
            var i = contentStart
            while (i < json.length) {
                val c = json[i]
                if (c == '"') return sb.toString()
                if (c == '\\' && i + 1 < json.length) {
                    when (val esc = json[i + 1]) {
                        '"', '\\', '/' -> {
                            sb.append(esc)
                            i += 2
                        }
                        'b' -> {
                            sb.append('\b')
                            i += 2
                        }
                        'f' -> {
                            sb.append('\u000C')
                            i += 2
                        }
                        'n' -> {
                            sb.append('\n')
                            i += 2
                        }
                        'r' -> {
                            sb.append('\r')
                            i += 2
                        }
                        't' -> {
                            sb.append('\t')
                            i += 2
                        }
                        'u' -> {
                            if (i + 5 < json.length) {
                                val hex = json.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16)
                                if (code != null) {
                                    sb.append(code.toChar())
                                    i += 6
                                } else {
                                    sb.append("\\u")
                                    i += 2
                                }
                            } else {
                                sb.append(c)
                                i++
                            }
                        }
                        else -> {
                            sb.append(esc)
                            i += 2
                        }
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        private fun extractJsonBoolean(json: String, key: String): Boolean? {
            val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
            return regex.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        }

        private fun extractJsonNumber(json: String, key: String): Double? {
            val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
            return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
        }
    }
}
