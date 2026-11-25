package com.stamper.app.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class OfflineAsrDeviceTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun bundledSenseVoiceModelTranscribesMandarinSiteNotesWithDigits() {
        assertTrue(OfflineAsr.isBundled(appContext))
        assertEquals("302教室东南角投影仪", OfflineAsr.transcribe(appContext, readWav("asr_302_projector.wav")))
        assertEquals("405会议室北墙网络面板", OfflineAsr.transcribe(appContext, readWav("asr_405_panel.wav")))
    }

    private fun readWav(name: String): FloatArray {
        val bytes = testContext.assets.open(name).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                val count = size / 2
                return FloatArray(count) { i -> buf.getShort(pos + 8 + i * 2) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in $name")
    }
}
