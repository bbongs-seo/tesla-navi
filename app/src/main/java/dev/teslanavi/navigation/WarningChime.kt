package dev.teslanavi.navigation

import kotlin.math.*

/** Original two-note bell. Smooth edges and decaying overtones avoid hard clicks. */
object WarningChime {
    const val SAMPLE_RATE = 48_000
    val pcm: ByteArray by lazy {
        val duration = 1.25
        val samples = DoubleArray((SAMPLE_RATE * duration).toInt()) { i ->
            val time = i.toDouble() / SAMPLE_RATE
            fun note(start: Double, frequency: Double): Double {
                val t = time - start
                val length = .75
                if (t !in 0.0..length) return 0.0
                val attack = (1 - cos(PI * (t / .012).coerceAtMost(1.0))) / 2
                val release = ((length - t) / .08).coerceIn(0.0, 1.0).let { (1 - cos(PI * it)) / 2 }
                val fundamental = sin(2 * PI * frequency * t) * exp(-4.0 * t)
                val overtone = .28 * sin(2 * PI * frequency * 2 * t) * exp(-7.5 * t)
                val shimmer = .08 * sin(2 * PI * frequency * 3 * t) * exp(-11 * t)
                return (fundamental + overtone + shimmer) * attack * release
            }
            note(0.0, 1046.502) + note(.42, 783.991)
        }
        val scale = 27_000 / samples.maxOf { abs(it) }.coerceAtLeast(1.0)
        ByteArray(samples.size * 2).also { bytes ->
            samples.forEachIndexed { i, sample ->
                val value = (sample * scale).roundToInt()
                bytes[2 * i] = value.toByte()
                bytes[2 * i + 1] = (value shr 8).toByte()
            }
        }
    }
}
