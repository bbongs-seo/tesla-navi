package dev.teslanavi.drive

import android.content.Context
import dev.teslanavi.TeslaNaviApp
import dev.teslanavi.BuildConfig
import dev.teslanavi.ble.AndroidBleTransport
import dev.teslanavi.data.BatteryStore
import dev.teslanavi.data.VehicleKeyStore
import dev.teslanavi.data.VehicleProfile
import dev.teslanavi.logbook.ChargeDetection
import dev.teslanavi.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

private class EnrollmentApprovalTimeout : IllegalStateException("키 등록 승인을 확인하지 못했습니다. 차량의 키 목록과 키 카드 승인을 확인해 주세요.")

enum class DrivePhase { IDLE, CONNECTING, PAIRING, AUTHENTICATING, CONNECTED, RECONNECTING, ERROR }
data class DriveState(
    val vin: String? = null,
    val phase: DrivePhase = DrivePhase.IDLE,
    val status: String = "차량을 연결해 주세요",
    val snapshot: DriveSnapshot? = null,
    val updatedAt: Long? = null,
    val fingerprint: String? = null,
    val error: String? = null,
    val events: List<String> = emptyList(),
    val automatic: Boolean = false,
) {
    val active get() = phase !in setOf(DrivePhase.IDLE, DrivePhase.ERROR)
}

class DriveController(private val context: Context, private val keyStore: VehicleKeyStore) {
    private val history = ConnectionHistory(context).apply {
        append("앱 시작 · ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}")
    }
    private val _state = MutableStateFlow(DriveState(events = history.read()))
    val state = _state.asStateFlow()
    // Kept outside DriveState so reconnects, failures and app restarts keep the last reading for the main screen.
    private val batteryStore = BatteryStore(context)
    val battery = batteryStore.readings
    private val refresh = Channel<Unit>(Channel.CONFLATED)

    fun prepare(vin: String, automatic: Boolean = false) {
        _state.value = DriveState(vin, DrivePhase.CONNECTING, "연결 준비 중", automatic = automatic,
            events = history.append(if (automatic) "자동 연결 준비" else "수동 연결 준비"))
    }
    fun clear() { _state.value = DriveState(events = history.read()) }
    fun requestRefresh() { refresh.trySend(Unit) }
    private fun status(message: String, phase: DrivePhase? = null) {
        android.util.Log.i("TeslaNaviConnection", message)
        val events = history.append("[${phase ?: _state.value.phase}] $message")
        _state.update { it.copy(phase = phase ?: it.phase, status = message,
            events = events) }
    }
    /** History-only line; unlike status() it leaves the status text (and notification) alone. */
    private fun note(message: String) {
        android.util.Log.i("TeslaNaviConnection", message)
        val events = history.append("[${_state.value.phase}] $message")
        _state.update { it.copy(events = events) }
    }
    internal fun recordBattery(vin: String, battery: BatterySnapshot, receivedAt: Long = System.currentTimeMillis()) =
        batteryStore.save(vin, battery, receivedAt)
    fun fail(error: Throwable) {
        val message = when (error) {
            is TimeoutCancellationException -> "차량 응답 시간이 초과되었습니다. 차량 가까이에서 화면을 켜고 다시 연결해 주세요."
            is SecurityException -> "Bluetooth 권한을 허용한 뒤 다시 시도해 주세요."
            else -> error.message?.take(220) ?: "차량 연결을 완료하지 못했습니다. 다시 시도해 주세요."
        }
        android.util.Log.w("TeslaNaviConnection", "Connection failed: ${error.javaClass.simpleName}: $message")
        val events = history.append("실패 단계: ${_state.value.phase} · ${error.javaClass.simpleName} · $message")
        _state.update { it.copy(phase = DrivePhase.ERROR, status = "연결을 확인해 주세요", snapshot = null, updatedAt = null, error = message, events = events) }
    }

    suspend fun run(profile: VehicleProfile, enroll: Boolean) {
        status(if (enroll) "앱 키 등록 시작 · 저장된 키 준비 중" else "차량 인증 연결 시작 · 저장된 키 준비 중")
        val keys = withContext(Dispatchers.IO) { keyStore.getOrCreate(profile.vin) }
        _state.update { it.copy(fingerprint = TeslaKeys.fingerprint(keys.public)) }
        var enrolled = !enroll
        var enrollmentSent = false
        var enrollmentDeadline: Long? = null
        var failures = 0
        while (currentCoroutineContext().isActive) {
            var client: TeslaClient? = null
            try {
                val transport = AndroidBleTransport.connect(context, profile.vin) { status(it) }
                client = TeslaClient(profile.vin, keys, transport)
                if (!enrolled) {
                    if (!enrollmentSent) {
                        client.requestKeyEnrollment()
                        enrollmentSent = true
                        enrollmentDeadline = android.os.SystemClock.elapsedRealtime() + 90000
                    }
                    status("차량에서 키 카드를 인식시키고 등록을 승인해 주세요", DrivePhase.PAIRING)
                    val expires = checkNotNull(enrollmentDeadline)
                    while (true) {
                        if (android.os.SystemClock.elapsedRealtime() >= expires) throw EnrollmentApprovalTimeout()
                        try { client.authenticate(); enrolled = true; break }
                        catch (error: Exception) {
                            if (error is CancellationException && error !is TimeoutCancellationException) throw error
                            val expected = error is KeyNotRegisteredException || error is TimeoutCancellationException ||
                                (error is VehicleFault && error.code in setOf(1, 2, 3, 11))
                            if (!expected) throw error
                            if (android.os.SystemClock.elapsedRealtime() >= expires) throw EnrollmentApprovalTimeout()
                            delay(2000)
                        }
                    }
                    status("앱 키 등록 확인 완료")
                } else {
                    status("차량 인증 중", DrivePhase.AUTHENTICATING)
                    client.authenticate()
                }
                status("목적지 확인 중", DrivePhase.CONNECTED)
                app.autoConnect.verified(profile.vin)
                var missedReads = 0
                val batteryPoll = BatteryPoll()
                lastChargeNote = null
                while (currentCoroutineContext().isActive) {
                    val snapshot = try {
                        client.readDriveState().also { missedReads = 0 }
                    } catch (error: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        missedReads++
                        status("주행 정보 응답 누락 ($missedReads/3) · BLE 연결 유지 후 재조회")
                        if (missedReads >= 3) throw error
                        delay(1000)
                        continue
                    }
                    val polledAt = android.os.SystemClock.elapsedRealtime()
                    val previous = _state.value.snapshot?.destination
                    _state.update { it.copy(snapshot = snapshot, updatedAt = System.currentTimeMillis(), error = null) }
                    if (previous != snapshot.destination) status(when (snapshot.destination) {
                        NavigationDestination.None -> "차량에 설정된 목적지가 없습니다"
                        is NavigationDestination.Active -> "차량 목적지를 확인했습니다"
                    })
                    failures = 0
                    if (batteryPoll.due(polledAt)) readBattery(client, profile.vin, batteryPoll, snapshot.odometerKm)
                    // The 4s battery read fits inside the 5s drive cadence instead of delaying the next drive read.
                    withTimeoutOrNull((polledAt + 5000 - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1)) { refresh.receive() }
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (error is KeyNotRegisteredException) app.autoConnect.forgetVerification(profile.vin)
                val terminal = error is KeyNotRegisteredException || error is EnrollmentApprovalTimeout || error is EnrollmentRejectedException || error is SecurityException ||
                    (error is VehicleFault && error.code !in setOf(1, 2, 11)) ||
                    error is IllegalArgumentException || error is java.security.GeneralSecurityException
                if (terminal || ++failures > 3) throw error
                // Clear stale destination immediately; a new connection establishes a new crypto session.
                _state.update { it.copy(snapshot = null, updatedAt = null) }
                status(error.message?.take(220) ?: "차량 응답 대기 실패")
                status("차량 재연결 중 ($failures/3)", DrivePhase.RECONNECTING)
            } finally { client?.close() }
            delay((failures * 2000L).coerceAtMost(6000))
        }
    }

    private val app get() = context.applicationContext as TeslaNaviApp
    private var lastChargeNote: String? = null

    /** Never throws except for cancellation: a missing or failed battery read must not end the drive session. */
    private suspend fun readBattery(client: TeslaClient, vin: String, poll: BatteryPoll, odometerKm: Double?) {
        val line = try {
            val charge = client.readChargeState()
            if (charge != null) recordBattery(vin, charge.battery)
            val now = android.os.SystemClock.elapsedRealtime()
            poll.received(charge?.battery, now).also {
                if (charge != null && app.features.logbook.value) recordCharge(vin, charge, odometerKm, poll, now)
            }
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            currentCoroutineContext().ensureActive()
            poll.failed(error, android.os.SystemClock.elapsedRealtime())
        }
        line?.let { note(it) }
    }

    /** The battery read feeds the logbook too; while a cable is in, read again soon so the unplug odometer is accurate. */
    private fun recordCharge(vin: String, charge: ChargeSnapshot, odometerKm: Double?, poll: BatteryPoll, now: Long) {
        val message = try {
            val detection = app.logbook.observe(vin, charge, odometerKm)
            if (detection == ChargeDetection.PluggedIn) poll.recheckBy(now + BatteryPoll.PLUGGED_RECHECK)
            when (detection) {
                ChargeDetection.PluggedIn -> "차계부 · 충전 케이블 연결됨 · 분리 후 기록"
                ChargeDetection.MissingEnergy -> "차계부 · 차량이 충전량을 제공하지 않음"
                ChargeDetection.Baseline -> "차계부 · 마지막 충전량을 기준으로 저장"
                ChargeDetection.Unchanged -> "차계부 · 새 충전 없음"
                ChargeDetection.MissingOdometer -> "차계부 · 주행거리 정보가 없어 기록 대기"
                ChargeDetection.AlreadyLogged -> "차계부 · 같은 주행거리의 충전 기록이 있어 건너뜀"
                is ChargeDetection.NewCharge -> String.format(Locale.KOREA, "차계부 · 충전 기록 추가 %.1f kWh · %,.0f km",
                    detection.energyKwh, detection.odometerKm)
            }
        } catch (error: Exception) {
            "차계부 기록 실패 · ${error.message?.take(160) ?: error.javaClass.simpleName}"
        }
        if (message != lastChargeNote) note(message)
        lastChargeNote = message
    }
}
