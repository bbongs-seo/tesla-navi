package dev.teslanavi.drive

import android.content.Context
import dev.teslanavi.TeslaNaviApp
import dev.teslanavi.BuildConfig
import dev.teslanavi.ble.AndroidBleTransport
import dev.teslanavi.data.VehicleKeyStore
import dev.teslanavi.data.VehicleProfile
import dev.teslanavi.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
                client = TeslaClient(profile.vin, keys, transport) { message -> status(message) }
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
                (context.applicationContext as TeslaNaviApp).autoConnect.verified(profile.vin)
                val reader = DriveStateReader(client::readDriveState, client::authenticate) { status(it) }
                var firstSnapshot = true
                while (currentCoroutineContext().isActive) {
                    val snapshot = reader.next()
                    val previous = _state.value.snapshot?.destination
                    _state.update { it.copy(snapshot = snapshot, updatedAt = System.currentTimeMillis(), error = null) }
                    if (firstSnapshot) {
                        status("주행 정보 수신 · 기어 ${snapshot.gear} · 속도 ${snapshot.speedKph?.toInt() ?: "미제공"} km/h")
                        firstSnapshot = false
                    }
                    if (previous != snapshot.destination) status(when (snapshot.destination) {
                        NavigationDestination.None -> "차량에 설정된 목적지가 없습니다"
                        is NavigationDestination.Active -> "차량 목적지를 확인했습니다"
                    })
                    failures = 0
                    withTimeoutOrNull(5000) { refresh.receive() }
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (error is KeyNotRegisteredException) (context.applicationContext as TeslaNaviApp).autoConnect.forgetVerification(profile.vin)
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
}
