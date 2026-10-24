package dev.teslanavi.drive

import dev.teslanavi.protocol.DriveSnapshot
import dev.teslanavi.protocol.Gear

/** Latch movement through traffic stops; P or a lost vehicle session ends the trip. */
class TripMotionGate {
    private var vin: String? = null
    private var started = false

    fun update(vehicle: String?, connected: Boolean, snapshot: DriveSnapshot?): Boolean {
        if (vin != vehicle || !connected || snapshot == null) started = false
        vin = vehicle
        if (!connected || snapshot == null) return false
        if (snapshot.gear == Gear.PARK) started = false
        else if (snapshot.speedKph?.let { it.isFinite() && it >= START_KPH } == true) started = true
        return started
    }

    companion object {
        /** Shared with drive stats so a "trip" means the same thing for guidance and the trip log. */
        const val START_KPH = 3.0
    }
}
