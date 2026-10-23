package dev.teslanavi.drive

import org.junit.Assert.*
import org.junit.Test

class ProximityGateTest {
    @Test fun weakAndNonConnectableAdvertisementsDoNotConnect() {
        val gate = ProximityGate()
        assertFalse(gate.observe(-90, true, 0))
        assertFalse(gate.observe(-60, false, 1))
        assertTrue(gate.observe(-60, true, 2))
    }

    @Test fun repeatedAdvertisementsCannotCreateConcurrentRetryStorm() {
        val gate = ProximityGate()
        assertTrue(gate.observe(-60, true, 0))
        assertFalse(gate.observe(-60, true, 1))
        assertFalse(gate.observe(-60, true, 59_999))
        assertTrue(gate.observe(-60, true, 60_000))
    }

    @Test fun parkedVehicleDoesNotReconnectUntilItLeavesRadioRange() {
        val gate = ProximityGate()
        gate.awaitDeparture(0)
        for (now in 30_000L..300_000L step 30_000) assertFalse(gate.observe(-60, true, now))
        assertTrue(gate.observe(-60, true, 360_001))
    }
}
