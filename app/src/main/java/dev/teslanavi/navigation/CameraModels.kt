package dev.teslanavi.navigation

import dev.teslanavi.protocol.NavigationDestination
import kotlinx.coroutines.flow.Flow

enum class CameraKind(val label: String) {
    SPEED("과속 단속"), SIGNAL_SPEED("신호·과속 단속"), MOBILE("이동식 단속"),
    REAR_SPEED("후면 과속 단속"), REAR_SIGNAL_SPEED("후면 신호·과속 단속"),
    SECTION_START("구간단속 시작"), SECTION_END("구간단속 종료"),
    LANE_SPEED("차로·과속 단속"),
}

data class CameraReading(
    val id: String,
    val kind: CameraKind,
    val speedLimitKph: Int?,
    val distanceMeters: Int,
    val variableLimit: Boolean = false,
)

enum class NavigationMode { SAFETY, ROUTE }
enum class RoadClass(val cameraAlertMeters: Int) { HIGHWAY(500), GENERAL(300), UNKNOWN(500) }
sealed interface NavigationEvent {
    data class Mode(val mode: NavigationMode, val message: String) : NavigationEvent
    data class Frame(val cameras: List<CameraReading>, val speedKph: Int?, val locationAtMillis: Long?,
        val roadClass: RoadClass = RoadClass.UNKNOWN) : NavigationEvent
    data class RouteInvalidated(val message: String) : NavigationEvent
}

interface CameraNavigationEngine {
    val events: Flow<NavigationUpdate>
    val revision: Long
    suspend fun initialize(nativeKey: String)
    suspend fun guide(destination: NavigationDestination)
    fun pause()
}
data class NavigationUpdate(val revision: Long, val event: NavigationEvent)

interface CameraSpeaker {
    suspend fun initialize(): String?
    fun speak(text: String): Boolean
    fun setOverSpeedAlert(active: Boolean) {}
    fun setVolume(percent: Int) {}
    fun silence()
    fun close()
}

enum class CameraPhase { KEY_REQUIRED, IDLE, WAITING_VEHICLE, WAITING_DRIVE, INITIALIZING, WAITING_GPS, GUIDING, ERROR }
data class CameraState(
    val phase: CameraPhase = CameraPhase.KEY_REQUIRED,
    val message: String = "이 설치 파일에는 카카오 안내 키가 없습니다. 앱 업데이트가 필요합니다.",
    val mode: NavigationMode? = null,
    val cameras: List<CameraReading> = emptyList(),
    val muted: Boolean = false,
    val error: String? = null,
    val voiceError: String? = null,
    val locationAtMillis: Long? = null,
    val currentSpeedKph: Double? = null,
    val roadClass: RoadClass = RoadClass.UNKNOWN,
    val overSpeed: Boolean = false,
)

/** One presence announcement per camera; commit only after audio was accepted. */
class CameraAlertPolicy {
    data class Announcement(val cameraId: String, val text: String, val atMillis: Long)
    private val announced = linkedMapOf<String, Long>()

    fun next(camera: CameraReading, roadClass: RoadClass, nowMillis: Long, options: GuidanceOptions = GuidanceOptions()): Announcement? {
        if (camera.distanceMeters !in 0..options.distance(roadClass)) return null
        announced.entries.removeAll { nowMillis - it.value > 5 * 60 * 1000 }
        if (camera.id in announced) return null
        val distance = if (camera.distanceMeters < 50) "잠시 후" else "${((camera.distanceMeters + 50) / 100) * 100}미터 앞"
        val limit = camera.speedLimitKph?.let { " 제한속도 시속 ${it}킬로미터입니다." } ?: ""
        val variable = if (camera.variableLimit) " 가변 제한속도 표지판을 확인해 주세요." else ""
        return Announcement(camera.id, "$distance ${camera.kind.label} 카메라가 있습니다.$limit$variable", nowMillis)
    }

    fun commit(announcement: Announcement) {
        announced[announcement.cameraId] = announcement.atMillis
        while (announced.size > 128) announced.remove(announced.keys.first())
    }
    fun clear() = announced.clear()
}

/** The SDK supplies camera-point limits, not a speed limit for every road segment. */
class OverSpeedPolicy {
    data class Announcement(val text: String, val atMillis: Long)
    private var lastSpokenAt: Long? = null

    fun isOverSpeed(camera: CameraReading?, speedKph: Double?, roadClass: RoadClass, options: GuidanceOptions = GuidanceOptions()): Boolean {
        val limit = camera?.speedLimitKph?.takeIf { it > 0 } ?: return false
        return !camera.variableLimit && camera.distanceMeters in 0..options.distance(roadClass) &&
            speedKph?.let { it.isFinite() && it >= limit.toDouble() + 5.0 } == true
    }

    fun next(camera: CameraReading?, speedKph: Double?, roadClass: RoadClass, nowMillis: Long, options: GuidanceOptions = GuidanceOptions()): Announcement? {
        if (!isOverSpeed(camera, speedKph, roadClass, options)) { lastSpokenAt = null; return null }
        if (lastSpokenAt != null) return null
        return Announcement("제한속도 ${camera!!.speedLimitKph}킬로미터 초과입니다.", nowMillis)
    }

    fun commit(announcement: Announcement) { lastSpokenAt = announcement.atMillis }
    fun clear() { lastSpokenAt = null }
}
