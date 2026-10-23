package dev.teslanavi.drive

import android.Manifest
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.TeslaNaviApp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class)
class AutoConnectBootReceiverTest {
    private val app get() = ApplicationProvider.getApplicationContext<TeslaNaviApp>()

    private fun grantPermissions() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun disabledSettingDoesNotRestartAtBoot() {
        grantPermissions()
        AutoConnectBootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun enabledSettingRestartsAfterBootAndAppUpdate() {
        grantPermissions()
        app.autoConnect.setEnabled(true)
        app.autoConnect.verified("5YJYGDEE1NF123456")
        assertTrue(AutoConnectSettings(app).enabled.value)
        assertTrue("5YJYGDEE1NF123456" in AutoConnectSettings(app).verified.value)
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach { action ->
            AutoConnectBootReceiver().onReceive(app, Intent(action))
            assertEquals(AutoConnectService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        }
    }

    @Test fun missingBackgroundPermissionDoesNotAttemptLocationServiceAtBoot() {
        grantPermissions()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        app.autoConnect.setEnabled(true)
        AutoConnectBootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(app.autoConnect.status.value.contains("권한"))
    }
}
