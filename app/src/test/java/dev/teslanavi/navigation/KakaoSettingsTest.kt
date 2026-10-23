package dev.teslanavi.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KakaoSettingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val bundled = "1".repeat(32)

    @Test fun bundledKeyIsReadyOnFirstLaunch() {
        val settings = KakaoSettings(context, bundled)
        assertTrue(settings.hasKey.value)
        assertTrue(settings.nativeKey == bundled)
    }

    @Test fun missingBundledKeyDoesNotEnableGuidance() {
        val settings = KakaoSettings(context, "")
        assertFalse(settings.hasKey.value)
        assertNull(settings.nativeKey)
    }

    @Test fun upgradeUsesBundleAndPreservesUserIdDespiteLegacyOverrideAndDisable() {
        val preferences = context.getSharedPreferences("kakao-settings", Context.MODE_PRIVATE)
        preferences.edit().putString("native-key", "2".repeat(32))
            .putBoolean("disabled", true).putString("user-id", "existing-user").commit()
        repeat(2) {
            val settings = KakaoSettings(context, bundled)
            assertTrue(settings.hasKey.value)
            assertTrue(settings.nativeKey == bundled)
            assertEquals("existing-user", settings.userId)
        }
        assertFalse(preferences.contains("native-key"))
        assertFalse(preferences.contains("disabled"))
    }

    @Test fun userIdPersistsAcrossRestarts() {
        val settings = KakaoSettings(context, bundled)
        val userId = settings.userId
        val reloaded = KakaoSettings(context, bundled)
        assertEquals(userId, reloaded.userId)
    }
}
