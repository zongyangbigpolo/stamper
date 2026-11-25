package com.stamper.app.domain.stamper

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Lightweight, spec-compliant JPEG APP1 XMP writer/reader (ISO 16684-1 / Adobe XMP Part 3).
 *
 * Writes UTF-8 Chinese descriptions directly into standard XMP tags (`dc:description`,
 * `photoshop:Headline`, `xmp:Label`, and `stamper:*`) so Windows Explorer, macOS Finder,
 * ExifTool, Python, and AI Agent skills can read Chinese notes with zero encoding issues.
 */
object JpegXmpWriter {

    private const val XMP_NAMESPACE_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
    private val XMP_HEADER_BYTES = XMP_NAMESPACE_HEADER.toByteArray(StandardCharsets.US_ASCII)

    private const val MARKER_PREFIX = 0xFF
    private const val MARKER_SOI = 0xD8
    private const val MARKER_EOI = 0xD9
    private const val MARKER_SOS = 0xDA
    private const val MARKER_APP1 = 0xE1

    fun injectXmp(jpegBytes: ByteArray, metadata: StamperMetadata): ByteArray {
        if (jpegBytes.size < 4 ||
            (jpegBytes[0].toInt() and 0xFF) != MARKER_PREFIX ||
            (jpegBytes[1].toInt() and 0xFF) != MARKER_SOI
        ) {
            return jpegBytes
        }

        val xmpPacketBytes = buildXmpPacketXml(metadata).toByteArray(StandardCharsets.UTF_8)
        val payloadLength = XMP_HEADER_BYTES.size + xmpPacketBytes.size
        // Standard JPEG APP1 segment max length is 65535 bytes (including the 2-byte length field)
        if (payloadLength + 2 > 0xFFFF) {
            return jpegBytes
        }

        val out = ByteArrayOutputStream(jpegBytes.size + payloadLength + 4)
        // Write SOI
        out.write(MARKER_PREFIX)
        out.write(MARKER_SOI)

        var pos = 2
        var inserted = false

        while (pos + 1 < jpegBytes.size) {
            if ((jpegBytes[pos].toInt() and 0xFF) != MARKER_PREFIX) {
                // Unexpected stream bytes; copy remainder safely
                if (!inserted) {
                    writeXmpApp1Segment(out, xmpPacketBytes)
                    inserted = true
                }
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                return out.toByteArray()
            }

            // Skip fill 0xFF bytes
            while (pos < jpegBytes.size && (jpegBytes[pos].toInt() and 0xFF) == MARKER_PREFIX) {
                pos++
            }
            if (pos >= jpegBytes.size) break

            val marker = jpegBytes[pos].toInt() and 0xFF
            pos++

            if (marker == MARKER_SOS || marker == MARKER_EOI) {
                if (!inserted) {
                    writeXmpApp1Segment(out, xmpPacketBytes)
                    inserted = true
                }
                out.write(MARKER_PREFIX)
                out.write(marker)
                if (pos < jpegBytes.size) {
                    out.write(jpegBytes, pos, jpegBytes.size - pos)
                }
                return out.toByteArray()
            }

            // Standalone markers without length (RST0..RST7, TEM)
            if (marker in 0xD0..0xD7 || marker == 0x01) {
                out.write(MARKER_PREFIX)
                out.write(marker)
                continue
            }

            if (pos + 1 >= jpegBytes.size) break
            val segLen = ((jpegBytes[pos].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 1].toInt() and 0xFF)
            if (segLen < 2 || pos + segLen > jpegBytes.size) {
                if (!inserted) {
                    writeXmpApp1Segment(out, xmpPacketBytes)
                    inserted = true
                }
                out.write(MARKER_PREFIX)
                out.write(marker)
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                return out.toByteArray()
            }

            val isExistingXmp = marker == MARKER_APP1 && isXmpSegment(jpegBytes, pos + 2, segLen - 2)
            if (isExistingXmp) {
                // Replace existing XMP segment in place (or drop duplicates if already inserted)
                if (!inserted) {
                    writeXmpApp1Segment(out, xmpPacketBytes)
                    inserted = true
                }
            } else {
                out.write(MARKER_PREFIX)
                out.write(marker)
                out.write(jpegBytes, pos, segLen)
                // If we just wrote EXIF APP1, place XMP APP1 right after it
                if (!inserted && marker == MARKER_APP1) {
                    writeXmpApp1Segment(out, xmpPacketBytes)
                    inserted = true
                }
            }

            pos += segLen
        }

        if (!inserted) {
            writeXmpApp1Segment(out, xmpPacketBytes)
        }
        return out.toByteArray()
    }

    fun extractXmpPacket(jpegBytes: ByteArray): String? {
        if (jpegBytes.size < 4 ||
            (jpegBytes[0].toInt() and 0xFF) != MARKER_PREFIX ||
            (jpegBytes[1].toInt() and 0xFF) != MARKER_SOI
        ) {
            return null
        }

        var pos = 2
        while (pos + 1 < jpegBytes.size) {
            if ((jpegBytes[pos].toInt() and 0xFF) != MARKER_PREFIX) return null
            while (pos < jpegBytes.size && (jpegBytes[pos].toInt() and 0xFF) == MARKER_PREFIX) {
                pos++
            }
            if (pos >= jpegBytes.size) return null
            val marker = jpegBytes[pos].toInt() and 0xFF
            pos++

            if (marker == MARKER_SOS || marker == MARKER_EOI) return null
            if (marker in 0xD0..0xD7 || marker == 0x01) continue
            if (pos + 1 >= jpegBytes.size) return null

            val segLen = ((jpegBytes[pos].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 1].toInt() and 0xFF)
            if (segLen < 2 || pos + segLen > jpegBytes.size) return null

            val payloadStart = pos + 2
            val payloadLen = segLen - 2
            if (marker == MARKER_APP1 && isXmpSegment(jpegBytes, payloadStart, payloadLen)) {
                val xmlStart = payloadStart + XMP_HEADER_BYTES.size
                val xmlLen = payloadLen - XMP_HEADER_BYTES.size
                return String(jpegBytes, xmlStart, xmlLen, StandardCharsets.UTF_8)
            }
            pos += segLen
        }
        return null
    }

    fun buildXmpPacketXml(metadata: StamperMetadata): String {
        val escapedNote = escapeXml(metadata.note)
        val escapedProject = escapeXml(metadata.project)
        val escapedCapturedAt = escapeXml(metadata.capturedAt)
        val escapedSource = escapeXml(metadata.source)
        val escapedCorner = escapeXml(metadata.watermarkCorner)

        return """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:dc="http://purl.org/dc/elements/1.1/"
        xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"
        xmlns:xmp="http://ns.adobe.com/xap/1.0/"
        xmlns:stamper="https://stamper.local/ns/1.0/"
        photoshop:Headline="$escapedNote"
        xmp:CreateDate="$escapedCapturedAt"
        xmp:Label="$escapedProject"
        stamper:Schema="${escapeXml(metadata.schema)}"
        stamper:Project="$escapedProject"
        stamper:Note="$escapedNote"
        stamper:CapturedAt="$escapedCapturedAt"
        stamper:WatermarkApplied="${metadata.watermarkApplied}"
        stamper:WatermarkCorner="$escapedCorner"
        stamper:Source="$escapedSource">
      <dc:description>
        <rdf:Alt>
          <rdf:li xml:lang="x-default">$escapedNote</rdf:li>
        </rdf:Alt>
      </dc:description>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    private fun isXmpSegment(bytes: ByteArray, offset: Int, length: Int): Boolean {
        if (length < XMP_HEADER_BYTES.size) return false
        for (i in XMP_HEADER_BYTES.indices) {
            if (bytes[offset + i] != XMP_HEADER_BYTES[i]) return false
        }
        return true
    }

    private fun writeXmpApp1Segment(out: ByteArrayOutputStream, xmpPacketBytes: ByteArray) {
        val segLen = 2 + XMP_HEADER_BYTES.size + xmpPacketBytes.size
        out.write(MARKER_PREFIX)
        out.write(MARKER_APP1)
        out.write((segLen ushr 8) and 0xFF)
        out.write(segLen and 0xFF)
        out.write(XMP_HEADER_BYTES)
        out.write(xmpPacketBytes)
    }

    private fun escapeXml(input: String): String = buildString(input.length + 16) {
        for (ch in input) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\t') append(ch)
            }
        }
    }
}
