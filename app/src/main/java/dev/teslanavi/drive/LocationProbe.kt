package dev.teslanavi.drive

import dev.teslanavi.protocol.GeoPoint
import dev.teslanavi.protocol.VehicleLocation
import kotlin.math.*

/**
 * Step 0 of TRIP_LOG.md: whether the vehicle answers LocationState over BLE and how often its fix
 * changes. Messages go to the connection history, so they never contain coordinates.
 * One instance per vehicle connection.
 */
internal class LocationProbe(private val summaryIntervalMs: Long = 300_000) {
    private var reported: String? = null
    private var windowStart: Long? = null
    private var reads = 0
    private var fixes = 0
    private var fixUpdates = 0
    private var meters = 0.0
    private var lastFix: Long? = null
    private var lastPoint: GeoPoint? = null

    fun observe(location: VehicleLocation?, rejection: String?, now: Long): String? {
        val availability = when {
            rejection != null -> "차량이 위치 조회를 거부해 주행 상태만 조회합니다: $rejection"
            location == null -> "차량 응답에 위치 상태가 없습니다"
            location.point == null -> "차량 위치 상태는 있지만 사용할 좌표가 없습니다"
            else -> "차량 위치 제공 확인 · 방향 ${if (location.headingDegrees != null) "있음" else "없음"}" +
                " · GPS 시각 ${if (location.gpsAsOf != null) "있음" else "없음"}"
        }
        val point = location?.point
        if (point != null) {
            fixes++
            if (location.gpsAsOf != null && location.gpsAsOf != lastFix) fixUpdates++
            lastFix = location.gpsAsOf
            lastPoint?.let { meters += distanceMeters(it, point) }
            lastPoint = point
        }
        reads++
        val start = windowStart ?: now.also { windowStart = it }
        if (availability != reported) {
            reported = availability
            return availability
        }
        if (now - start < summaryIntervalMs || fixes == 0) return null
        val summary = "위치 ${(now - start) / 60_000}분 요약: 조회 ${reads}회 · 좌표 ${fixes}회 · " +
            "GPS 시각 갱신 ${fixUpdates}회 · 이동 약 ${meters.roundToInt()} m"
        windowStart = now; reads = 0; fixes = 0; fixUpdates = 0; meters = 0.0
        return summary
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 2 * 6_371_000.0 * asin(sqrt(h))
    }
}
