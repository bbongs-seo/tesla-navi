package dev.teslanavi.navigation

import dev.teslanavi.drive.DrivePhase
import dev.teslanavi.drive.DriveState
import dev.teslanavi.drive.TripMotionGate
import dev.teslanavi.protocol.NavigationDestination
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CameraController(
    private val engine: CameraNavigationEngine,
    private val speaker: CameraSpeaker,
    private val settings: StateFlow<GuidanceOptions> = MutableStateFlow(GuidanceOptions()),
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow(CameraState())
    val state = _state.asStateFlow()
    private val policy = CameraAlertPolicy()
    private val overSpeedPolicy = OverSpeedPolicy()
    private var tripVin: String? = null
    private var tripAnnounced = false
    private var pendingTrip = false
    private var acceptingFrames = false

    fun configured(hasKey: Boolean) {
        _state.value = CameraState(phase = if (hasKey) CameraPhase.IDLE else CameraPhase.KEY_REQUIRED,
            message = if (hasKey) "차량 연결 후 주행을 시작하면 카메라를 안내합니다" else "이 설치 파일에는 카카오 안내 키가 없습니다. 앱 업데이트가 필요합니다.",
            muted = _state.value.muted)
    }

    fun setMuted(muted: Boolean) {
        _state.update { it.copy(muted = muted) }
        if (muted) { speaker.setOverSpeedAlert(false); speaker.silence() }
    }

    fun blocked(message: String) {
        invalidate()
        _state.update { it.copy(phase = CameraPhase.ERROR, message = message, error = message) }
    }

    private fun invalidate() {
        acceptingFrames = false
        policy.clear()
        overSpeedPolicy.clear()
        speaker.setOverSpeedAlert(false)
        speaker.silence()
        _state.update { it.copy(cameras = emptyList(), locationAtMillis = null, currentSpeedKph = null,
            roadClass = RoadClass.UNKNOWN, overSpeed = false) }
    }

    suspend fun run(nativeKey: String, drive: StateFlow<DriveState>) {
        if (nativeKey.isBlank()) { configured(false); return }
        try {
            coroutineScope {
                _state.update { it.copy(phase = CameraPhase.WAITING_VEHICLE, message = "차량 연결을 기다리고 있습니다", error = null) }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    settings.collect { speaker.setVolume(it.volumePercent) }
                }
                val movement = TripMotionGate()
                var initialized = false
                launch(start = CoroutineStart.UNDISPATCHED) {
                    engine.events.collect { update ->
                        if (!acceptingFrames || update.revision != engine.revision) return@collect
                        val event = update.event
                        when (event) {
                            is NavigationEvent.Mode -> {
                                if (_state.value.mode != event.mode) {
                                    policy.clear(); overSpeedPolicy.clear(); speaker.setOverSpeedAlert(false); speaker.silence()
                                    _state.update { it.copy(cameras = emptyList(), locationAtMillis = null,
                                        currentSpeedKph = null, roadClass = RoadClass.UNKNOWN, overSpeed = false) }
                                }
                                _state.update { it.copy(mode = event.mode, message = event.message) }
                            }
                            is NavigationEvent.RouteInvalidated -> {
                                policy.clear(); overSpeedPolicy.clear(); speaker.setOverSpeedAlert(false); speaker.silence()
                                _state.update { it.copy(cameras = emptyList(), locationAtMillis = null,
                                    phase = CameraPhase.WAITING_GPS, message = event.message,
                                    currentSpeedKph = null, roadClass = RoadClass.UNKNOWN, overSpeed = false) }
                            }
                            is NavigationEvent.Frame -> {
                                val fresh = event.locationAtMillis?.let { clock() - it in 0..10000 } == true
                                val cameras = if (fresh) event.cameras.filter { it.distanceMeters in 0..10000 }
                                    .distinctBy { it.id }.sortedBy { it.distanceMeters }.take(3) else emptyList()
                                val car = drive.value
                                val carSpeed = car.takeIf { it.phase == DrivePhase.CONNECTED &&
                                    it.updatedAt?.let { time -> System.currentTimeMillis() - time in 0..10_000 } == true }
                                    ?.snapshot?.speedKph?.takeIf { it.isFinite() && it >= 0 }
                                val speed = if (fresh) carSpeed ?: event.speedKph?.takeIf { it >= 0 }?.toDouble() else null
                                val road = if (fresh) event.roadClass else RoadClass.UNKNOWN
                                val overSpeed = overSpeedPolicy.isOverSpeed(cameras.firstOrNull(), speed, road, settings.value)
                                _state.update { it.copy(phase = if (fresh) CameraPhase.GUIDING else CameraPhase.WAITING_GPS,
                                    cameras = cameras, locationAtMillis = event.locationAtMillis,
                                    currentSpeedKph = speed, roadClass = road, overSpeed = overSpeed) }
                                speaker.setOverSpeedAlert(overSpeed && !_state.value.muted && _state.value.voiceError == null)
                                if (!fresh) speaker.silence()
                                if (fresh && !_state.value.muted && _state.value.voiceError == null) {
                                    val now = clock()
                                    val presence = cameras.firstNotNullOfOrNull { policy.next(it, road, now, settings.value) }
                                    val speedWarning = overSpeedPolicy.next(cameras.firstOrNull(), speed, road, now, settings.value)
                                    val presenceWithWarning = presence?.takeIf {
                                        speedWarning == null || it.cameraId == cameras.firstOrNull()?.id
                                    }
                                    val text = listOfNotNull(presenceWithWarning?.text, speedWarning?.text).joinToString(" ")
                                    if (text.isNotBlank() && speaker.speak(text)) {
                                        presenceWithWarning?.let(policy::commit)
                                        speedWarning?.let(overSpeedPolicy::commit)
                                    }
                                }
                            }
                        }
                    }
                }
                launch {
                    while (isActive) {
                        delay(1000)
                        if (pendingTrip && acceptingFrames && !_state.value.muted && _state.value.voiceError == null && speaker.speak("주행시작")) {
                            pendingTrip = false; tripAnnounced = true
                        }
                        if (_state.value.locationAtMillis?.let { clock() - it > 10000 } == true) {
                            speaker.setOverSpeedAlert(false); speaker.silence()
                            _state.update { it.copy(phase = CameraPhase.WAITING_GPS, cameras = emptyList(),
                                locationAtMillis = null, currentSpeedKph = null, roadClass = RoadClass.UNKNOWN,
                                overSpeed = false, message = "현재 위치를 다시 확인하고 있습니다") }
                        }
                    }
                }
                drive.map { current ->
                    if (tripVin != current.vin || current.snapshot?.gear == dev.teslanavi.protocol.Gear.PARK) {
                        tripVin = current.vin; tripAnnounced = false; pendingTrip = false
                    }
                    val available = current.phase == DrivePhase.CONNECTED && current.snapshot != null
                    val moving = movement.update(current.vin, available, current.snapshot)
                    GuidanceTarget(current.vin, available, moving, if (available) current.snapshot?.destination else null)
                }.distinctUntilChanged().collectLatest { (_, available, moving, destination) ->
                    invalidate()
                    engine.pause()
                    if (!available || destination == null) {
                        _state.update { it.copy(phase = CameraPhase.WAITING_VEHICLE, mode = null, message = "차량 재연결을 기다리고 있습니다") }
                    } else if (!moving) {
                        _state.update { it.copy(phase = CameraPhase.WAITING_DRIVE, mode = null,
                            message = "주행 시작 대기 · 차량 속도 3 km/h 이상에서 안내합니다") }
                    } else {
                        if (!initialized) {
                            _state.update { it.copy(phase = CameraPhase.INITIALIZING, message = "카카오내비를 준비하고 있습니다") }
                            engine.initialize(nativeKey)
                            val voiceError = speaker.initialize()
                            _state.update { it.copy(voiceError = voiceError) }
                            initialized = true
                        }
                        pendingTrip = !tripAnnounced
                        acceptingFrames = true
                        _state.update { it.copy(phase = CameraPhase.WAITING_GPS, message = "현재 위치를 확인하고 있습니다") }
                        engine.guide(destination)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            _state.update { it.copy(phase = CameraPhase.ERROR, error = "카카오 응답 시간이 초과되었습니다. 네트워크를 확인한 뒤 다시 연결해 주세요.",
                message = "카카오 연결을 확인해 주세요") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(phase = CameraPhase.ERROR, error = error.message?.take(240) ?: "카카오 안내를 시작하지 못했습니다",
                message = "카카오 연결을 확인해 주세요") }
        } catch (_: LinkageError) {
            _state.update { it.copy(phase = CameraPhase.ERROR, error = "이 기기에서 카카오 SDK를 불러오지 못했습니다.", message = "기기 호환성을 확인해 주세요") }
        } finally {
            invalidate(); engine.pause(); speaker.close()
            if (_state.value.phase != CameraPhase.ERROR) configured(true)
        }
    }

    private data class GuidanceTarget(val vin: String?, val available: Boolean, val moving: Boolean, val destination: NavigationDestination?)
}
