package dev.teslanavi.logbook

import dev.teslanavi.protocol.ChargeSnapshot
import dev.teslanavi.protocol.ChargingStatus
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/** Korean public slow-charging rate (under 30 kW, Ministry of Environment, revised 2026-08); user-editable. */
const val DEFAULT_WON_PER_KWH = 295

enum class EntrySource { AUTO, MANUAL }

/** [wonPerKwh] is this charge's own rate; null follows the default rate in the settings. */
data class ChargeEntry(val id: String, val timeMs: Long, val odometerKm: Double, val energyKwh: Double, val source: EntrySource,
    val wonPerKwh: Int? = null) {
    fun rate(defaultWonPerKwh: Int): Int = wonPerKwh ?: defaultWonPerKwh
}

/** Gas-logbook convention: a charge refills what was used since the previous charge. */
data class ChargeInterval(val entry: ChargeEntry, val distanceKm: Double?, val kmPerKwh: Double?, val wonPerKm: Double?)

data class LogbookSummary(val intervals: List<ChargeInterval>, val distanceKm: Double, val energyKwh: Double,
    val kmPerKwh: Double?, val wonPerKm: Double?) {
    /** Newest charge that produced an efficiency value. */
    val latest: ChargeInterval? get() = intervals.lastOrNull { it.kmPerKwh != null }
    val measuredCount: Int get() = intervals.count { it.kmPerKwh != null }
}

data class MonthGroup<T>(val month: YearMonth, val items: List<T>)

/** Keeps the given order; a backfilled item joins its month's existing group instead of opening a new one. */
fun <T> groupByMonth(items: List<T>, zone: ZoneId, timeMs: (T) -> Long): List<MonthGroup<T>> =
    items.groupBy { YearMonth.from(Instant.ofEpochMilli(timeMs(it)).atZone(zone)) }.map { (month, grouped) -> MonthGroup(month, grouped) }

object ChargeLogbook {
    fun sorted(entries: List<ChargeEntry>) = entries.sortedWith(compareBy({ it.odometerKm }, { it.timeMs }))

    /** Each charge is priced at its own rate when set, otherwise at [wonPerKwh]. */
    fun summarize(entries: List<ChargeEntry>, wonPerKwh: Int): LogbookSummary {
        val ordered = sorted(entries)
        val intervals = ordered.mapIndexed { index, entry ->
            val distance = ordered.getOrNull(index - 1)?.let { entry.odometerKm - it.odometerKm }
            val efficiency = distance?.takeIf { it > 0 && entry.energyKwh > 0 }?.let { it / entry.energyKwh }
            ChargeInterval(entry, distance, efficiency, efficiency?.let { entry.rate(wonPerKwh) / it })
        }
        val distance = if (ordered.size < 2) 0.0 else ordered.last().odometerKm - ordered.first().odometerKm
        val charged = ordered.drop(1)
        val energy = charged.sumOf { it.energyKwh }
        val efficiency = distance.takeIf { it > 0 && energy > 0 }?.let { it / energy }
        return LogbookSummary(intervals, distance, energy, efficiency, efficiency?.let { cost(charged, wonPerKwh) / distance })
    }

    fun cost(entries: List<ChargeEntry>, wonPerKwh: Int): Double = entries.sumOf { it.energyKwh * it.rate(wonPerKwh) }
}

/** Last unplugged charge reading seen for a vehicle; the baseline for spotting the next session. */
data class ChargeObservation(val energyAddedKwh: Double, val batteryLevel: Int?)

sealed interface ChargeDetection {
    /** Cable connected or status unknown: the session may still grow, so read again after unplugging. */
    data object PluggedIn : ChargeDetection
    data object MissingEnergy : ChargeDetection
    /** First reading for this vehicle; its session may be old, so it only becomes the baseline. */
    data object Baseline : ChargeDetection
    data object Unchanged : ChargeDetection
    data object MissingOdometer : ChargeDetection
    /** No driving since the furthest entry, e.g. the user already logged this charge by hand. */
    data object AlreadyLogged : ChargeDetection
    data class NewCharge(val odometerKm: Double, val energyKwh: Double) : ChargeDetection
}

data class ChargeDecision(val detection: ChargeDetection, val observation: ChargeObservation?)

object ChargeDetector {
    private const val ENERGY_STEP_KWH = 0.05
    private const val MIN_ENERGY_KWH = 0.1
    private const val BATTERY_RISE_PERCENT = 3
    private const val SAME_PLACE_KM = 1.0

    /**
     * The vehicle keeps reporting the last session's energy until the next one starts, so a session is new
     * only when that energy differs from the baseline, or the battery rose (identical-energy sessions).
     */
    fun decide(previous: ChargeObservation?, charge: ChargeSnapshot, odometerKm: Double?, latestEntryKm: Double?): ChargeDecision {
        if (charge.status != ChargingStatus.DISCONNECTED) return ChargeDecision(ChargeDetection.PluggedIn, previous)
        val energy = charge.energyAddedKwh ?: return ChargeDecision(ChargeDetection.MissingEnergy, previous)
        val current = ChargeObservation(energy, charge.batteryLevel)
        if (previous == null) return ChargeDecision(ChargeDetection.Baseline, current)
        val energyChanged = abs(energy - previous.energyAddedKwh) >= ENERGY_STEP_KWH
        val battery = charge.batteryLevel
        val batteryRose = battery != null && previous.batteryLevel != null && battery - previous.batteryLevel >= BATTERY_RISE_PERCENT
        if ((!energyChanged && !batteryRose) || energy < MIN_ENERGY_KWH) return ChargeDecision(ChargeDetection.Unchanged, current)
        if (odometerKm == null) return ChargeDecision(ChargeDetection.MissingOdometer, previous)
        if (latestEntryKm != null && odometerKm - latestEntryKm < SAME_PLACE_KM) return ChargeDecision(ChargeDetection.AlreadyLogged, current)
        return ChargeDecision(ChargeDetection.NewCharge(odometerKm, energy), current)
    }
}
