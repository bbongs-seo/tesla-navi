package dev.teslanavi.data

import android.content.Context
import dev.teslanavi.protocol.BatterySnapshot
import dev.teslanavi.protocol.VehicleIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.math.roundToInt

data class BatteryReading(val levelPercent: Int?, val rangeKm: Double?, val receivedAt: Long) {
    val summary get() = listOfNotNull(levelPercent?.let { "배터리 $it%" }, rangeKm?.let { "주행 가능 ${it.roundToInt()} km" }).joinToString(" · ")
}

fun batteryAge(minutes: Long): String = when {
    minutes < 1 -> "방금"
    minutes < 60 -> "${minutes}분 전"
    minutes < 24 * 60 -> "${minutes / 60}시간 전"
    else -> "${minutes / (24 * 60)}일 전"
}

/** Last battery reading per vehicle, kept across restarts so the main screen can show it with its age while disconnected. */
class BatteryStore(context: Context) {
    private val prefs = context.getSharedPreferences("battery", Context.MODE_PRIVATE)
    private val _readings = MutableStateFlow(load())
    val readings = _readings.asStateFlow()

    private fun load(): Map<String, BatteryReading> = runCatching {
        val data = JSONObject(prefs.getString("readings", "{}") ?: "{}")
        data.keys().asSequence().filter { VehicleIdentity.isValidVin(it) }.mapNotNull { vin ->
            val item = data.getJSONObject(vin)
            BatteryReading(item.optInt("level", -1).takeIf { it in 0..100 },
                item.optDouble("rangeKm").takeIf { it.isFinite() && it >= 0 }, item.optLong("at"))
                .takeIf { (it.levelPercent != null || it.rangeKm != null) && it.receivedAt > 0 }?.let { vin to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    @Synchronized fun save(vin: String, battery: BatterySnapshot, receivedAt: Long) {
        if (battery.empty) return
        val readings = _readings.value + (vin to BatteryReading(battery.levelPercent, battery.rangeKm, receivedAt))
        val data = JSONObject()
        readings.forEach { (key, reading) ->
            data.put(key, JSONObject().put("at", reading.receivedAt).apply {
                reading.levelPercent?.let { put("level", it) }
                reading.rangeKm?.let { put("rangeKm", it) }
            })
        }
        prefs.edit().putString("readings", data.toString()).apply()
        _readings.value = readings
    }
}
