package dev.teslanavi.drive

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.TeslaNaviApp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class)
class BluetoothStartTest {
    private val app get() = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
    private val address = "00:11:22:33:44:55"
    private val other = "00:11:22:33:44:66"
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter

    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(adapter).setBondedDevices(setOf(adapter.getRemoteDevice(address), adapter.getRemoteDevice(other)))
        app.bluetoothStart.refresh()
        app.bluetoothStart.select(mapOf(address to "Tesla audio"))
    }

    private fun event(action: String, target: String = address) = Intent(action)
        .putExtra(BluetoothDevice.EXTRA_DEVICE, adapter.getRemoteDevice(target))

    @Test fun matchingDeviceStartsMonitorOnlyWhenEnabledAndDisconnectClosesGate() {
        app.autoConnect.setEnabled(true)
        BluetoothStartReceiver().onReceive(app, event(BluetoothDevice.ACTION_ACL_CONNECTED))
        assertTrue(app.bluetoothStart.state.value.allowed)
        assertEquals(AutoConnectService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        BluetoothStartReceiver().onReceive(app, event(BluetoothDevice.ACTION_ACL_DISCONNECTED))
        assertFalse(app.bluetoothStart.state.value.allowed)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun unrelatedDeviceAndDisabledAutomationNeverStartMonitor() {
        app.autoConnect.setEnabled(true)
        BluetoothStartReceiver().onReceive(app, event(BluetoothDevice.ACTION_ACL_CONNECTED, other))
        assertFalse(app.bluetoothStart.state.value.allowed)
        assertNull(shadowOf(app).nextStartedService)
        app.autoConnect.setEnabled(false)
        BluetoothStartReceiver().onReceive(app, event(BluetoothDevice.ACTION_ACL_CONNECTED))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun selectionSurvivesRestartButConnectedStateDoesNot() {
        app.bluetoothStart.connectionChanged(address, true)
        assertTrue(app.bluetoothStart.state.value.allowed)
        val restored = BluetoothStartSettings(app)
        assertEquals(mapOf(address to "Tesla audio"), restored.state.value.selectedDevices)
        assertFalse(restored.state.value.allowed)
        restored.select(emptyMap())
        assertTrue(restored.state.value.allowed)
        assertTrue(BluetoothStartSettings(app).state.value.selectedDevices.isEmpty())
    }

    @Test fun anySelectedDeviceCanStartAndOneDisconnectDoesNotCloseGateWhileAnotherRemains() {
        app.autoConnect.setEnabled(true)
        val selection = mapOf(address to "Model 3 audio", other to "Model Y audio")
        app.bluetoothStart.select(selection)
        assertEquals(selection, BluetoothStartSettings(app).state.value.selectedDevices)
        val receiver = BluetoothStartReceiver()
        receiver.onReceive(app, event(BluetoothDevice.ACTION_ACL_CONNECTED, other))
        assertTrue(app.bluetoothStart.state.value.allowed)
        assertEquals(AutoConnectService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        receiver.onReceive(app, event(BluetoothDevice.ACTION_ACL_CONNECTED, address))
        assertTrue(app.bluetoothStart.state.value.allowed)
        assertNotNull(shadowOf(app).nextStartedService)
        receiver.onReceive(app, event(BluetoothDevice.ACTION_ACL_DISCONNECTED, other))
        assertTrue(app.bluetoothStart.state.value.allowed)
        receiver.onReceive(app, event(BluetoothDevice.ACTION_ACL_DISCONNECTED, address))
        assertFalse(app.bluetoothStart.state.value.allowed)
    }

    @Test fun oldSingleSelectionMigratesAndClearingDoesNotBringItBack() {
        val prefs = app.getSharedPreferences("bluetooth-start", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().putString("address", address).putString("name", "Original car").commit()
        val restored = BluetoothStartSettings(app)
        assertEquals(mapOf(address to "Original car"), restored.state.value.selectedDevices)
        restored.select(emptyMap())
        assertTrue(BluetoothStartSettings(app).state.value.selectedDevices.isEmpty())
        assertFalse(prefs.contains("address"))
    }

    @Test fun radioOffAndMissingPermissionPreventStart() {
        app.autoConnect.setEnabled(true)
        app.bluetoothStart.connectionChanged(address, true)
        BluetoothStartReceiver().onReceive(app, Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
            .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF))
        assertFalse(app.bluetoothStart.state.value.allowed)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        BluetoothStartReceiver().onReceive(app, event(BluetoothDevice.ACTION_ACL_CONNECTED))
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(app.autoConnect.status.value.contains("권한"))
    }
}
