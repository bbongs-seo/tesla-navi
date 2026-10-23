package dev.teslanavi.drive

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.teslanavi.MainActivity
import dev.teslanavi.R
import dev.teslanavi.TeslaNaviApp
import dev.teslanavi.protocol.VehicleIdentity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** A user-enabled, visible foreground service owns one filtered low-power scan. */
@SuppressLint("MissingPermission")
class AutoConnectService : Service() {
    private val app get() = application as TeslaNaviApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "자동 차량 연결", NotificationManager.IMPORTANCE_MIN))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            disable(this)
            return START_NOT_STICKY
        }
        if (!app.autoConnect.enabled.value || !AutoConnectSettings.ready(this)) {
            app.autoConnect.report("자동 연결 권한을 확인해 주세요")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification("차량 접근을 기다리고 있습니다"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (_: Exception) {
            app.autoConnect.report("앱에서 자동 연결을 다시 켜 주세요")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (app.drive.state.value.active) DriveService.notificationOwnerChanged(this)
        if (monitor?.isActive != true) monitor = scope.launch {
            launch {
                combine(app.autoConnect.status, app.drive.state, app.cameras.state) { message, drive, cameras ->
                    if (!drive.active) message else when (cameras.phase) {
                        dev.teslanavi.navigation.CameraPhase.WAITING_DRIVE -> "차량 연결됨 · 주행 시작 대기"
                        dev.teslanavi.navigation.CameraPhase.GUIDING -> "주행 중 · 카메라 안내"
                        else -> drive.status
                    }
                }.distinctUntilChanged().collect { message ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
                }
            }
            val radio = flow {
                while (currentCoroutineContext().isActive) {
                    val permissions = AutoConnectSettings.ready(this@AutoConnectService)
                    val bluetooth = permissions && runCatching {
                        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
                    }.getOrDefault(false)
                    val location = LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))
                    emit(Triple(permissions, bluetooth, location))
                    delay(10_000)
                }
            }.distinctUntilChanged()
            combine(app.vehicles.selected, app.autoConnect.verified, app.autoConnect.enabled, radio) { vin, verified, enabled, ready ->
                MonitorTarget(vin, vin in verified, enabled, ready.first, ready.second, ready.third)
            }.distinctUntilChanged().collectLatest { target ->
                app.autoConnect.proximity.reset()
                when {
                    !target.enabled -> stopSelf()
                    !target.permissions -> app.autoConnect.report("자동 연결 권한을 확인해 주세요")
                    !target.bluetooth -> app.autoConnect.report("Bluetooth를 켜면 자동 연결을 다시 시작합니다")
                    !target.location -> app.autoConnect.report("휴대폰 위치 기능을 켜면 자동 연결을 다시 시작합니다")
                    target.vin == null -> app.autoConnect.report("자동 연결할 차량을 등록해 주세요")
                    !target.verified -> app.autoConnect.report("먼저 차량에 앱 키를 등록하고 한 번 연결해 주세요")
                    else -> {
                        while (currentCoroutineContext().isActive) {
                            try { scan(target.vin) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) {
                                app.autoConnect.report("차량 검색을 다시 준비하고 있습니다")
                                delay(60_000)
                            }
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun scan(vin: String) {
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: error("Bluetooth 검색 불가")
        val name = VehicleIdentity.localName(vin)
        val failed = CompletableDeferred<Unit>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.scanRecord?.deviceName != name) return
                scope.launch {
                    if (app.vehicles.selected.value != vin || !app.autoConnect.enabled.value) return@launch
                    val nearby = app.autoConnect.proximity.observe(result.rssi, result.isConnectable, SystemClock.elapsedRealtime())
                    if (!nearby || app.drive.state.value.active) return@launch
                    app.autoConnect.report("차량을 발견해 연결하고 있습니다")
                    app.drive.prepare(vin, automatic = true)
                    try { DriveService.start(this@AutoConnectService, vin, enroll = false, automatic = true) }
                    catch (error: Exception) { app.drive.fail(error); app.autoConnect.report("앱에서 자동 연결을 다시 켜 주세요") }
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
            override fun onScanFailed(errorCode: Int) { failed.completeExceptionally(IllegalStateException("BLE 검색 오류 $errorCode")) }
        }
        try {
            app.autoConnect.report("차량 접근 대기 · 주행 시작 후 카메라 안내")
            scanner.startScan(listOf(ScanFilter.Builder().setDeviceName(name).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), callback)
            failed.await()
        } finally { failed.cancel(); runCatching { scanner.stopScan(callback) } }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 11, Intent(this, AutoConnectService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tesla Navi · 자동 연결").setContentText(message).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(0, "자동 연결 끄기", stop).build()
    }

    override fun onDestroy() { running = false; scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private data class MonitorTarget(val vin: String?, val verified: Boolean, val enabled: Boolean, val permissions: Boolean, val bluetooth: Boolean, val location: Boolean)

    companion object {
        @Volatile var running = false
            private set
        private const val CHANNEL = "auto-connection-quiet"
        private const val NOTIFICATION = 2
        private const val STOP = "dev.teslanavi.AUTO_STOP"
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AutoConnectService::class.java))
        }
        fun disable(context: Context) {
            val app = context.applicationContext as TeslaNaviApp
            app.autoConnect.setEnabled(false)
            running = false
            if (app.drive.state.value.active && !app.drive.state.value.automatic) DriveService.notificationOwnerChanged(context)
            context.stopService(Intent(context, AutoConnectService::class.java))
            if (app.drive.state.value.automatic && app.drive.state.value.active) DriveService.stop(context)
        }
    }
}
