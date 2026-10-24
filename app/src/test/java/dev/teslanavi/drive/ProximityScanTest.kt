package dev.teslanavi.drive

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanSettings
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.TeslaNaviApp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothLeScanner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class)
class ProximityScanTest {
    private val app get() = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private fun scans() = Shadow.extract<ShadowBluetoothLeScanner>(adapter.bluetoothLeScanner).activeScans

    @Before fun enableBluetooth() { shadowOf(adapter).setEnabled(true) }

    @Test fun registersOneSystemOwnedLowPowerScanAndReplacesItWhenVehiclesChange() {
        ProximityScan.arm(app, setOf("S0123456789abcdefC", "Sfedcba9876543210C"))
        val scan = scans().single()
        assertNotNull(scan.pendingIntent())
        assertNull(scan.scanCallback())
        assertEquals(setOf("S0123456789abcdefC", "Sfedcba9876543210C"), scan.scanFilters().map { it.deviceName }.toSet())
        assertEquals(ScanSettings.SCAN_MODE_LOW_POWER, scan.scanSettings()!!.scanMode)
        // ProximityGate needs repeated sightings, so first-match-only delivery must not be selected.
        assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scan.scanSettings()!!.callbackType)

        ProximityScan.arm(app, setOf("Sfedcba9876543210C"))
        assertEquals(listOf("Sfedcba9876543210C"), scans().single().scanFilters().map { it.deviceName })

        ProximityScan.disarm(app)
        assertTrue(scans().isEmpty())
    }
}
