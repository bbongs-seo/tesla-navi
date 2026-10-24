package dev.teslanavi.navigation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GuidanceVoice { SYSTEM, SUPERTONIC }

/** Volume is relative to the phone's media volume. Distances also bound overspeed alerts. */
data class GuidanceOptions(val volumePercent: Int = 100, val highwayMeters: Int = 500, val generalMeters: Int = 300,
    val voice: GuidanceVoice = GuidanceVoice.SYSTEM) {
    fun distance(road: RoadClass): Int = if (road == RoadClass.GENERAL) generalMeters else highwayMeters
}

class GuidanceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("guidance-settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(normalize(GuidanceOptions(
        prefs.getInt("volume", 100), prefs.getInt("highway", 500), prefs.getInt("general", 300),
        GuidanceVoice.entries.firstOrNull { it.name == prefs.getString("voice", null) } ?: GuidanceVoice.SYSTEM)))
    val options = mutable.asStateFlow()
    fun volume(value: Int) = save(options.value.copy(volumePercent = value))
    fun highway(value: Int) = save(options.value.copy(highwayMeters = value))
    fun general(value: Int) = save(options.value.copy(generalMeters = value))
    fun voice(value: GuidanceVoice) = save(options.value.copy(voice = value))
    /** Restores volume and distances; the voice stays, since it may depend on a downloaded model. */
    fun reset() = save(GuidanceOptions(voice = options.value.voice))
    private fun save(value: GuidanceOptions) {
        val next = normalize(value)
        check(prefs.edit().putInt("volume", next.volumePercent).putInt("highway", next.highwayMeters)
            .putInt("general", next.generalMeters).putString("voice", next.voice.name).commit())
        mutable.value = next
    }
    private fun normalize(value: GuidanceOptions) = value.copy(volumePercent = value.volumePercent.coerceIn(0, 100),
        highwayMeters = ((value.highwayMeters.coerceIn(100, 2000) + 50) / 100) * 100,
        generalMeters = ((value.generalMeters.coerceIn(100, 1000) + 50) / 100) * 100)
}
