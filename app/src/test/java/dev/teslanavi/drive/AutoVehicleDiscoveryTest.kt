package dev.teslanavi.drive

import dev.teslanavi.protocol.VehicleIdentity
import org.junit.Assert.*
import org.junit.Test

class AutoVehicleDiscoveryTest {
    private val a = "5YJ3E1EA1MF123456"
    private val b = "5YJYGDEE1NF123456"
    private fun name(vin: String) = VehicleIdentity.localName(vin)
    @Test fun watchesAllRegisteredVerifiedVehiclesAndRejectsOthers() {
        val discovery = AutoVehicleDiscovery()
        discovery.update(setOf(a, b), setOf(a, b, "5YJYGDEE1NF222222"))
        assertEquals(setOf(name(a), name(b)), discovery.scanNames)
        assertNull(discovery.observe(name("5YJYGDEE1NF222222"), -50, true, 0, false))
        assertEquals(b, discovery.observe(name(b), -50, true, 1, false))
    }
    @Test fun firstCandidateReservesConnectionAndBusyDoesNotConsumeOtherCarsRetry() {
        val discovery = AutoVehicleDiscovery()
        discovery.update(setOf(a, b), setOf(a, b))
        assertEquals(b, discovery.observe(name(b), -50, true, 0, false))
        assertNull(discovery.observe(name(a), -50, true, 1, true))
        // The previous connection ended; A can be used immediately without selecting it.
        assertEquals(a, discovery.observe(name(a), -50, true, 2, false))
        assertNull(discovery.observe(name(a), -50, true, 3, false))
    }
    @Test fun parkingDepartureAndRetryAreIndependentForEachVehicle() {
        val discovery = AutoVehicleDiscovery()
        discovery.update(setOf(a, b), setOf(a, b))
        discovery.awaitDeparture(a, 0)
        assertNull(discovery.observe(name(a), -50, true, 30_000, false))
        assertEquals(b, discovery.observe(name(b), -50, true, 30_001, false))
        discovery.update(setOf(a, b), setOf(a, b))
        assertNull(discovery.observe(name(a), -50, true, 50_000, false))
        assertEquals(a, discovery.observe(name(a), -50, true, 110_001, false))
    }
    @Test fun revokedOrRemovedCarsAreNotAcceptedFromQueuedScanResults() {
        val discovery = AutoVehicleDiscovery()
        discovery.update(setOf(a, b), setOf(a, b))
        discovery.update(setOf(a, b), setOf(b))
        assertNull(discovery.observe(name(a), -50, true, 0, false))
        discovery.update(setOf(a), setOf(a, b))
        assertNull(discovery.observe(name(b), -50, true, 1, false))
        assertEquals(a, discovery.observe(name(a), -50, true, 2, false))
        discovery.reset()
        assertTrue(discovery.scanNames.isEmpty())
    }
    @Test fun weakOrUnavailableFirstCarDoesNotBlockAnotherReadyCar() {
        val discovery = AutoVehicleDiscovery()
        discovery.update(setOf(a, b), setOf(a, b))
        assertNull(discovery.observe(name(a), -90, true, 0, false))
        assertNull(discovery.observe(name(a), -50, false, 1, false))
        assertEquals(b, discovery.observe(name(b), -50, true, 2, false))
    }
}
