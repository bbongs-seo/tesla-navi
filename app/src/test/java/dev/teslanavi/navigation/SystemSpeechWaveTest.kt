package dev.teslanavi.navigation

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun speechWave(data: ByteArray = ByteArray(4800) { if (it % 2 == 0) 10 else 0 },
    channels: Int = 1, bits: Int = 16, format: Int = 1): ByteArray =
    ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(format.toShort()); putShort(channels.toShort()); putInt(24_000)
        putInt(24_000 * channels * bits / 8); putShort((channels * bits / 8).toShort()); putShort(bits.toShort())
        put("data".toByteArray()); putInt(data.size); put(data)
    }.array()

class SystemSpeechWaveTest {
    @Test fun decodesMonoAndDownmixesStereo() {
        val mono = SystemSpeechWave.decode(speechWave())
        assertEquals(24_000, mono.sampleRate)
        assertEquals(2400, mono.frames)
        val stereo = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(1000).putShort(3000).array()
        val pcm = SystemSpeechWave.decode(speechWave(stereo, channels = 2))
        assertEquals(2000, ByteBuffer.wrap(pcm.bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
    }

    @Test fun supportsEightBitAndFloatTtsOutput() {
        assertEquals(32512, ByteBuffer.wrap(SystemSpeechWave.decode(speechWave(byteArrayOf(255.toByte(), 128.toByte()), bits = 8)).bytes)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        val float = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).array()
        assertEquals(16384, ByteBuffer.wrap(SystemSpeechWave.decode(speechWave(float, bits = 32, format = 3)).bytes)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt())
    }

    @Test fun rejectsEmptySilentAndTruncatedOutput() {
        for (bytes in listOf(ByteArray(0), speechWave(ByteArray(4800)), speechWave().copyOf(45), speechWave(bits = 24))) {
            try { SystemSpeechWave.decode(bytes); fail("invalid speech accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
