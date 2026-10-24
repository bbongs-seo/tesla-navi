package dev.teslanavi.drive

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.TeslaNaviApp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class)
class AutoConnectServiceTest {
    private val app get() = ApplicationProvider.getApplicationContext<TeslaNaviApp>()

    @Test fun automaticWaitingRunsAsVisibleForegroundServiceAndCanBeDisabled() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        app.autoConnect.setEnabled(true)
        val controller = Robolectric.buildService(AutoConnectService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        assertEquals(dev.teslanavi.R.drawable.ic_notification, notification.smallIcon.resId)
        assertTrue(AutoConnectService.running)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("자동 연결 끄기", notification.actions.single().title.toString())
        AutoConnectService.disable(app)
        assertFalse(app.autoConnect.enabled.value)
        assertFalse(AutoConnectSettings(app).enabled.value)
        controller.destroy()
    }

    @Test fun automaticStartUsesDetectedVerifiedVehicleEvenWhenAnotherVehicleWasSelected() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val a = "5YJ3E1EA1MF123456"
        val b = "5YJYGDEE1NF123456"
        app.vehicles.add("Model 3", a); app.vehicles.add("Model Y", b); app.vehicles.select(a)
        app.autoConnect.setEnabled(true)
        app.autoConnect.verified(b)
        val controller = Robolectric.buildService(DriveService::class.java).create()
        controller.get().onStartCommand(Intent(app, DriveService::class.java).putExtra("vin", b).putExtra("automatic", true), 0, 1)
        assertEquals(b, app.vehicles.selected.value)
        controller.destroy()
    }

    @Test fun queuedAutomaticStartCannotUseUnverifiedVehicle() {
        val a = "5YJ3E1EA1MF123456"
        val b = "5YJYGDEE1NF123456"
        app.vehicles.add("Model 3", a); app.vehicles.add("Model Y", b); app.vehicles.select(a)
        app.autoConnect.setEnabled(true)
        app.autoConnect.verified(a)
        val controller = Robolectric.buildService(DriveService::class.java).create()
        controller.get().onStartCommand(Intent(app, DriveService::class.java).putExtra("vin", b).putExtra("automatic", true), 0, 1)
        assertEquals(a, app.vehicles.selected.value)
        assertFalse(app.drive.state.value.active)
        controller.destroy()
    }

    @Test fun missingPermissionsNeverStartForegroundOrVehicleConnection() {
        app.autoConnect.setEnabled(true)
        val controller = Robolectric.buildService(AutoConnectService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertNull(shadowOf(service).lastForegroundNotification)
        assertFalse(app.drive.state.value.active)
        controller.destroy()
    }
}
