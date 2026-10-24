package dev.teslanavi.logbook

import android.content.Context
import dev.teslanavi.protocol.Gear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Recorded trips per VIN in app-private storage, plus one open-trip tracker per vehicle. */
class TripStore(context: Context) {
    private val prefs = context.getSharedPreferences("drive-trips", Context.MODE_PRIVATE)
    private val _trips = MutableStateFlow(load())
    val trips = _trips.asStateFlow()
    private val trackers = mutableMapOf<String, TripTracker>()

    private fun load(): Map<String, List<DriveTrip>> = runCatching {
        val data = JSONObject(prefs.getString("trips", "{}")!!)
        data.keys().asSequence().associateWith { vin ->
            val array = data.getJSONArray(vin)
            (0 until array.length()).mapNotNull { index ->
                runCatching {
                    val item = array.getJSONObject(index)
                    fun optDouble(key: String) = if (item.has(key)) item.getDouble(key) else null
                    fun optInt(key: String) = if (item.has(key)) item.getInt(key) else null
                    DriveTrip(item.getString("id"), item.getLong("start"), item.getLong("end"), optDouble("startKm"), optDouble("endKm"),
                        item.getDouble("km"), item.getDouble("maxKph"), optInt("startBattery"), optInt("endBattery"))
                }.getOrNull()?.takeIf { it.distanceKm.isFinite() && it.distanceKm >= 0 && it.endMs >= it.startMs }
            }
        }
    }.getOrDefault(emptyMap())

    @Synchronized fun sample(vin: String, timeMs: Long, gear: Gear, speedKph: Double?, odometerKm: Double?, battery: Int?): DriveTrip? =
        trackers.getOrPut(vin) { TripTracker() }.sample(timeMs, gear, speedKph, odometerKm, battery)?.also { add(vin, it) }

    @Synchronized fun finish(vin: String): DriveTrip? = trackers[vin]?.finish()?.also { add(vin, it) }

    @Synchronized fun add(vin: String, trip: DriveTrip) = save(vin, _trips.value[vin].orEmpty() + trip)

    @Synchronized fun delete(vin: String, id: String) = save(vin, _trips.value[vin].orEmpty().filterNot { it.id == id })

    /** A battery read that lands just after parking is the real end value; the reading at P can be minutes old. */
    @Synchronized fun lateBattery(vin: String, level: Int, atMs: Long) {
        val items = _trips.value[vin] ?: return
        val last = items.maxByOrNull { it.endMs } ?: return
        if (atMs - last.endMs !in 0..LATE_BATTERY_MS || last.endBattery == level) return
        save(vin, items.map { if (it.id == last.id) it.copy(endBattery = level) else it })
    }

    private fun save(vin: String, items: List<DriveTrip>) {
        val next = _trips.value + (vin to items.sortedBy { it.startMs }.takeLast(LIMIT))
        val data = JSONObject()
        next.forEach { (key, list) ->
            data.put(key, JSONArray().apply {
                list.forEach { trip ->
                    put(JSONObject().put("id", trip.id).put("start", trip.startMs).put("end", trip.endMs).put("km", trip.distanceKm)
                        .put("maxKph", trip.maxSpeedKph).apply {
                            trip.startKm?.let { put("startKm", it) }
                            trip.endKm?.let { put("endKm", it) }
                            trip.startBattery?.let { put("startBattery", it) }
                            trip.endBattery?.let { put("endBattery", it) }
                        })
                }
            })
        }
        check(prefs.edit().putString("trips", data.toString()).commit()) { "주행 기록 저장에 실패했습니다." }
        _trips.value = next
    }

    companion object {
        const val LIMIT = 1000
        const val LATE_BATTERY_MS = 3 * 60_000L
    }
}
