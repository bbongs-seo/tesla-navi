package dev.teslanavi.drive

import dev.teslanavi.protocol.BatterySnapshot
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.math.roundToInt

/**
 * Battery is optional and read on its own connection-scoped schedule: right after the first drive state, then every
 * 5 minutes. After three misses in a row the vehicle is asked every 10 minutes. A history line is returned only
 * when the outcome changes, so a long drive does not push real diagnostics out of the bounded history.
 */
class BatteryPoll {
    private var dueAt = Long.MIN_VALUE
    private var misses = 0
    private var outcome: String? = null

    fun due(now: Long) = now >= dueAt

    /** Pulls the next read forward (never later), e.g. while a charge cable is in so the logbook sees the unplug moment. */
    fun recheckBy(at: Long) { dueAt = minOf(dueAt, at) }

    fun received(battery: BatterySnapshot?, now: Long): String? = when {
        battery == null -> miss("충전 상태 없음 · 차량 응답에 ChargeState가 없습니다", now)
        battery.empty -> miss("충전 상태 수신 · 배터리 %·주행 가능 거리 필드 없음", now)
        else -> {
            misses = 0
            dueAt = now + INTERVAL
            val values = listOf(battery.levelPercent?.let { "배터리 $it%" } ?: "배터리 % 없음",
                battery.rangeKm?.let { "주행 가능 ${it.roundToInt()} km" } ?: "주행 가능 거리 없음")
            report("received", "충전 상태 수신 · ${values.joinToString(" · ")}")
        }
    }

    fun failed(error: Throwable, now: Long): String? = miss("충전 상태 조회 실패 · " + when (error) {
        is TimeoutCancellationException -> "응답 시간 초과"
        else -> error.message?.take(160) ?: error.javaClass.simpleName
    }, now)

    private fun miss(line: String, now: Long): String? {
        dueAt = now + if (++misses >= 3) BACKOFF else INTERVAL
        return report(line, line)
    }

    private fun report(kind: String, line: String): String? = line.takeIf { kind != outcome }.also { outcome = kind }

    companion object {
        const val INTERVAL = 300_000L
        const val BACKOFF = 600_000L
        const val PLUGGED_RECHECK = 60_000L
    }
}
