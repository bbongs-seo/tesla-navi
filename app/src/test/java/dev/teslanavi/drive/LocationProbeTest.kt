package dev.teslanavi.drive

import dev.teslanavi.protocol.GeoPoint
import dev.teslanavi.protocol.VehicleLocation
import org.junit.Assert.*
import org.junit.Test

class LocationProbeTest {
    private fun fix(lat: Double, gpsAsOf: Long) = VehicleLocation(GeoPoint(lat, 127.0), 90, gpsAsOf)

    @Test fun reportsAvailabilityOnceThenSummarizesWithoutCoordinates() {
        val probe = LocationProbe(summaryIntervalMs = 60_000)
        assertEquals("차량 위치 제공 확인 · 방향 있음 · GPS 시각 있음", probe.observe(fix(37.5000, 1), null, 0))
        assertNull(probe.observe(fix(37.5001, 2), null, 5_000))
        assertNull(probe.observe(fix(37.5001, 2), null, 10_000))
        val summary = probe.observe(fix(37.5010, 3), null, 60_000)!!
        assertTrue(summary, summary.startsWith("위치 1분 요약: 조회 4회 · 좌표 4회 · GPS 시각 갱신 3회 · 이동 약 111 m"))
        assertFalse(summary.contains("37.5") || summary.contains("127"))
        // The next window starts empty.
        assertNull(probe.observe(fix(37.5010, 3), null, 90_000))
    }

    @Test fun eachKindOfMissingLocationIsReportedWhenItChanges() {
        val probe = LocationProbe()
        assertEquals("차량 응답에 위치 상태가 없습니다", probe.observe(null, null, 0))
        assertNull(probe.observe(null, null, 5_000))
        assertEquals("차량 위치 상태는 있지만 사용할 좌표가 없습니다", probe.observe(VehicleLocation(null, null, null), null, 10_000))
        assertEquals("차량이 위치 조회를 거부해 주행 상태만 조회합니다: 차량 조회 실패: unauthorized",
            probe.observe(null, "차량 조회 실패: unauthorized", 15_000))
        // Without a fix there is nothing to summarize, however long the connection lasts.
        assertNull(probe.observe(null, "차량 조회 실패: unauthorized", 3_600_000))
    }
}
