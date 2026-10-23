package dev.teslanavi.drive

/** End an automatic connection after five minutes without a trip, including after P. */
class TripIdleTimeout {
    private val movement = TripMotionGate()
    private var idleSince: Long? = null
    private var vin: String? = null

    fun expired(state: DriveState, now: Long): Boolean {
        if (vin != state.vin) idleSince = null
        vin = state.vin
        val connected = state.phase == DrivePhase.CONNECTED && state.snapshot != null
        val moving = movement.update(state.vin, connected, state.snapshot)
        if (!connected || moving) { idleSince = null; return false }
        val since = idleSince ?: now.also { idleSince = it }
        return now - since >= 5 * 60_000
    }
}
