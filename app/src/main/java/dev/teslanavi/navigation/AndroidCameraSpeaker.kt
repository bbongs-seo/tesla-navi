package dev.teslanavi.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

class AndroidCameraSpeaker(private val context: Context,
    private val neural: PcmVoice? = null,
    private val preferred: () -> GuidanceVoice = { GuidanceVoice.SYSTEM },
) : CameraSpeaker {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var focus: AudioFocusRequest? = null
    private var alarm = false
    private var voiceTrack: AudioTrack? = null
    private var track: AudioTrack? = null
    private var gain = 1f
    override var needsInitialization = true
        private set
    private var completion: ((Boolean) -> Unit)? = null
    private var retryAfter = 0L
    private val speechTimeout = Runnable {
        utterance?.let { id ->
            record("speech timeout id=$id")
            needsInitialization = true
            finishCurrent(false)
            tts?.stop()
        }
    }
    private val alarmLoop = object : Runnable {
        override fun run() {
            if (!alarm) return
            if (utterance == null && acquireFocus()) {
                runCatching {
                    track?.release()
                    val bytes = WarningChime.pcm
                    track = AudioTrack.Builder().setAudioAttributes(attributes)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(WarningChime.SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(bytes.size).build()
                    checkNotNull(track).apply {
                            check(write(bytes, 0, bytes.size) == bytes.size) { "경고음 PCM 데이터 전송 실패" }
                            setVolume(gain)
                            play()
                        }
                }.onFailure { android.util.Log.w("TeslaNaviAudio", "과속 경고음 출력 실패", it); setOverSpeedAlert(false) }
            }
            if (alarm) handler.postDelayed(this, 2000)
        }
    }
    private var utterance: String? = null
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    override suspend fun initialize(): String? {
        val systemError = initializeSystemVoice()
        val voice = neural?.takeIf { preferred() == GuidanceVoice.SUPERTONIC } ?: return systemError
        val error = voice.load()
        if (error != null) { record("neural init failed; system fallback"); return systemError }
        needsInitialization = false
        voice.prefetch("주행시작")
        return null
    }

    private suspend fun initializeSystemVoice(): String? {
        // Rebuilding TTS must not stop the independently playing overspeed chime.
        stopSpeech()
        tts?.shutdown()
        tts = null
        needsInitialization = true
        record("tts init start")
        val status = CompletableDeferred<Int>()
        try {
            tts = TextToSpeech(context.applicationContext) { status.complete(it) }
            val result = withTimeoutOrNull(7000) { status.await() }
            record("tts init result=$result")
            if (result != TextToSpeech.SUCCESS) {
                return "음성 엔진을 시작하지 못했습니다. 자동으로 다시 시도합니다."
            }
            val engine = checkNotNull(tts)
            val language = engine.setLanguage(Locale.KOREAN)
            record("tts language=$language engine=${engine.defaultEngine}")
            if (language < 0) return "휴대폰에 한국어 TTS 음성 데이터를 설치해 주세요."
            check(engine.setAudioAttributes(attributes) == TextToSpeech.SUCCESS)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { handler.post {
                    if (id == utterance) {
                        record("speech start id=$id")
                        handler.removeCallbacks(speechTimeout)
                        handler.postDelayed(speechTimeout, 30_000)
                    }
                } }
                override fun onDone(id: String?) { finished(id, true, "done") }
                override fun onStop(id: String?, interrupted: Boolean) { finished(id, false, "stop") }
                @Deprecated("Required by the Android TTS API")
                override fun onError(id: String?) { finished(id, false, "error", rebuild = true) }
                override fun onError(id: String?, errorCode: Int) { finished(id, false, "error=$errorCode", rebuild = true) }
            })
            needsInitialization = false
            retryAfter = 0
            return null
        } finally {
            if (needsInitialization) { tts?.shutdown(); tts = null }
        }
    }

    private fun record(event: String) = AudioDiagnostics.record(context, event)

    private fun finished(id: String?, success: Boolean, result: String, rebuild: Boolean = false) { handler.post {
        if (id != null && id == utterance) {
            record("speech $result id=$id")
            if (rebuild) needsInitialization = true
            finishCurrent(success)
        }
    } }

    private fun finishCurrent(success: Boolean) {
        handler.removeCallbacks(speechTimeout)
        utterance = null
        releaseVoiceTrack()
        val callback = completion
        completion = null
        if (!success) retryAfter = SystemClock.elapsedRealtime() + 2000
        if (!alarm) releaseFocus()
        callback?.invoke(success)
    }

    override fun speak(text: String): Boolean = speak(text) {}

    override fun speak(text: String, onComplete: (Boolean) -> Unit): Boolean {
        val voice = neuralVoice()
        if (voice == null && tts == null) return false
        if (needsInitialization || utterance != null || gain == 0f || SystemClock.elapsedRealtime() < retryAfter) return false
        track?.pause()
        if (!acquireFocus()) {
            retryAfter = SystemClock.elapsedRealtime() + 2000
            record("speech focus denied")
            return false
        }
        val id = UUID.randomUUID().toString()
        utterance = id
        completion = onComplete
        record("speech requested id=$id gain=$gain musicVolume=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}")
        handler.postDelayed(speechTimeout, 10_000)
        if (voice != null) {
            voice.render(text) { pcm -> handler.post {
                if (id != utterance) return@post
                if (pcm != null && play(pcm, id)) return@post
                record("neural render failed; system fallback id=$id")
                speakSystem(text, id)
            } }
            return true
        }
        return speakSystem(text, id)
    }

    private fun speakSystem(text: String, id: String): Boolean {
        val result = runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH,
                Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, gain) }, id)
        }.getOrNull()
        if (result != TextToSpeech.SUCCESS) {
            record("speech rejected id=$id result=$result")
            needsInitialization = true
            finishCurrent(false)
            return false
        }
        handler.removeCallbacks(speechTimeout)
        handler.postDelayed(speechTimeout, 10_000)
        return true
    }

    private fun neuralVoice() = neural?.takeIf { preferred() == GuidanceVoice.SUPERTONIC && it.ready }
    override fun prefetch(text: String) { neuralVoice()?.prefetch(text) }

    /** Static PCM playback. The marker ends the utterance; a timer covers devices that drop the marker callback. */
    private fun play(pcm: Pcm, id: String): Boolean = runCatching {
        releaseVoiceTrack()
        val output = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(pcm.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.bytes.size).build()
        voiceTrack = output
        check(output.write(pcm.bytes, 0, pcm.bytes.size) == pcm.bytes.size) { "음성 PCM 데이터 전송 실패" }
        output.setVolume(gain)
        output.notificationMarkerPosition = pcm.frames
        output.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack?) = finished(id, true, "neural done")
            override fun onPeriodicNotification(track: AudioTrack?) = Unit
        }, handler)
        handler.removeCallbacks(speechTimeout)
        handler.postDelayed({ finished(id, true, "neural done") }, pcm.millis + 1000)
        output.play()
    }.onFailure { android.util.Log.w("TeslaNaviAudio", "Supertonic 음성 출력 실패", it); releaseVoiceTrack() }.isSuccess

    private fun releaseVoiceTrack() { voiceTrack?.let { runCatching { it.stop() }; it.release() }; voiceTrack = null }

    private fun acquireFocus(): Boolean {
        if (focus != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    record("audio focus lost change=$change")
                    setOverSpeedAlert(false); silence()
                }
            }, handler).build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = request
        return true
    }

    override fun setVolume(percent: Int) {
        gain = percent.coerceIn(0, 100) / 100f
        track?.setVolume(gain)
        voiceTrack?.setVolume(gain)
        if (gain == 0f) silence()
    }

    override fun setOverSpeedAlert(active: Boolean) {
        if (active && gain == 0f) return
        if (alarm == active) return
        alarm = active
        handler.removeCallbacks(alarmLoop)
        if (active) handler.postDelayed(alarmLoop, 1500)
        else { track?.pause(); track?.release(); track = null; if (utterance == null) releaseFocus() }
    }

    private fun stopSpeech() {
        handler.removeCallbacks(speechTimeout)
        utterance = null
        releaseVoiceTrack()
        val callback = completion
        completion = null
        tts?.stop()
        callback?.invoke(false)
        if (!alarm) releaseFocus()
    }
    override fun silence() { setOverSpeedAlert(false); stopSpeech(); releaseFocus() }
    private fun releaseFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null

    }
    override fun close() { silence(); tts?.shutdown(); tts = null; needsInitialization = true }
}
