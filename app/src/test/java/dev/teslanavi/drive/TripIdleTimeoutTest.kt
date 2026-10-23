package dev.teslanavi.drive

import dev.teslanavi.protocol.*
import org.junit.Assert.*
import org.junit.Test

class TripIdleTimeoutTest {
    private val park = DriveState(vin = "a", phase = DrivePhase.CONNECTED,
        snapshot = DriveSnapshot(NavigationDestination.None, 0.0, null, null, Gear.PARK))
    private val moving = park.copy(snapshot = park.snapshot!!.copy(speedKph = 10.0, gear = Gear.DRIVE))

    @Test fun idleConnectionClosesAfterFiveMinutes() {
        val timeout = TripIdleTimeout()
        assertFalse(timeout.expired(park, 0))
        assertFalse(timeout.expired(park, 299_999))
        assertTrue(timeout.expired(park, 300_000))
    }

    @Test fun trafficStopsDoNotCloseAnActiveTripButParkDoes() {
        val timeout = TripIdleTimeout()
        assertFalse(timeout.expired(moving, 0))
        val stopped = moving.copy(snapshot = moving.snapshot!!.copy(speedKph = 0.0))
        assertFalse(timeout.expired(stopped, 600_000))
        assertFalse(timeout.expired(park, 610_000))
        assertTrue(timeout.expired(park, 910_000))
    }

    @Test fun reconnectStartsANewIdleDeadline() {
        val timeout = TripIdleTimeout()
        assertFalse(timeout.expired(park, 0))
        assertFalse(timeout.expired(park.copy(phase = DrivePhase.RECONNECTING, snapshot = null), 290_000))
        assertFalse(timeout.expired(park, 300_000))
        assertFalse(timeout.expired(park, 590_000))
    }
}
