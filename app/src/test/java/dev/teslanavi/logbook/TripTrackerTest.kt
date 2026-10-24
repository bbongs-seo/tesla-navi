package dev.teslanavi.logbook

import dev.teslanavi.protocol.Gear
import org.junit.Assert.*
import org.junit.Test

class TripTrackerTest {
    private var ids = 0
    private fun tracker() = TripTracker(newId = { "t${++ids}" })

    @Test fun leavingParkAtThreeKphStartsTripAndParkEndsItWithOdometerDistance() {
        val tracker = tracker()
        assertNull(tracker.sample(0, Gear.PARK, 0.0, 10_000.0, 80))
        assertNull("below 3 km/h is not a trip yet", tracker.sample(5_000, Gear.DRIVE, 2.9, 10_000.0, 80))
        assertNull(tracker.sample(10_000, Gear.DRIVE, 12.0, 10_000.0, 80))
        assertNull(tracker.sample(15_000, Gear.DRIVE, 88.0, 10_005.0, 79))
        (1..6).forEach { assertNull(tracker.sample(15_000 + it * 300_000L, Gear.DRIVE, 60.0, 10_005.0 + it * 4, 78)) }
        val trip = tracker.sample(2_110_000, Gear.PARK, 0.0, 10_032.4, 74)!!
        assertEquals(DriveTrip("t1", 10_000, 2_110_000, 10_000.0, 10_032.4, 32.4, 88.0, 80, 74), trip.copy(distanceKm = Math.round(trip.distanceKm * 10) / 10.0))
        assertEquals(35, trip.durationMs / 60_000)
        assertEquals(6, trip.batteryUsed)
        assertEquals(55.5, trip.averageKph!!, 0.1)
    }

    @Test fun trafficStopsAndUnknownGearKeepOneTripOpen() {
        val tracker = tracker()
        assertNull(tracker.sample(0, Gear.DRIVE, 30.0, 100.0, null))
        assertNull(tracker.sample(5_000, Gear.DRIVE, 0.0, 100.1, null))
        assertNull(tracker.sample(10_000, Gear.UNKNOWN, null, null, null))
        assertNull(tracker.sample(15_000, Gear.DRIVE, 40.0, 100.4, null))
        val trip = tracker.sample(20_000, Gear.PARK, 0.0, 100.6, null)!!
        assertEquals(0.6, trip.distanceKm, 1e-9)
        assertNull(trip.batteryUsed)
    }

    @Test fun parkingShuffleBelowMinimumDistanceIsDropped() {
        val tracker = tracker()
        tracker.sample(0, Gear.REVERSE, 5.0, 500.0, 60)
        assertNull(tracker.sample(20_000, Gear.PARK, 0.0, 500.1, 60))
    }

    @Test fun longSampleGapClosesTripAndMovingSampleStartsNextOne() {
        val tracker = tracker()
        tracker.sample(0, Gear.DRIVE, 50.0, 1_000.0, 70)
        tracker.sample(60_000, Gear.DRIVE, 50.0, 1_001.0, 70)
        val first = tracker.sample(60_000 + 10 * 60_000 + 1, Gear.DRIVE, 40.0, 1_020.0, 65)!!
        assertEquals(60_000L, first.endMs)
        assertEquals(1.0, first.distanceKm, 1e-9)
        val second = tracker.sample(60_000 + 15 * 60_000L, Gear.PARK, 0.0, 1_025.0, 64)!!
        assertEquals(5.0, second.distanceKm, 1e-9)
    }

    @Test fun speedIsIntegratedWhenVehicleDoesNotReportOdometer() {
        val tracker = tracker()
        (0..10).forEach { tracker.sample(it * 5_000L, Gear.DRIVE, 72.0, null, null) }
        val trip = tracker.sample(55_000, Gear.PARK, 72.0, null, null)!!
        assertEquals(72.0 * 55 / 3600, trip.distanceKm, 1e-9)
        assertNull(trip.startKm)
    }

    @Test fun finishClosesOpenTripOnceAndIgnoresNoTrip() {
        val tracker = tracker()
        assertNull(tracker.finish())
        tracker.sample(0, Gear.DRIVE, 30.0, 10.0, null)
        tracker.sample(5_000, Gear.DRIVE, 30.0, 12.0, null)
        assertEquals(2.0, tracker.finish()!!.distanceKm, 1e-9)
        assertNull(tracker.finish())
    }
}
