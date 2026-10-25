package dev.teslanavi.navigation

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Decode Android TTS WAV output into the same mono PCM16 format used by the other guidance sounds. */
internal object SystemSpeechWave {
    fun read(file: File): Pcm {
        require(file.length() in 44..16_000_000) { "TTS 음성 파일이 없거나 크기가 잘못되었습니다." }
        return decode(file.readBytes())
    }

    fun decode(bytes: ByteArray): Pcm {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        require(bytes.size >= 44 && tag(0) == "RIFF" && tag(8) == "WAVE") { "TTS WAV 형식 오류" }
        var format = 0; var channels = 0; var rate = 0; var bits = 0
        var dataAt = 0; var dataSize = 0
        var at = 12
        while (at <= bytes.size - 8) {
            val size = buffer.getInt(at + 4)
            require(size >= 0 && size <= bytes.size - at - 8) { "TTS WAV 길이 오류" }
            when (tag(at)) {
                "fmt " -> {
                    require(size >= 16)
                    format = buffer.getShort(at + 8).toInt()
                    channels = buffer.getShort(at + 10).toInt()
                    rate = buffer.getInt(at + 12)
                    bits = buffer.getShort(at + 22).toInt()
                }
                "data" -> { dataAt = at + 8; dataSize = size }
            }
            at += 8 + size + (size and 1)
        }
        require(channels in 1..2 && rate in 8_000..96_000 && dataSize > 0)
        require((format == 1 && bits in setOf(8, 16)) || (format == 3 && bits == 32)) { "지원하지 않는 TTS PCM 형식" }
        val sampleBytes = bits / 8
        val frameBytes = sampleBytes * channels
        require(dataSize % frameBytes == 0)
        val output = ByteBuffer.allocate(dataSize / frameBytes * 2).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0
        for (offset in dataAt until dataAt + dataSize step frameBytes) {
            var sum = 0
            repeat(channels) { channel ->
                val pos = offset + channel * sampleBytes
                sum += when {
                    format == 3 -> {
                        val value = buffer.getFloat(pos)
                        require(value.isFinite())
                        (value.coerceIn(-1f, 1f) * 32767).roundToInt()
                    }
                    bits == 8 -> ((bytes[pos].toInt() and 255) - 128) * 256
                    else -> buffer.getShort(pos).toInt()
                }
            }
            val sample = sum / channels
            peak = maxOf(peak, abs(sample))
            output.putShort(sample.toShort())
        }
        require(peak > 0) { "TTS가 무음 데이터를 생성했습니다." }
        return Pcm(output.array(), rate)
    }
}
