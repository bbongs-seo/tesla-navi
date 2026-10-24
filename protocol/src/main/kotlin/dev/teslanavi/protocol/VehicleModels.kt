package dev.teslanavi.protocol

import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.LocationState
import com.tesla.generated.carserver.vehicle.ShiftState
import java.security.MessageDigest
import java.util.Locale

object VehicleIdentity {
    fun normalizeVin(value: String): String = value.trim().uppercase(Locale.ROOT)
    fun isValidVin(value: String): Boolean = Regex("[A-HJ-NPR-Z0-9]{17}").matches(value)
    fun localName(vin: String): String {
        require(isValidVin(vin)) { "차대번호(VIN) 17자리를 확인해 주세요." }
        val hash = MessageDigest.getInstance("SHA-1").digest(vin.toByteArray(Charsets.US_ASCII))
        return "S" + hash.take(8).joinToString("") { "%02x".format(it.toInt() and 255) } + "C"
    }
}

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

/** The vehicle's own GPS from LocationState. A missing or 0,0 coordinate means no usable fix. */
data class VehicleLocation(val point: GeoPoint?, val headingDegrees: Int?, val gpsAsOf: Long?) {
    companion object {
        fun from(state: LocationState): VehicleLocation = VehicleLocation(
            if (state.hasLatitude() && state.hasLongitude())
                runCatching { GeoPoint(state.latitude.toDouble(), state.longitude.toDouble()) }.getOrNull()
                    ?.takeUnless { it.latitude == 0.0 && it.longitude == 0.0 }
            else null,
            if (state.hasHeading()) state.heading else null,
            if (state.hasGpsAsOf()) state.gpsAsOf else null,
        )
    }
}

sealed interface NavigationDestination {
    data object None : NavigationDestination
    data class Active(val name: String?, val point: GeoPoint?) : NavigationDestination
}

enum class Gear { UNKNOWN, PARK, REVERSE, NEUTRAL, DRIVE }

data class DriveSnapshot(
    val destination: NavigationDestination,
    val speedKph: Double?,
    val minutesToArrival: Double?,
    val distanceKm: Double?,
    val gear: Gear = Gear.UNKNOWN,
    val odometerKm: Double? = null,
    /** Null when the response carried no LocationState. */
    val location: VehicleLocation? = null,
) {
    companion object {
        fun from(state: DriveState, location: LocationState? = null): DriveSnapshot {
            val name = state.activeRouteDestination.trim().takeIf { it.isNotEmpty() }
            val point = if (state.hasActiveRouteCoordinates()) {
                val coords = state.activeRouteCoordinates
                GeoPoint(coords.latitude.toDouble(), coords.longitude.toDouble())
            } else null
            val destination = if (name == null && point == null) NavigationDestination.None
                else NavigationDestination.Active(name, point)
            val active = destination is NavigationDestination.Active
            val mph = when {
                state.hasSpeedFloat() -> state.speedFloat.toDouble()
                state.hasSpeed() -> Integer.toUnsignedLong(state.speed).toDouble()
                else -> null
            }
            return DriveSnapshot(
                destination,
                mph?.takeIf { it.isFinite() && it >= 0 }?.times(1.609344),
                if (active && state.hasActiveRouteMinutesToArrival())
                    state.activeRouteMinutesToArrival.toDouble().takeIf { it.isFinite() && it >= 0 } else null,
                if (active && state.hasActiveRouteMilesToArrival())
                    state.activeRouteMilesToArrival.toDouble().takeIf { it.isFinite() && it >= 0 }?.times(1.609344) else null,
                when (state.shiftState.typeCase) {
                    ShiftState.TypeCase.P -> Gear.PARK
                    ShiftState.TypeCase.R -> Gear.REVERSE
                    ShiftState.TypeCase.N -> Gear.NEUTRAL
                    ShiftState.TypeCase.D -> Gear.DRIVE
                    else -> Gear.UNKNOWN
                },
                if (state.hasOdometerInHundredthsOfAMile())
                    state.odometerInHundredthsOfAMile.takeIf { it >= 0 }?.let { it / 100.0 * 1.609344 } else null,
                location?.let(VehicleLocation::from),
            )
        }
    }
}

/** Rated range (battery_range), the figure the car screen and Tesla app show; out-of-range values become null. */
data class BatterySnapshot(val levelPercent: Int?, val rangeKm: Double?) {
    val empty get() = levelPercent == null && rangeKm == null

    companion object {
        fun from(state: ChargeState) = BatterySnapshot(
            if (state.hasBatteryLevel()) state.batteryLevel.takeIf { it in 0..100 } else null,
            if (state.hasBatteryRange()) state.batteryRange.toDouble().takeIf { it.isFinite() && it in 0.0..1000.0 }?.times(1.609344) else null,
        )
    }
}

enum class ChargingStatus { UNKNOWN, DISCONNECTED, NO_POWER, STARTING, CHARGING, COMPLETE, STOPPED }

/**
 * One charge_state read shared by the battery card and the logbook. energyAddedKwh is the vehicle's last (or current)
 * session total; it resets when a new session starts.
 */
data class ChargeSnapshot(val status: ChargingStatus, val energyAddedKwh: Double?, val battery: BatterySnapshot = BatterySnapshot(null, null)) {
    val batteryLevel: Int? get() = battery.levelPercent

    companion object {
        fun from(state: ChargeState) = ChargeSnapshot(
            when (state.chargingState.typeCase) {
                ChargeState.ChargingState.TypeCase.DISCONNECTED -> ChargingStatus.DISCONNECTED
                ChargeState.ChargingState.TypeCase.NOPOWER -> ChargingStatus.NO_POWER
                ChargeState.ChargingState.TypeCase.STARTING -> ChargingStatus.STARTING
                ChargeState.ChargingState.TypeCase.CHARGING -> ChargingStatus.CHARGING
                ChargeState.ChargingState.TypeCase.COMPLETE -> ChargingStatus.COMPLETE
                ChargeState.ChargingState.TypeCase.STOPPED -> ChargingStatus.STOPPED
                else -> ChargingStatus.UNKNOWN
            },
            if (state.hasChargeEnergyAdded()) state.chargeEnergyAdded.toDouble().takeIf { it.isFinite() && it >= 0 } else null,
            BatterySnapshot.from(state),
        )
    }
}
