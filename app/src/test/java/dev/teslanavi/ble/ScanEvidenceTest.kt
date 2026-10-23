package dev.teslanavi.ble

import org.junit.Assert.*
import org.junit.Test

class ScanEvidenceTest {
    private val target = "S0123456789abcdefC"

    @Test fun onlyExactConnectableVehicleIsAccepted() {
        val evidence = ScanEvidence(target)
        assertFalse(evidence.observe(null, true))
        assertFalse(evidence.observe("S1123456789abcdefC", true))
        assertFalse(evidence.observe(target.lowercase(), true))
        assertFalse(evidence.observe(target, false))
        assertTrue(evidence.observe(target, true))
    }

    @Test fun distinguishesNoSignalFromUnrelatedBluetoothAndOtherTesla() {
        val evidence = ScanEvidence(target)
        assertTrue(evidence.timeoutMessage().contains("수신하지 못했습니다"))
        evidence.observe("Headphones", true)
        assertTrue(evidence.timeoutMessage().contains("주변 Bluetooth 신호는 수신"))
        evidence.observe("S1123456789abcdefC", true)
        assertTrue(evidence.timeoutMessage().contains("VIN과 일치하지 않습니다"))
        assertFalse(evidence.timeoutMessage().contains("S1123456789abcdefC"))
    }

    @Test fun unavailableTargetTakesPriorityWithoutAssumingKeySlotsAreFull() {
        val evidence = ScanEvidence(target)
        evidence.observe(target, false)
        evidence.observe("S1123456789abcdefC", true)
        assertTrue(evidence.timeoutMessage().contains("연결 가능한 상태가 아닙니다"))
        assertFalse(evidence.timeoutMessage().contains("가득"))
        assertTrue(evidence.observe(target, true))
    }
}
