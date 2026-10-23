package dev.teslanavi.data

import android.content.Context
import dev.teslanavi.protocol.VehicleIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class VehicleProfile(val vin: String, val model: String) {
    val label: String get() = "$model · ${vin.takeLast(6)}"
}

class VehicleStore(context: Context) {
    private val prefs = context.getSharedPreferences("vehicles", Context.MODE_PRIVATE)
    private val _vehicles = MutableStateFlow(load())
    val vehicles = _vehicles.asStateFlow()
    val selected = MutableStateFlow(prefs.getString("selected", null)?.takeIf { vin -> _vehicles.value.any { it.vin == vin } }
        ?: _vehicles.value.firstOrNull()?.vin)

    private fun load(): List<VehicleProfile> = runCatching {
        val array = JSONArray(prefs.getString("list", "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.map {
            VehicleProfile(it.getString("vin"), it.getString("model"))
        }.filter { VehicleIdentity.isValidVin(it.vin) && it.model in setOf("Model 3", "Model Y") }.distinctBy { it.vin }
    }.getOrDefault(emptyList())

    fun add(model: String, input: String) {
        val vin = VehicleIdentity.normalizeVin(input)
        require(VehicleIdentity.isValidVin(vin)) { "VIN은 I·O·Q를 제외한 영문과 숫자 17자리입니다." }
        require(model in setOf("Model 3", "Model Y"))
        val items = _vehicles.value.filterNot { it.vin == vin } + VehicleProfile(vin, model)
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("vin", it.vin).put("model", it.model)) }
        check(prefs.edit().putString("list", array.toString()).putString("selected", vin).commit()) { "차량 저장에 실패했습니다." }
        _vehicles.value = items
        selected.value = vin
    }

    fun select(vin: String) {
        require(_vehicles.value.any { it.vin == vin })
        prefs.edit().putString("selected", vin).apply()
        selected.value = vin
    }
}
