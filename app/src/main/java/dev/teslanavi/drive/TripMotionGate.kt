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
        else if (snapshot.speedKph?.let { it.isFinite() && it >= 3.0 } == true) started = true
        return started
    }
}
