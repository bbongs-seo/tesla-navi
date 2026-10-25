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
class GuidanceSettingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun defaultsAndIndependentValuesSurviveReloadAndReset() {
        val settings = GuidanceSettings(context)
        assertEquals(GuidanceOptions(), settings.options.value)
        settings.volume(35); settings.highway(1500); settings.general(700)
        assertEquals(GuidanceOptions(35, 1500, 700), GuidanceSettings(context).options.value)
        settings.reset()
        assertEquals(GuidanceOptions(), GuidanceSettings(context).options.value)
    }
    @Test fun invalidStoredValuesAreClampedAndDistancesRounded() {
        context.getSharedPreferences("guidance-settings", Context.MODE_PRIVATE).edit()
            .putInt("volume", 900).putInt("highway", -300).putInt("general", 450).commit()
        val settings = GuidanceSettings(context)
        assertEquals(GuidanceOptions(100, 100, 500), settings.options.value)
        settings.highway(99999); settings.general(99999); settings.volume(-1)
        assertEquals(GuidanceOptions(0, 2000, 1000), GuidanceSettings(context).options.value)
    }
    @Test fun voiceChoiceSurvivesReloadAndResetButUnknownValuesFallBackToSystem() {
        val settings = GuidanceSettings(context)
        settings.voice(GuidanceVoice.SUPERTONIC); settings.volume(40)
        assertEquals(GuidanceVoice.SUPERTONIC, GuidanceSettings(context).options.value.voice)
        settings.reset()
        assertEquals(GuidanceOptions(voice = GuidanceVoice.SUPERTONIC), GuidanceSettings(context).options.value)
        context.getSharedPreferences("guidance-settings", Context.MODE_PRIVATE).edit().putString("voice", "REMOVED_ENGINE").commit()
        assertEquals(GuidanceVoice.SYSTEM, GuidanceSettings(context).options.value.voice)
    }
    @Test fun configuredRangesControlBothCameraPresenceAndOverspeed() {
        val options = GuidanceOptions(highwayMeters = 1200, generalMeters = 600)
        val camera = CameraReading("a", CameraKind.SPEED, 60, 1000)
        val presence = CameraAlertPolicy()
        val speed = OverSpeedPolicy()
        assertNotNull(presence.next(camera, RoadClass.HIGHWAY, 0, options))
        assertTrue(speed.isOverSpeed(camera, 65.0, RoadClass.HIGHWAY, options))
        assertNull(presence.next(camera, RoadClass.GENERAL, 0, options))
        assertFalse(speed.isOverSpeed(camera, 65.0, RoadClass.GENERAL, options))
        assertNotNull(presence.next(camera.copy(distanceMeters = 600), RoadClass.GENERAL, 0, options))
        assertFalse(speed.isOverSpeed(camera.copy(distanceMeters = 1201), 65.0, RoadClass.UNKNOWN, options))
    }

    @Test fun supertonicSpeakerSurvivesRestartResetAndSwitchingToSystem() {
        val settings = GuidanceSettings(context)
        settings.supertonicSpeaker(SupertonicSpeaker.F3)
        settings.voice(GuidanceVoice.SUPERTONIC)
        settings.reset()
        assertEquals(SupertonicSpeaker.F3, GuidanceSettings(context).options.value.supertonicSpeaker)
        settings.voice(GuidanceVoice.SYSTEM)
        assertEquals(SupertonicSpeaker.F3, GuidanceSettings(context).options.value.supertonicSpeaker)
        context.getSharedPreferences("guidance-settings", Context.MODE_PRIVATE).edit()
            .putString("supertonic-speaker", "UNKNOWN").commit()
        assertEquals(SupertonicSpeaker.F1, GuidanceSettings(context).options.value.supertonicSpeaker)
    }
}
