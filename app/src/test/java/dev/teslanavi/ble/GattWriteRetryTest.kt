package dev.teslanavi.ble

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class GattWriteRetryTest {
    @Test fun busyRetriesButAcceptedChunkIsNeverDuplicated() = runTest {
        var calls = 0
        assertEquals(0, retryBusyGattWrite { if (++calls < 3) 201 else 0 })
        assertEquals(3, calls)
    }

    @Test fun disconnectedServiceIsNotRetriedAndBusyHasBoundedAttempts() = runTest {
        var calls = 0
        assertEquals(9, retryBusyGattWrite { calls++; 9 })
        assertEquals(1, calls)
        calls = 0
        assertEquals(201, retryBusyGattWrite { calls++; 201 })
        assertEquals(4, calls)
    }

    @Test fun cancellationStopsRetryBeforeNextWrite() = runTest {
        var calls = 0
        assertNull(withTimeoutOrNull(100) { retryBusyGattWrite { calls++; 201 } })
        assertEquals(1, calls)
    }
}
