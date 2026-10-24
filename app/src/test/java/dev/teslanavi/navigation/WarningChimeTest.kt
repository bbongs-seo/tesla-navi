package dev.teslanavi.navigation

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class WarningChimeTest {
    @Test fun bellHasHeadroomSmoothEdgesAndFitsRepeatInterval() {
        val pcm = WarningChime.pcm
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val samples = IntArray(pcm.size / 2) { buffer.short.toInt() }
        assertTrue(samples.size < WarningChime.SAMPLE_RATE * 2)
        assertEquals(0, samples.first())
        assertEquals(0, samples.last())
        assertTrue(samples.maxOf { abs(it) } in 10_000..28_000)
        assertTrue(samples.asList().zipWithNext().maxOf { (a, b) -> abs(a - b) } < 8000)
        // Export the exact application PCM for listening, without a second synthesizer.
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(1).putInt(WarningChime.SAMPLE_RATE)
        header.putInt(WarningChime.SAMPLE_RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        File("build/reports/audio/warning-chime.wav").apply { parentFile.mkdirs(); writeBytes(header.array() + pcm) }
    }
}
