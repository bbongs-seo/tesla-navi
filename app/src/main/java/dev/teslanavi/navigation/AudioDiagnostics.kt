package dev.teslanavi.navigation

import android.content.Context
import android.util.Log
import org.json.JSONArray

/** Bounded, device-local playback history. Never records destinations, VINs or spoken text. */
internal object AudioDiagnostics {
    @Synchronized fun record(context: Context, event: String) {
        Log.i("TeslaNaviAudio", event)
        runCatching {
            val prefs = context.getSharedPreferences("audio-diagnostics", Context.MODE_PRIVATE)
            val old = JSONArray(prefs.getString("events", "[]"))
            val next = JSONArray()
            for (i in (old.length() - 199).coerceAtLeast(0) until old.length()) next.put(old.get(i))
            next.put("${System.currentTimeMillis()} $event")
            prefs.edit().putString("events", next.toString()).apply()
        }
    }
}
