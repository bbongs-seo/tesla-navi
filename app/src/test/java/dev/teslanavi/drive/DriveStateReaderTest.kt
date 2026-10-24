package dev.teslanavi.drive

import dev.teslanavi.protocol.DriveSnapshot
import dev.teslanavi.protocol.NavigationDestination
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriveStateReaderTest {
    private val snapshot = DriveSnapshot(NavigationDestination.None, 30.0, null, null)
    private suspend fun timeout(): Nothing = withTimeout(8_000) { awaitCancellation() }

    @Test fun missingReplyReauthenticatesAndClearsWarningEvenWithSameDestination() = runTest {
        var reads = 0
        var authentications = 0
        val messages = mutableListOf<String>()
        val reader = DriveStateReader(
            { if (++reads == 1) timeout() else snapshot }, { authentications++ }, messages::add)
        assertEquals(snapshot, reader.next())
        assertEquals(2, reads)
        assertEquals(1, authentications)
        assertTrue(messages.last().contains("수신 복구"))
        assertEquals(snapshot, reader.next())
        assertEquals(1, authentications)
    }

    @Test fun persistentMissingReplyLeavesRecoveryForBleReconnect() = runTest {
        var reads = 0
        var authentications = 0
        val reader = DriveStateReader({ reads++; timeout() }, { authentications++ }, {})
        try { reader.next(); fail("must time out") } catch (_: TimeoutCancellationException) { }
        assertEquals(2, reads)
        assertEquals(1, authentications)
        assertEquals(17_000L, testScheduler.currentTime)
    }

    @Test fun parentTimeoutDoesNotStartRecoveryOrChangeStatus() = runTest {
        var authentications = 0
        val messages = mutableListOf<String>()
        val reader = DriveStateReader({ timeout() }, { authentications++ }, messages::add)
        assertNull(withTimeoutOrNull(100) { reader.next() })
        assertEquals(0, authentications)
        assertTrue(messages.isEmpty())
    }

    @Test fun stoppingDuringReauthenticationDoesNotRequestOldVehicleAgain() = runTest {
        var reads = 0
        val started = CompletableDeferred<Unit>()
        val reader = DriveStateReader({ reads++; timeout() }, { started.complete(Unit); awaitCancellation() }, {})
        val task = launch { reader.next() }
        started.await()
        task.cancelAndJoin()
        assertEquals(1, reads)
    }
}
