package dev.teslanavi.navigation

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.TeslaNaviApp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class)
class GuidanceVolumeTest {
    @Test fun warningBoostsVolumeAndRestoresItWhenStopped() {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        val audio = context.getSystemService(AudioManager::class.java)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        val speaker = AndroidCameraSpeaker(context)
        speaker.setOverSpeedAlert(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501))
        assertEquals(org.robolectric.shadows.ShadowLog.getLogsForTag("TeslaNaviAudio").joinToString { it.msg + it.throwable?.stackTraceToString() }, 7.coerceAtMost(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        speaker.setOverSpeedAlert(false)
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        speaker.close()
    }

    @Test fun closingWarningPreservesManualVolumeChange() {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        val audio = context.getSystemService(AudioManager::class.java)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        val speaker = AndroidCameraSpeaker(context)
        speaker.setOverSpeedAlert(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
        speaker.close()
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
}
