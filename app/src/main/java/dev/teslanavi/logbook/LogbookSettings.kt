package dev.teslanavi.logbook

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Charging rate used for 원/km; the logbook on/off switch lives in FeatureSettings. */
class LogbookSettings(context: Context) {
    private val prefs = context.getSharedPreferences("logbook-settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(normalize(prefs.getInt("won-per-kwh", DEFAULT_WON_PER_KWH)))
    val wonPerKwh = mutable.asStateFlow()
    fun rate(value: Int) {
        val next = normalize(value)
        check(prefs.edit().putInt("won-per-kwh", next).commit())
        mutable.value = next
    }
    private fun normalize(value: Int) = value.coerceIn(1, 2000)
}
