package dev.teslanavi.drive

import dev.teslanavi.protocol.*
import org.junit.Assert.*
import org.junit.Test

class TripMotionGateTest {
    private val parked = DriveSnapshot(NavigationDestination.Active("목적지", null), 0.0, null, null, Gear.PARK)
    private fun moving(speed: Double? = 10.0) = parked.copy(speedKph = speed, gear = Gear.DRIVE)

    @Test fun destinationAndDriveGearAloneDoNotStartTrip() {
        val gate = TripMotionGate()
        assertFalse(gate.update("a", true, parked))
        listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 0.0, 2.99).forEach {
            assertFalse(gate.update("a", true, moving(it)))
        }
        assertTrue(gate.update("a", true, moving(3.0)))
    }

    @Test fun trafficStopsStayActiveUntilPark() {
        val gate = TripMotionGate()
        assertTrue(gate.update("a", true, moving()))
        assertTrue(gate.update("a", true, moving(0.0)))
        assertTrue(gate.update("a", true, moving(null)))
        assertFalse(gate.update("a", true, parked))
        assertFalse(gate.update("a", true, moving(0.0)))
        assertTrue(gate.update("a", true, moving()))
    }

    @Test fun disconnectAndVehicleSwitchRequireNewMovement() {
        val gate = TripMotionGate()
        assertTrue(gate.update("a", true, moving()))
        assertFalse(gate.update("a", false, null))
        assertFalse(gate.update("a", true, moving(null)))
        assertTrue(gate.update("a", true, moving()))
        assertFalse(gate.update("b", true, moving(0.0)))
    }
}
