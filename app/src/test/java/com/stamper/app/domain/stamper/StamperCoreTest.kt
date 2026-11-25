package com.stamper.app.domain.stamper

import com.stamper.app.speech.SystemSpeechHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime

class StamperCoreTest {

    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val sampleMillis: Long = ZonedDateTime.of(
        2026, 11, 25, 10, 5, 12, 0, shanghaiZone
    ).toInstant().toEpochMilli()

    @Test
    fun `fileName puts voice note first and yyyyMMdd date last`() {
        val fileName = FileNameSanitizer.buildPhotoFileName(
            note = "302教室东南角投影仪",
            capturedAtMillis = sampleMillis,
            zoneId = shanghaiZone
        )
        assertEquals("302教室东南角投影仪_20261125.jpg", fileName)
    }

    @Test
    fun `fileName appends incrementing suffix on collision and sanitizes illegal chars`() {
        val first = FileNameSanitizer.buildPhotoFileName(
            note = " 302/教室:东南角*投影仪? ",
            capturedAtMillis = sampleMillis,
            existingNames = setOf("302_教室_东南角_投影仪_20261125.jpg"),
            zoneId = shanghaiZone
        )
        assertEquals("302_教室_东南角_投影仪_20261125_2.jpg", first)
    }

    @Test
    fun `fileName falls back cleanly when note is blank`() {
        val fallback = FileNameSanitizer.buildPhotoFileName(
            note = "   ",
            capturedAtMillis = sampleMillis,
            zoneId = shanghaiZone
        )
        assertEquals("未备注_20261125_100512.jpg", fallback)
    }

    @Test
    fun `exif UserComment JSON is pure 7-bit ASCII and round-trips Chinese accurately`() {
        val original = StamperMetadata(
            project = "一号楼弱电施工",
            capturedAt = "2026-11-25T10:05:12+08:00",
            note = "302教室东南角\"吊装\"投影仪",
            watermarkApplied = true,
            watermarkCorner = WatermarkCorner.BOTTOM_LEFT.name,
            source = CaptureSource.IN_APP_CAMERA.wireValue,
            gpsLat = 31.2304,
            gpsLng = 121.4737
        )

        val asciiJson = original.toExifAsciiSafeJson()
        assertTrue(
            "EXIF JSON must be pure 7-bit ASCII to avoid UTF-16/Latin-1 mojibake",
            asciiJson.all { it.code in 0x20..0x7E }
        )

        // Simulate ExifInterface ASCII prefix when reading back UserComment
        val simulatedExifValue = "ASCII\u0000\u0000\u0000$asciiJson"
        val parsed = StamperMetadata.parseFromUserComment(simulatedExifValue)
        assertNotNull(parsed)
        assertEquals(original.project, parsed!!.project)
        assertEquals(original.note, parsed.note)
        assertEquals(original.capturedAt, parsed.capturedAt)
        assertEquals(original.watermarkApplied, parsed.watermarkApplied)
        assertEquals(31.2304, parsed.gpsLat ?: 0.0, 0.0001)
        assertEquals(121.4737, parsed.gpsLng ?: 0.0, 0.0001)
    }

    @Test
    fun `JpegXmpWriter injects and replaces UTF-8 Chinese XMP without corrupting JPEG`() {
        val minimalJpeg = buildMinimalValidJpeg()
        val meta1 = StamperMetadata(
            project = "教学楼改造",
            capturedAt = "2026-11-25T10:05:12+08:00",
            note = "302教室东南角投影仪",
            watermarkApplied = true,
            watermarkCorner = "BOTTOM_LEFT",
            source = "in_app_camera"
        )

        val injectedOnce = JpegXmpWriter.injectXmp(minimalJpeg, meta1)
        assertEquals(0xFF, injectedOnce[0].toInt() and 0xFF)
        assertEquals(0xD8, injectedOnce[1].toInt() and 0xFF)
        assertEquals(0xFF, injectedOnce[injectedOnce.size - 2].toInt() and 0xFF)
        assertEquals(0xD9, injectedOnce[injectedOnce.size - 1].toInt() and 0xFF)

        val extractedXml1 = JpegXmpWriter.extractXmpPacket(injectedOnce)
        assertNotNull(extractedXml1)
        assertTrue(extractedXml1!!.contains("302教室东南角投影仪"))
        assertTrue(extractedXml1.contains("教学楼改造"))

        // Replace existing XMP with updated note and verify old note is replaced cleanly
        val meta2 = meta1.copy(note = "405会议室北墙配电箱")
        val injectedTwice = JpegXmpWriter.injectXmp(injectedOnce, meta2)
        val extractedXml2 = JpegXmpWriter.extractXmpPacket(injectedTwice)
        assertNotNull(extractedXml2)
        assertTrue(extractedXml2!!.contains("405会议室北墙配电箱"))
        assertTrue(!extractedXml2.contains("302教室东南角投影仪"))
    }

    @Test
    fun `watermark text contains only trimmed voice note without date or project`() {
        assertEquals("302教室东南角投影仪", WatermarkRenderer.resolveWatermarkText("  302教室东南角投影仪  "))
        assertEquals("", WatermarkRenderer.resolveWatermarkText("   "))
    }

    @Test
    fun `appendRecognizedSegment merges partial dictation without dropping prior text`() {
        assertEquals(
            "302教室 东南角投影仪",
            SystemSpeechHelper.appendRecognizedSegment("302教室", "东南角投影仪。")
        )
        assertEquals(
            "302教室，东南角投影仪",
            SystemSpeechHelper.appendRecognizedSegment("302教室，", "东南角投影仪")
        )
        assertEquals(
            "302教室东南角投影仪",
            SystemSpeechHelper.appendRecognizedSegment("", "  302教室东南角投影仪 ")
        )
    }

    @Test
    fun `changing photo project group updates EXIF and XMP project while preserving voice note`() {
        val minimalJpeg = buildMinimalValidJpeg()
        val initialMeta = StamperMetadata(
            project = "默认项目",
            capturedAt = "2026-11-25T10:05:12+08:00",
            note = "302教室东南角投影仪",
            watermarkApplied = true,
            watermarkCorner = "BOTTOM_LEFT",
            source = "in_app_camera"
        )
        val initialJpeg = JpegXmpWriter.injectXmp(minimalJpeg, initialMeta)

        // Simulate moving photo to a new project group ("三号楼弱电")
        val movedMeta = initialMeta.copy(project = "三号楼弱电")
        val movedJpeg = JpegXmpWriter.injectXmp(initialJpeg, movedMeta)

        val xmp = JpegXmpWriter.extractXmpPacket(movedJpeg)
        assertNotNull(xmp)
        assertTrue(xmp!!.contains("三号楼弱电"))
        assertTrue(!xmp.contains("默认项目"))
        assertTrue(xmp.contains("302教室东南角投影仪"))

        val exifParsed = StamperMetadata.parseFromUserComment(movedMeta.toExifAsciiSafeJson())
        assertNotNull(exifParsed)
        assertEquals("三号楼弱电", exifParsed!!.project)
        assertEquals("302教室东南角投影仪", exifParsed.note)
    }

    private fun buildMinimalValidJpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // Dummy APP1 EXIF segment: FF E1, length = 10, "Exif\0\0\0\0"
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x0A))
        out.write("Exif\u0000\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII))
        // SOS marker + dummy compressed scan + EOI
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x04, 0x00, 0x00, 0x11, 0x22))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }
}
