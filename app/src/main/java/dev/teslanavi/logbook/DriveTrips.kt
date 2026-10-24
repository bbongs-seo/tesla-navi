package dev.teslanavi.logbook

import dev.teslanavi.drive.TripMotionGate
import dev.teslanavi.protocol.Gear
import java.util.UUID

data class DriveTrip(val id: String, val startMs: Long, val endMs: Long, val startKm: Double?, val endKm: Double?,
    val distanceKm: Double, val maxSpeedKph: Double, val startBattery: Int?, val endBattery: Int?) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
    val averageKph: Double? get() = durationMs.takeIf { it > 0 }?.let { distanceKm / (it / 3_600_000.0) }
    val batteryUsed: Int? get() = if (startBattery != null && endBattery != null) startBattery - endBattery else null
}

/**
 * Turns drive samples into trips with the guidance rule (TripMotionGate): leaving P at 3 km/h or more starts a trip
 * and P ends it. Unlike guidance, a BLE reconnect keeps the trip open; only a sample gap longer than [gapMs] closes it,
 * so a short drop-out does not split one drive into two.
 */
class TripTracker(private val gapMs: Long = 10 * 60_000L, private val minKm: Double = 0.3,
    private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private class Open(val startMs: Long, val startKm: Double?, val startBattery: Int?, speed: Double) {
        var lastMs = startMs
        var lastKm = startKm
        var lastSpeed = speed
        var maxSpeed = speed
        var travelledKm = 0.0
        var battery = startBattery
    }
    private var open: Open? = null

    /** Returns a trip that just ended, by parking or by a long gap, when it is long enough to keep. */
    fun sample(timeMs: Long, gear: Gear, speedKph: Double?, odometerKm: Double?, battery: Int?): DriveTrip? {
        val speed = speedKph?.takeIf { it.isFinite() && it >= 0 }
        var ended: DriveTrip? = null
        open?.let { if (timeMs - it.lastMs > gapMs) { ended = close(it); open = null } }
        val trip = open
        if (trip == null) {
            if (gear != Gear.PARK && speed != null && speed >= TripMotionGate.START_KPH) open = Open(timeMs, odometerKm, battery, speed)
        } else {
            // Speed-based distance is only the fallback for a vehicle that does not report the odometer.
            trip.travelledKm += (trip.lastSpeed + (speed ?: trip.lastSpeed)) / 2 * (timeMs - trip.lastMs) / 3_600_000.0
            trip.lastMs = timeMs
            odometerKm?.let { trip.lastKm = it }
            speed?.let { trip.lastSpeed = it; trip.maxSpeed = maxOf(trip.maxSpeed, it) }
            battery?.let { trip.battery = it }
            if (gear == Gear.PARK) { ended = close(trip); open = null }
        }
        return ended
    }

    /** Closes the open trip when the vehicle session ends. */
    fun finish(): DriveTrip? = open?.let { close(it) }.also { open = null }

    private fun close(trip: Open): DriveTrip? {
        val start = trip.startKm
        val end = trip.lastKm
        val distance = (if (start != null && end != null) end - start else null)?.takeIf { it >= 0 } ?: trip.travelledKm
        return DriveTrip(newId(), trip.startMs, trip.lastMs, start, end, distance, trip.maxSpeed, trip.startBattery, trip.battery)
            .takeIf { distance >= minKm }
    }
}
