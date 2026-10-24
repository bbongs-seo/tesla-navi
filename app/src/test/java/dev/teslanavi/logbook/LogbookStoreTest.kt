package dev.teslanavi.logbook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.protocol.BatterySnapshot
import dev.teslanavi.protocol.ChargeSnapshot
import dev.teslanavi.protocol.ChargingStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogbookStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val vinA = "5YJ3E1EA1MF111111"
    private val vinB = "5YJYGDEE1NF222222"
    private fun unplugged(kwh: Double, battery: Int) = ChargeSnapshot(ChargingStatus.DISCONNECTED, kwh, BatterySnapshot(battery, null))

    @Test fun entriesAndBaselineSurviveReloadAndEachChargeIsRecordedOnce() {
        val store = LogbookStore(context) { 1_000L }
        store.add(vinA, 10_000.0, 40.0)
        assertEquals(ChargeDetection.Baseline, store.observe(vinA, unplugged(40.0, 80), 10_000.0))
        val reloaded = LogbookStore(context)
        assertEquals(listOf(10_000.0), reloaded.entries.value[vinA]!!.map { it.odometerKm })
        assertNull(reloaded.entries.value[vinB])
        assertEquals(ChargeDetection.Unchanged, reloaded.observe(vinA, unplugged(40.0, 45), 10_200.0))
        assertEquals(ChargeDetection.NewCharge(10_228.0, 30.0), reloaded.observe(vinA, unplugged(30.0, 80), 10_228.0))
        assertEquals(ChargeDetection.Unchanged, reloaded.observe(vinA, unplugged(30.0, 80), 10_228.0))
        val entries = LogbookStore(context).entries.value[vinA]!!
        assertEquals(listOf(EntrySource.MANUAL, EntrySource.AUTO), entries.map { it.source })
        assertEquals(30.0, entries.last().energyKwh, 0.0)
    }

    @Test fun firstReadingOfAnotherVehicleDoesNotUseThisVehiclesBaseline() {
        val store = LogbookStore(context)
        store.observe(vinA, unplugged(40.0, 80), 10_000.0)
        assertEquals(ChargeDetection.Baseline, store.observe(vinB, unplugged(20.0, 80), 5_000.0))
        assertNull(store.entries.value[vinB])
    }

    @Test fun invalidManualInputIsRejectedAndDeleteRemovesOnlyThatEntry() {
        val store = LogbookStore(context)
        assertThrows(IllegalArgumentException::class.java) { store.add(vinA, -1.0, 30.0) }
        assertThrows(IllegalArgumentException::class.java) { store.add(vinA, 10_000.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { store.add(vinA, 10_000.0, 250.0) }
        val first = store.add(vinA, 10_000.0, 40.0)
        val second = store.add(vinA, 10_300.0, 35.0)
        store.delete(vinA, first.id)
        assertEquals(listOf(second.id), LogbookStore(context).entries.value[vinA]!!.map { it.id })
    }

    @Test fun ownRateIsSavedPerChargeAndCanFollowTheDefaultAgain() {
        val store = LogbookStore(context)
        val first = store.add(vinA, 10_000.0, 40.0)
        val second = store.add(vinA, 10_300.0, 35.0)
        store.setRate(vinA, second.id, 347)
        assertEquals(listOf(null, 347), LogbookStore(context).entries.value[vinA]!!.map { it.wonPerKwh })
        assertThrows(IllegalArgumentException::class.java) { store.setRate(vinA, first.id, 0) }
        store.setRate(vinA, second.id, null)
        assertEquals(listOf(null, null), LogbookStore(context).entries.value[vinA]!!.map { it.wonPerKwh })
    }

    @Test fun logbookStartsOffAtSlowChargingRateAndClampsStoredRate() {
        val features = dev.teslanavi.data.FeatureSettings(context)
        assertFalse(features.logbook.value)
        features.setLogbook(true)
        assertTrue(dev.teslanavi.data.FeatureSettings(context).logbook.value)
        assertFalse(dev.teslanavi.data.FeatureSettings(context).settingsTab.value)
        val settings = LogbookSettings(context)
        assertEquals(295, settings.wonPerKwh.value)
        settings.rate(347)
        assertEquals(347, LogbookSettings(context).wonPerKwh.value)
        settings.rate(99_999)
        assertEquals(2000, LogbookSettings(context).wonPerKwh.value)
    }
}
