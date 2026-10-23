package dev.teslanavi.protocol

import com.tesla.generated.carserver.vehicle.DriveState
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
) {
    companion object {
        fun from(state: DriveState): DriveSnapshot {
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
            )
        }
    }
}
