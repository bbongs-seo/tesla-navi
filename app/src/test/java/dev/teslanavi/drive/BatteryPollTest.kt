package dev.teslanavi.drive

import dev.teslanavi.protocol.BatterySnapshot
import dev.teslanavi.protocol.VehicleFault
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Test

class BatteryPollTest {
    private val battery = BatterySnapshot(78, 352.4)

    @Test fun readsRightAfterConnectingThenEveryFiveMinutes() {
        val poll = BatteryPoll()
        assertTrue(poll.due(0))
        assertEquals("충전 상태 수신 · 배터리 78% · 주행 가능 352 km", poll.received(battery, 1_000))
        assertFalse(poll.due(300_999))
        assertTrue(poll.due(301_000))
        assertNull("same outcome is not logged again", poll.received(battery.copy(levelPercent = 77), 301_000))
    }

    @Test fun repeatedMissesBackOffAndOnlyOutcomeChangesAreLogged() {
        val poll = BatteryPoll()
        assertEquals("충전 상태 없음 · 차량 응답에 ChargeState가 없습니다", poll.received(null, 0))
        assertTrue(poll.due(300_000))
        assertNull(poll.received(null, 300_000))
        assertNull(poll.received(null, 600_000))
        assertFalse("third miss waits 10 minutes", poll.due(600_000 + 599_999))
        assertTrue(poll.due(600_000 + 600_000))
        assertEquals("충전 상태 수신 · 배터리 %·주행 가능 거리 필드 없음", poll.received(BatterySnapshot(null, null), 1_200_000))
        assertEquals("충전 상태 수신 · 배터리 % 없음 · 주행 가능 161 km", poll.received(BatterySnapshot(null, 160.9344), 1_800_000))
        assertFalse(poll.due(2_099_999))
        assertTrue("success resets the back-off", poll.due(2_100_000))
    }

    @Test fun failureReasonIsRecordedAndChangesAreLoggedAgain() {
        val poll = BatteryPoll()
        val timeout = runBlocking { runCatching { withTimeout(1) { awaitCancellation() } }.exceptionOrNull() }
        assertTrue(timeout is TimeoutCancellationException)
        assertEquals("충전 상태 조회 실패 · 응답 시간 초과", poll.failed(timeout!!, 0))
        assertNull(poll.failed(timeout, 300_000))
        assertEquals("충전 상태 조회 실패 · 차량 조회 실패: 권한 없음", poll.failed(IllegalStateException("차량 조회 실패: 권한 없음"), 600_000))
        assertTrue(poll.failed(VehicleFault(7), 1_200_000)!!.contains("데이터 조회 권한"))
        assertEquals("충전 상태 수신 · 배터리 78% · 주행 가능 352 km", poll.received(battery, 1_800_000))
    }
}
