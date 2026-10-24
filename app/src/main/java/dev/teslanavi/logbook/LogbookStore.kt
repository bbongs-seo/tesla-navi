package dev.teslanavi.logbook

import android.content.Context
import dev.teslanavi.protocol.ChargeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Charge entries and the last unplugged reading, per VIN, in app-private storage. */
class LogbookStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("charge-logbook", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(loadEntries())
    val entries = _entries.asStateFlow()
    private var observations = loadObservations()

    private fun loadEntries(): Map<String, List<ChargeEntry>> = runCatching {
        val data = JSONObject(prefs.getString("entries", "{}")!!)
        data.keys().asSequence().associateWith { vin ->
            val array = data.getJSONArray(vin)
            (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull { item ->
                runCatching {
                    ChargeEntry(item.getString("id"), item.getLong("time"), item.getDouble("odometer"), item.getDouble("kwh"),
                        EntrySource.valueOf(item.getString("source")))
                }.getOrNull()?.takeIf { valid(it.odometerKm, it.energyKwh) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun loadObservations(): Map<String, ChargeObservation> = runCatching {
        val data = JSONObject(prefs.getString("observations", "{}")!!)
        data.keys().asSequence().associateWith { vin ->
            val item = data.getJSONObject(vin)
            ChargeObservation(item.getDouble("kwh"), if (item.has("battery")) item.getInt("battery") else null)
        }
    }.getOrDefault(emptyMap())

    @Synchronized fun add(vin: String, odometerKm: Double, energyKwh: Double, source: EntrySource = EntrySource.MANUAL): ChargeEntry {
        require(odometerKm.isFinite() && odometerKm in 0.0..MAX_ODOMETER_KM) { "누적 주행거리를 확인해 주세요." }
        require(energyKwh.isFinite() && energyKwh in MIN_ENERGY_KWH..MAX_ENERGY_KWH) { "충전량은 0.1~200 kWh 사이로 입력해 주세요." }
        val entry = ChargeEntry(UUID.randomUUID().toString(), clock(), odometerKm, energyKwh, source)
        saveEntries(vin, (_entries.value[vin].orEmpty() + entry))
        return entry
    }

    @Synchronized fun delete(vin: String, id: String) = saveEntries(vin, _entries.value[vin].orEmpty().filterNot { it.id == id })

    /** Applies one vehicle charge reading; returns what was decided so the caller can log it. */
    @Synchronized fun observe(vin: String, charge: ChargeSnapshot, odometerKm: Double?): ChargeDetection {
        val latestKm = _entries.value[vin]?.maxOfOrNull { it.odometerKm }
        val decision = ChargeDetector.decide(observations[vin], charge, odometerKm, latestKm)
        val detection = decision.detection
        if (detection is ChargeDetection.NewCharge) add(vin, detection.odometerKm, detection.energyKwh, EntrySource.AUTO)
        if (decision.observation != null && decision.observation != observations[vin]) saveObservation(vin, decision.observation)
        return detection
    }

    private fun saveEntries(vin: String, items: List<ChargeEntry>) {
        val next = _entries.value + (vin to ChargeLogbook.sorted(items).takeLast(LIMIT))
        val data = JSONObject()
        next.forEach { (key, list) ->
            data.put(key, JSONArray().apply {
                list.forEach { put(JSONObject().put("id", it.id).put("time", it.timeMs).put("odometer", it.odometerKm)
                    .put("kwh", it.energyKwh).put("source", it.source.name)) }
            })
        }
        check(prefs.edit().putString("entries", data.toString()).commit()) { "충전 기록 저장에 실패했습니다." }
        _entries.value = next
    }

    private fun saveObservation(vin: String, observation: ChargeObservation) {
        val next = observations + (vin to observation)
        val data = JSONObject()
        next.forEach { (key, value) ->
            data.put(key, JSONObject().put("kwh", value.energyAddedKwh).apply { value.batteryLevel?.let { put("battery", it) } })
        }
        check(prefs.edit().putString("observations", data.toString()).commit())
        observations = next
    }

    private fun valid(odometerKm: Double, energyKwh: Double) =
        odometerKm.isFinite() && odometerKm in 0.0..MAX_ODOMETER_KM && energyKwh.isFinite() && energyKwh in MIN_ENERGY_KWH..MAX_ENERGY_KWH

    companion object {
        const val LIMIT = 500
        private const val MAX_ODOMETER_KM = 2_000_000.0
        private const val MIN_ENERGY_KWH = 0.1
        private const val MAX_ENERGY_KWH = 200.0
    }
}
