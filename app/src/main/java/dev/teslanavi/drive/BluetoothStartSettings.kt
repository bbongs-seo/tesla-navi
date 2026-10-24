package dev.teslanavi.drive

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

data class StartBluetoothDevice(val address: String, val name: String, val connected: Boolean)
data class BluetoothStartState(
    val selectedDevices: Map<String, String> = emptyMap(),
    val devices: List<StartBluetoothDevice> = emptyList(),
    val message: String? = null,
) {
    val allowed get() = selectedDevices.isEmpty() || devices.any { it.address in selectedDevices && it.connected }
    val selectedNames get() = selectedDevices.values.joinToString(", ")
}

/** Saves only the user's chosen bonded device; live connection state is never restored from disk. */
@SuppressLint("MissingPermission")
class BluetoothStartSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("bluetooth-start", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(BluetoothStartState(selectedDevices = runCatching {
        if (prefs.contains("devices")) {
            val saved = JSONObject(prefs.getString("devices", "{}")!!)
            saved.keys().asSequence().associateWith { saved.getString(it) }
        } else prefs.getString("address", null)?.let { mapOf(it to (prefs.getString("name", null) ?: "이름 없는 기기")) }.orEmpty()
    }.getOrDefault(emptyMap())))
    val state = mutable.asStateFlow()
    private val acl = mutableSetOf<String>()
    private val profiles = mutableMapOf<Int, Set<String>>()
    private var generation = 0

    fun select(devices: Map<String, String>) {
        val selected = devices.toMap()
        check(prefs.edit().putString("devices", JSONObject(selected).toString()).remove("address").remove("name").commit())
        mutable.update { it.copy(selectedDevices = selected) }
    }

    fun connectionChanged(address: String, connected: Boolean) {
        generation++
        if (connected) acl.add(address) else {
            acl.remove(address)
            profiles.replaceAll { _, devices -> devices - address }
        }
        publishConnections()
    }

    fun radioOff() {
        generation++
        acl.clear(); profiles.clear()
        publishConnections()
    }

    private fun publishConnections() {
        val connected = acl + profiles.values.flatten()
        mutable.update { it.copy(devices = it.devices.map { device -> device.copy(connected = device.address in connected) }) }
    }

    fun refresh() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            radioOff()
            mutable.update { it.copy(devices = emptyList(), message = "목록을 보려면 근처 기기 권한을 허용해 주세요.") }
            return
        }
        runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled != true) {
                radioOff()
                mutable.update { it.copy(message = "Bluetooth를 켜 주세요.") }
                return
            }
            profiles[BluetoothProfile.GATT] = context.getSystemService(BluetoothManager::class.java)
                .getConnectedDevices(BluetoothProfile.GATT).map { it.address }.toSet()
            val connected = acl + profiles.values.flatten()
            val devices = adapter.bondedDevices.map {
                StartBluetoothDevice(it.address, it.name?.takeIf(String::isNotBlank) ?: "이름 없는 기기", it.address in connected)
            }.sortedBy { it.name }
            mutable.update { it.copy(devices = devices, message = if (devices.isEmpty()) "등록된 기기가 없습니다. 휴대폰 Bluetooth 설정에서 먼저 연결해 주세요." else null) }
            val token = ++generation
            for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                        try {
                            if (token == generation) {
                                profiles[profileId] = proxy.connectedDevices.map { it.address }.toSet()
                                publishConnections()
                            }
                        } catch (_: SecurityException) { } finally { adapter.closeProfileProxy(profileId, proxy) }
                    }
                    override fun onServiceDisconnected(profileId: Int) = Unit
                }, profile)
            }
        }.onFailure { mutable.update { it.copy(message = "블루투스 목록을 가져오지 못했습니다. 권한을 확인해 주세요.") } }
    }
}
