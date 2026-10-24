package dev.teslanavi.drive

import dev.teslanavi.protocol.DriveSnapshot
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Repair the authenticated session once before asking the owner to replace the BLE connection. */
internal class DriveStateReader(
    private val read: suspend () -> DriveSnapshot,
    private val authenticate: suspend () -> Unit,
    private val status: (String) -> Unit,
) {
    suspend fun next(): DriveSnapshot {
        try { return read() }
        catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            status("주행 정보 응답 지연 · BLE 연결을 유지하며 차량 인증 갱신 중")
        }
        delay(1000)
        authenticate()
        status("차량 인증 갱신 완료 · 주행 정보 재조회 중")
        // A second timeout propagates to the owner: never loop indefinitely on a silent link.
        return read().also { status("주행 정보 수신 복구 · 차량 연결 정상") }
    }
}
