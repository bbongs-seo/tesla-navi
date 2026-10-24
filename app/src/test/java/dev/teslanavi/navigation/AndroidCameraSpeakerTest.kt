package dev.teslanavi.navigation

import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.TeslaNaviApp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class, shadows = [ControlledSpeech::class])
class AndroidCameraSpeakerTest {
    @Test fun stoppedSpeechAllowsOverspeedSpeechAndRepeatingChime() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val speaker = AndroidCameraSpeaker(context)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
        try {
            assertTrue(speaker.speak("카메라가 있습니다"))
            speaker.setOverSpeedAlert(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501))
            assertNull(trackOf(speaker))
            speech.utteranceProgressListener.onStop(speech.lastId, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(speaker.speak("제한속도 60킬로미터 초과입니다"))
            speech.utteranceProgressListener.onDone(speech.lastId)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
            val firstChime = checkNotNull(trackOf(speaker))
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, firstChime.playState)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
            val nextChime = checkNotNull(trackOf(speaker))
            assertNotSame(firstChime, nextChime)
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, nextChime.playState)
            speaker.setOverSpeedAlert(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4000))
            assertNull(trackOf(speaker))
        } finally {
            speaker.close()
        }
    }

    @Test fun lateStopFromOldSpeechDoesNotClearNewSpeech() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val speaker = AndroidCameraSpeaker(context)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
        try {
            assertTrue(speaker.speak("첫 안내"))
            val oldId = speech.lastId
            speaker.silence()
            assertTrue(speaker.speak("새 안내"))
            speech.utteranceProgressListener.onStop(oldId, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(speaker.speak("겹치는 안내"))
            speech.utteranceProgressListener.onStop(speech.lastId, false)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(speaker.speak("다음 안내"))
        } finally { speaker.close() }
    }

    private fun trackOf(speaker: AndroidCameraSpeaker): AudioTrack? = ReflectionHelpers.getField(speaker, "track")
}

/** Deliver actual Android TTS callbacks explicitly, including interruption without onDone. */
@Implements(TextToSpeech::class)
class ControlledSpeech : ShadowTextToSpeech() {
    var lastId: String? = null
    @Implementation
    public override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
        lastId = utteranceId
        return TextToSpeech.SUCCESS
    }
}
