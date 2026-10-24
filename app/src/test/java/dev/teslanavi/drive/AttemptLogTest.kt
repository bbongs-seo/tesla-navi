package dev.teslanavi.drive

import org.junit.Assert.*
import org.junit.Test

class AttemptLogTest {
    @Test fun repeatedAutomaticOutcomeIsWrittenOnceThenOnlyCounted() {
        val log = AttemptLog(summaryEvery = 3)
        assertTrue(log.begin(automatic = true, line = "자동 연결 준비").isEmpty())
        assertTrue(log.line("차량 연결 중").isEmpty())
        assertEquals(listOf("자동 연결 준비", "차량 연결 중", "잠듦"), log.failed("asleep", "잠듦"))
        repeat(2) {
            log.begin(automatic = true, line = "자동 연결 준비")
            log.line("차량 연결 중")
            assertTrue(log.failed("asleep", "잠듦").isEmpty())
        }
        log.begin(automatic = true, line = "자동 연결 준비")
        assertEquals(listOf("같은 자동 연결 결과 3회 반복 · 잠듦"), log.failed("asleep", "잠듦"))
    }

    @Test fun newOutcomeClosesTheRunAndWritesItsLinesInFull() {
        val log = AttemptLog()
        log.begin(automatic = true, line = "자동 연결 준비"); log.failed("asleep", "잠듦")
        log.begin(automatic = true, line = "자동 연결 준비"); log.failed("asleep", "잠듦")
        log.begin(automatic = true, line = "자동 연결 준비")
        log.line("차량 인증 중")
        assertEquals(listOf("이전 자동 연결 결과가 1회 더 반복됨", "자동 연결 준비", "차량 인증 중", "시간 초과"),
            log.failed("timeout", "시간 초과"))
    }

    @Test fun connectionWritesHeldLinesAndLaterLinesPassThrough() {
        val log = AttemptLog()
        log.begin(automatic = true, line = "자동 연결 준비"); log.failed("asleep", "잠듦")
        log.begin(automatic = true, line = "자동 연결 준비"); log.failed("asleep", "잠듦")
        log.begin(automatic = true, line = "자동 연결 준비")
        log.line("차량 인증 중")
        assertEquals(listOf("이전 자동 연결 결과가 1회 더 반복됨", "자동 연결 준비", "차량 인증 중"), log.connected())
        assertEquals(listOf("목적지 확인 중"), log.line("목적지 확인 중"))
        // A failure after a working connection is never folded into a run.
        assertEquals(listOf("응답 누락"), log.failed("asleep", "응답 누락"))
    }

    @Test fun manualAttemptsAreAlwaysWrittenAsTheyHappen() {
        val log = AttemptLog()
        log.begin(automatic = true, line = "자동 연결 준비"); log.failed("asleep", "잠듦")
        log.begin(automatic = true, line = "자동 연결 준비"); log.failed("asleep", "잠듦")
        assertEquals(listOf("이전 자동 연결 결과가 1회 더 반복됨", "수동 연결 준비"), log.begin(automatic = false, line = "수동 연결 준비"))
        assertEquals(listOf("차량 연결 중"), log.line("차량 연결 중"))
        assertEquals(listOf("잠듦"), log.failed("asleep", "잠듦"))
    }

    @Test fun cancelledAutomaticAttemptLeavesNothingBehind() {
        val log = AttemptLog()
        log.begin(automatic = true, line = "자동 연결 준비")
        log.line("차량 연결 중")
        log.cancelled()
        assertEquals(listOf("앱 다시 시작"), log.line("앱 다시 시작"))
    }
}
