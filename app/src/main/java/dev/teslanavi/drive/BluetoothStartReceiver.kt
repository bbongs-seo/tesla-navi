package dev.teslanavi.drive

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.teslanavi.TeslaNaviApp

class BluetoothStartReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TeslaNaviApp
        if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) app.bluetoothStart.radioOff()
            else app.bluetoothStart.refresh()
            return
        }
        if (intent.action !in setOf(BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED)) return
        val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        device ?: return
        try {
            val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
            app.bluetoothStart.connectionChanged(device.address, connected)
            app.bluetoothStart.refresh()
            if (!connected || device.address !in app.bluetoothStart.state.value.selectedDevices || !app.autoConnect.enabled.value) return
            if (!AutoConnectSettings.ready(context)) { app.autoConnect.report("자동 시작 권한을 확인해 주세요"); return }
            try { AutoConnectService.start(context) }
            catch (_: Exception) { app.autoConnect.report("자동 시작을 준비하지 못했습니다. 앱을 열어 다시 켜 주세요") }
        } catch (_: SecurityException) { app.autoConnect.report("근처 기기 권한을 확인해 주세요") }
    }
}
