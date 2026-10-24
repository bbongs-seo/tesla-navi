package dev.teslanavi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.TeslaNaviApp
import dev.teslanavi.protocol.BatterySnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class)
class BatteryStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
    private val first = "5YJ3E1EA1MF111111"
    private val second = "5YJYGDEE1NF222222"

    @Test fun lastReadingPerVehicleSurvivesReloadAndEmptyReadingKeepsPrevious() {
        val store = BatteryStore(context)
        store.save(first, BatterySnapshot(78, 352.4), 1_000)
        store.save(second, BatterySnapshot(64, null), 2_000)
        store.save(first, BatterySnapshot(null, null), 3_000)
        val restored = BatteryStore(context).readings.value
        assertEquals(BatteryReading(78, 352.4, 1_000), restored[first])
        assertEquals(BatteryReading(64, null, 2_000), restored[second])
    }

    @Test fun damagedOrInvalidStoredValuesAreIgnored() {
        val prefs = context.getSharedPreferences("battery", Context.MODE_PRIVATE)
        prefs.edit().putString("readings", "{incomplete").commit()
        assertTrue(BatteryStore(context).readings.value.isEmpty())
        prefs.edit().putString("readings", """{"$first":{"at":5,"level":101,"rangeKm":-3},"NOT-A-VIN":{"at":5,"level":50},"$second":{"at":7,"level":40}}""").commit()
        assertEquals(mapOf(second to BatteryReading(40, null, 7)), BatteryStore(context).readings.value)
    }

    @Test fun summaryAndAgeShowOnlyAvailableValues() {
        assertEquals("배터리 78% · 주행 가능 352 km", BatteryReading(78, 352.4, 0).summary)
        assertEquals("배터리 64%", BatteryReading(64, null, 0).summary)
        assertEquals("주행 가능 353 km", BatteryReading(null, 352.5, 0).summary)
        assertEquals(listOf("방금", "1분 전", "59분 전", "1시간 전", "23시간 전", "1일 전"),
            listOf(0L, 1, 59, 60, 1439, 1440).map { batteryAge(it) })
    }
}
