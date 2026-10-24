package dev.teslanavi.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional features start off. The settings tab moves vehicle and guidance controls off the main screen;
 * the logbook adds its own tab and its vehicle charge reads.
 */
class FeatureSettings(context: Context) {
    private val prefs = context.getSharedPreferences("features", Context.MODE_PRIVATE)
    private val _settingsTab = MutableStateFlow(prefs.getBoolean("settings-tab", false))
    val settingsTab = _settingsTab.asStateFlow()
    private val _logbook = MutableStateFlow(prefs.getBoolean("logbook", false))
    val logbook = _logbook.asStateFlow()

    fun setSettingsTab(value: Boolean) {
        check(prefs.edit().putBoolean("settings-tab", value).commit())
        _settingsTab.value = value
    }

    fun setLogbook(value: Boolean) {
        check(prefs.edit().putBoolean("logbook", value).commit())
        _logbook.value = value
    }
}
