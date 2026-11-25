package com.stamper.app.domain.stamper

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object FileNameSanitizer {

    private val DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

    private val TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HHmmss", Locale.US)

    private val ILLEGAL_CHARS_REGEX = Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    private const val MAX_NOTE_CHARS = 40
    private const val MAX_FOLDER_CHARS = 50

    /**
     * Builds the photo file name with the voice note FIRST and the date LAST:
     *   `<语音录入内容>_<yyyyMMdd>.jpg`
     * Example: `302教室东南角投影仪_20261125.jpg`
     *
     * Collisions on the same day automatically append `_2`, `_3`, etc.
     * Empty notes fall back to `未备注_<yyyyMMdd>_<HHmmss>.jpg`.
     */
    fun buildPhotoFileName(
        note: String,
        capturedAtMillis: Long,
        existingNames: Set<String> = emptySet(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val zdt = Instant.ofEpochMilli(capturedAtMillis).atZone(zoneId)
        val datePart = DATE_FORMATTER.format(zdt)
        val sanitizedNote = sanitizeNotePrefix(note)

        val baseStem = if (sanitizedNote.isNotEmpty()) {
            "${sanitizedNote}_${datePart}"
        } else {
            val timePart = TIME_FORMATTER.format(zdt)
            "未备注_${datePart}_${timePart}"
        }

        val normalizedExisting = existingNames.map { it.lowercase(Locale.ROOT) }.toSet()
        val firstCandidate = "$baseStem.jpg"
        if (firstCandidate.lowercase(Locale.ROOT) !in normalizedExisting) {
            return firstCandidate
        }

        var suffix = 2
        while (true) {
            val candidate = "${baseStem}_${suffix}.jpg"
            if (candidate.lowercase(Locale.ROOT) !in normalizedExisting) {
                return candidate
            }
            suffix++
        }
    }

    fun sanitizeNotePrefix(rawNote: String): String {
        val cleaned = rawNote
            .trim()
            .replace(ILLEGAL_CHARS_REGEX, "_")
            .replace(WHITESPACE_REGEX, "")
            .trim('.', '_', ' ')

        if (cleaned.isEmpty()) return ""
        return truncateByCodePoints(cleaned, MAX_NOTE_CHARS).trimEnd('.', '_', ' ')
    }

    fun sanitizeFolderName(projectName: String): String {
        val cleaned = projectName
            .trim()
            .replace(ILLEGAL_CHARS_REGEX, "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
        if (cleaned.isEmpty()) return "默认项目"
        return truncateByCodePoints(cleaned, MAX_FOLDER_CHARS).trimEnd('.', ' ').ifEmpty { "默认项目" }
    }

    private fun truncateByCodePoints(input: String, maxCodePoints: Int): String {
        val count = input.codePointCount(0, input.length)
        if (count <= maxCodePoints) return input
        val endOffset = input.offsetByCodePoints(0, maxCodePoints)
        return input.substring(0, endOffset)
    }
}
