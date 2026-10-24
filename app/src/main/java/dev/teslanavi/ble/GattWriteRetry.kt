package dev.teslanavi.ble

import android.bluetooth.BluetoothStatusCodes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Retry only requests rejected as busy. Accepted writes and missing acknowledgements are never resent. */
internal suspend fun retryBusyGattWrite(write: () -> Int): Int {
    repeat(4) { attempt ->
        currentCoroutineContext().ensureActive()
        val result = write()
        if (result != BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY || attempt == 3) return result
        delay(150L * (attempt + 1))
    }
    error("unreachable")
}
