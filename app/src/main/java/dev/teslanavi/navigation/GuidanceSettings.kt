package dev.teslanavi.navigation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GuidanceVoice { SYSTEM, SUPERTONIC }

/** voice.bin is ordered F1..F5, then M1..M5 by the upstream converter. */
enum class SupertonicSpeaker(val sid: Int, val label: String) {
    F1(0, "여성 1 · F1"), F2(1, "여성 2 · F2"), F3(2, "여성 3 · F3"),
    F4(3, "여성 4 · F4"), F5(4, "여성 5 · F5"),
    M1(5, "남성 1 · M1"), M2(6, "남성 2 · M2"), M3(7, "남성 3 · M3"),
    M4(8, "남성 4 · M4"), M5(9, "남성 5 · M5")
}

/** Volume is relative to the phone's media volume. Distances also bound overspeed alerts. */
data class GuidanceOptions(val volumePercent: Int = 100, val highwayMeters: Int = 500, val generalMeters: Int = 300,
    val voice: GuidanceVoice = GuidanceVoice.SYSTEM, val supertonicSpeaker: SupertonicSpeaker = SupertonicSpeaker.F1) {
    fun distance(road: RoadClass): Int = if (road == RoadClass.GENERAL) generalMeters else highwayMeters
}

class GuidanceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("guidance-settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(normalize(GuidanceOptions(
        prefs.getInt("volume", 100), prefs.getInt("highway", 500), prefs.getInt("general", 300),
        GuidanceVoice.entries.firstOrNull { it.name == prefs.getString("voice", null) } ?: GuidanceVoice.SYSTEM,
        SupertonicSpeaker.entries.firstOrNull { it.name == prefs.getString("supertonic-speaker", null) } ?: SupertonicSpeaker.F1)))
    val options = mutable.asStateFlow()
    fun volume(value: Int) = save(options.value.copy(volumePercent = value))
    fun highway(value: Int) = save(options.value.copy(highwayMeters = value))
    fun general(value: Int) = save(options.value.copy(generalMeters = value))
    fun voice(value: GuidanceVoice) = save(options.value.copy(voice = value))
    fun supertonicSpeaker(value: SupertonicSpeaker) = save(options.value.copy(supertonicSpeaker = value))
    /** Restores volume and distances; the voice stays, since it may depend on a downloaded model. */
    fun reset() = save(GuidanceOptions(voice = options.value.voice, supertonicSpeaker = options.value.supertonicSpeaker))
    private fun save(value: GuidanceOptions) {
        val next = normalize(value)
        check(prefs.edit().putInt("volume", next.volumePercent).putInt("highway", next.highwayMeters)
            .putInt("general", next.generalMeters).putString("voice", next.voice.name)
            .putString("supertonic-speaker", next.supertonicSpeaker.name).commit())
        mutable.value = next
    }
    private fun normalize(value: GuidanceOptions) = value.copy(volumePercent = value.volumePercent.coerceIn(0, 100),
        highwayMeters = ((value.highwayMeters.coerceIn(100, 2000) + 50) / 100) * 100,
        generalMeters = ((value.generalMeters.coerceIn(100, 1000) + 50) / 100) * 100)
}
