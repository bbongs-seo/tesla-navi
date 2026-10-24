package dev.teslanavi.drive

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The filtered low-power scan is registered with a PendingIntent, so Android keeps it and wakes
 * [ProximityScanReceiver] even after the app process was killed. Force-stop still cancels it.
 * Results use the default ALL_MATCHES delivery because [ProximityGate] relies on repeated sightings
 * for its retry interval and departure detection.
 */
@SuppressLint("MissingPermission")
internal object ProximityScan {
    const val ACTION = "dev.teslanavi.VEHICLE_ADVERTISEMENT"
    private const val REQUEST = 20

    /** Scan errors that Android reports through the PendingIntent after registration succeeded. */
    val failures = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    fun arm(context: Context, names: Set<String>) {
        val scanner = scanner(context) ?: error("Bluetooth 검색 불가")
        val callback = intent(context)
        // Registering the same PendingIntent twice fails, including one left by a killed process.
        runCatching { scanner.stopScan(callback) }
        val code = scanner.startScan(names.map { ScanFilter.Builder().setDeviceName(it).build() },
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), callback)
        check(code == 0) { "BLE 검색 오류 $code" }
    }

    fun disarm(context: Context) { runCatching { scanner(context)?.stopScan(intent(context)) } }

    private fun scanner(context: Context) =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    // Android writes the results into this intent, so it must be mutable on Android 12 and later.
    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, REQUEST,
        Intent(context, ProximityScanReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
}
