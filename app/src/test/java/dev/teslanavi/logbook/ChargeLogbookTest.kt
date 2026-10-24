package dev.teslanavi.logbook

import dev.teslanavi.protocol.BatterySnapshot
import dev.teslanavi.protocol.ChargeSnapshot
import dev.teslanavi.protocol.ChargingStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

class ChargeLogbookTest {
    private fun entry(km: Double, kwh: Double) = ChargeEntry("e$km", km.toLong(), km, kwh, EntrySource.MANUAL)
    private fun unplugged(kwh: Double?, battery: Int? = 80) = ChargeSnapshot(ChargingStatus.DISCONNECTED, kwh, BatterySnapshot(battery, null))

    @Test fun efficiencyIsDistanceSincePreviousChargeOverThisChargeAtSlowChargingRate() {
        val summary = ChargeLogbook.summarize(listOf(entry(10_228.0, 30.0), entry(10_000.0, 40.0)), DEFAULT_WON_PER_KWH)
        assertNull(summary.intervals.first().kmPerKwh)
        val latest = summary.latest!!
        assertEquals(228.0, latest.distanceKm!!, 1e-9)
        assertEquals(7.6, latest.kmPerKwh!!, 1e-9)
        assertEquals("38.8", String.format(Locale.US, "%.1f", latest.wonPerKm))
    }

    @Test fun overallAverageDividesTotalDistanceByEnergyChargedAfterFirstEntry() {
        val summary = ChargeLogbook.summarize(listOf(entry(1_000.0, 50.0), entry(1_300.0, 40.0), entry(1_500.0, 25.0)), 300)
        assertEquals(2, summary.measuredCount)
        assertEquals(8.0, summary.latest!!.kmPerKwh!!, 1e-9)
        assertEquals(500.0, summary.distanceKm, 1e-9)
        assertEquals(65.0, summary.energyKwh, 1e-9)
        assertEquals(500.0 / 65.0, summary.kmPerKwh!!, 1e-9)
        assertEquals(300 * 65.0 / 500.0, summary.wonPerKm!!, 1e-9)
    }

    @Test fun singleEntryOrNoDrivingProducesNoEfficiency() {
        val single = ChargeLogbook.summarize(listOf(entry(1_000.0, 50.0)), 295)
        assertNull(single.latest); assertNull(single.kmPerKwh); assertEquals(0.0, single.distanceKm, 0.0)
        val parked = ChargeLogbook.summarize(listOf(entry(1_000.0, 50.0), entry(1_000.0, 5.0)), 295)
        assertEquals(0.0, parked.intervals.last().distanceKm!!, 0.0)
        assertNull(parked.intervals.last().kmPerKwh)
        assertNull(parked.kmPerKwh)
    }

    @Test fun monthGroupsKeepNewestFirstOrderAndAbsorbBackfilledEntries() {
        val october = 1_791_000_000_000L // 2026-10-03 UTC
        val august = 1_788_000_000_000L // 2026-08-29 UTC
        val entries = listOf(
            ChargeEntry("a", october, 10_500.0, 30.0, EntrySource.AUTO),
            ChargeEntry("b", august, 10_300.0, 20.0, EntrySource.MANUAL),
            ChargeEntry("c", october + 1_000, 10_100.0, 10.0, EntrySource.MANUAL))
        val intervals = ChargeLogbook.summarize(entries, 295).intervals.asReversed()
        val groups = ChargeLogbook.byMonth(intervals, ZoneOffset.UTC)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 8)), groups.map { it.month })
        assertEquals(listOf("a", "c"), groups.first().intervals.map { it.entry.id })
        assertEquals(40.0, groups.first().energyKwh, 1e-9)
    }

    @Test fun firstReadingOnlyBecomesBaselineBecauseItsSessionMayBeOld() {
        val decision = ChargeDetector.decide(null, unplugged(30.0), 10_000.0, null)
        assertEquals(ChargeDetection.Baseline, decision.detection)
        assertEquals(ChargeObservation(30.0, 80), decision.observation)
    }

    @Test fun sameSessionSeenOnEveryConnectionIsNotRecordedAgain() {
        val baseline = ChargeObservation(30.0, 80)
        val decision = ChargeDetector.decide(baseline, unplugged(30.0, 60), 10_120.0, 10_000.0)
        assertEquals(ChargeDetection.Unchanged, decision.detection)
        assertEquals(ChargeObservation(30.0, 60), decision.observation)
    }

    @Test fun newSessionEnergyIsRecordedAtCurrentOdometer() {
        val decision = ChargeDetector.decide(ChargeObservation(30.0, 40), unplugged(32.4, 80), 10_228.0, 10_000.0)
        assertEquals(ChargeDetection.NewCharge(10_228.0, 32.4), decision.detection)
        assertEquals(ChargeObservation(32.4, 80), decision.observation)
    }

    @Test fun identicalEnergySessionIsDetectedByBatteryRise() {
        val decision = ChargeDetector.decide(ChargeObservation(30.0, 40), unplugged(30.0, 80), 10_228.0, 10_000.0)
        assertEquals(ChargeDetection.NewCharge(10_228.0, 30.0), decision.detection)
        assertEquals(ChargeDetection.Unchanged, ChargeDetector.decide(ChargeObservation(30.0, 79), unplugged(30.0, 80), 10_228.0, 10_000.0).detection)
    }

    @Test fun pluggedInOrUnknownStatusDefersWithoutMovingBaseline() {
        val baseline = ChargeObservation(30.0, 40)
        for (status in ChargingStatus.entries - ChargingStatus.DISCONNECTED) {
            val decision = ChargeDetector.decide(baseline, ChargeSnapshot(status, 12.0, BatterySnapshot(70, null)), 10_228.0, 10_000.0)
            assertEquals(ChargeDetection.PluggedIn, decision.detection)
            assertSame(baseline, decision.observation)
        }
        assertEquals(ChargeDetection.NewCharge(10_228.0, 25.0),
            ChargeDetector.decide(baseline, unplugged(25.0), 10_228.0, 10_000.0).detection)
    }

    @Test fun missingOdometerOrEnergyKeepsBaselineSoTheChargeIsRetried() {
        val baseline = ChargeObservation(30.0, 40)
        val noOdometer = ChargeDetector.decide(baseline, unplugged(25.0), null, 10_000.0)
        assertEquals(ChargeDetection.MissingOdometer, noOdometer.detection)
        assertSame(baseline, noOdometer.observation)
        val noEnergy = ChargeDetector.decide(baseline, unplugged(null), 10_228.0, 10_000.0)
        assertEquals(ChargeDetection.MissingEnergy, noEnergy.detection)
        assertSame(baseline, noEnergy.observation)
    }

    @Test fun chargeAlreadyLoggedByHandAtSameOdometerIsNotDuplicated() {
        val decision = ChargeDetector.decide(ChargeObservation(30.0, 40), unplugged(25.0), 10_228.4, 10_228.0)
        assertEquals(ChargeDetection.AlreadyLogged, decision.detection)
        assertEquals(ChargeObservation(25.0, 80), decision.observation)
    }

    @Test fun newSessionThatAddedNothingIsIgnored() {
        assertEquals(ChargeDetection.Unchanged, ChargeDetector.decide(ChargeObservation(30.0, 40), unplugged(0.0, 40), 10_228.0, 10_000.0).detection)
    }
}
