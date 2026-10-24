package dev.teslanavi.logbook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.protocol.Gear
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val vinA = "5YJ3E1EA1MF111111"
    private val vinB = "5YJYGDEE1NF222222"
    private fun trip(id: String, start: Long, km: Double) = DriveTrip(id, start, start + 600_000, 1_000.0, 1_000.0 + km, km, 80.0, 70, 68)

    @Test fun parkedTripIsSavedPerVehicleAndSurvivesReload() {
        val store = TripStore(context)
        store.sample(vinA, 0, Gear.DRIVE, 40.0, 1_000.0, 80)
        store.sample(vinA, 300_000, Gear.DRIVE, 50.0, 1_006.0, 79)
        val saved = store.sample(vinA, 600_000, Gear.PARK, 0.0, 1_012.0, 77)!!
        assertNull(store.sample(vinB, 0, Gear.PARK, 0.0, 5_000.0, 50))
        val reloaded = TripStore(context).trips.value
        assertEquals(listOf(saved), reloaded[vinA])
        assertNull(reloaded[vinB])
    }

    @Test fun deleteRemovesOnlyThatTripAndCorruptStorageIsIgnored() {
        context.getSharedPreferences("drive-trips", Context.MODE_PRIVATE).edit().putString("trips", "{broken").commit()
        val store = TripStore(context)
        assertTrue(store.trips.value.isEmpty())
        store.add(vinA, trip("a", 0, 5.0))
        store.add(vinA, trip("b", 1_000_000, 7.0))
        store.delete(vinA, "a")
        assertEquals(listOf("b"), TripStore(context).trips.value[vinA]!!.map { it.id })
    }

    @Test fun batteryReadJustAfterParkingBecomesTheEndValue() {
        val store = TripStore(context)
        store.add(vinA, trip("a", 0, 5.0))
        store.lateBattery(vinA, 66, 600_000 + 60_000)
        assertEquals(66, TripStore(context).trips.value[vinA]!!.single().endBattery)
        store.lateBattery(vinA, 60, 600_000 + TripStore.LATE_BATTERY_MS + 1)
        assertEquals("a later read belongs to the parked car, not the trip", 66, store.trips.value[vinA]!!.single().endBattery)
    }
}
