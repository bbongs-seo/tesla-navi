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

/**
 * Guidance audio: the phone's TTS, or the offline [neural] voice when selected and loaded. The system voice stays
 * initialized as the fallback whenever the neural voice cannot render a sentence.
 */
class AndroidCameraSpeaker(
    private val context: Context,
    private val neural: PcmVoice? = null,
    private val preferred: () -> GuidanceVoice = { GuidanceVoice.SYSTEM },
) : CameraSpeaker {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var focus: AudioFocusRequest? = null
    private var alarm = false
    private var track: AudioTrack? = null
    private var voiceTrack: AudioTrack? = null
    private var gain = 1f
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
                }.onFailure { android.util.Log.w(TAG, "과속 경고음 출력 실패", it); setOverSpeedAlert(false) }
            }
            if (alarm) handler.postDelayed(this, 2000)
        }
    }
    private var utterance: String? = null
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    override suspend fun initialize(): String? {
        close()
        val systemError = initializeSystemVoice()
        val voice = neural?.takeIf { preferred() == GuidanceVoice.SUPERTONIC } ?: return systemError
        val loading = SystemClock.elapsedRealtime()
        voice.load()?.let { android.util.Log.w(TAG, "$it 기본 음성으로 안내합니다."); return systemError }
        android.util.Log.i(TAG, "Supertonic 엔진 준비 ${SystemClock.elapsedRealtime() - loading}ms")
        voice.prefetch("주행시작")
        return null
    }

    private suspend fun initializeSystemVoice(): String? {
        val status = CompletableDeferred<Int>()
        tts = TextToSpeech(context.applicationContext) { status.complete(it) }
        if (withTimeoutOrNull(7000) { status.await() } != TextToSpeech.SUCCESS) {
            shutdownSystemVoice(); return "음성 엔진을 시작하지 못했습니다. 화면으로 카메라 정보를 확인해 주세요."
        }
        val engine = checkNotNull(tts)
        val language = engine.setLanguage(Locale.KOREAN)
        if (language < 0) { shutdownSystemVoice(); return "휴대폰에 한국어 TTS 음성 데이터를 설치해 주세요." }
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { finished(id) }
            override fun onStop(id: String?, interrupted: Boolean) { finished(id) }
            @Deprecated("Required by the Android TTS API")
            override fun onError(id: String?) { finished(id) }
            override fun onError(id: String?, errorCode: Int) { finished(id) }
        })
        return null
    }

    private fun finished(id: String?) { handler.post { finishedNow(id) } }
    private fun finishedNow(id: String?) {
        if (id == utterance) { utterance = null; releaseVoiceTrack(); if (!alarm) releaseFocus() }
    }

    private fun neuralVoice() = neural?.takeIf { preferred() == GuidanceVoice.SUPERTONIC && it.ready }

    override fun speak(text: String): Boolean {
        val voice = neuralVoice()
        val engine = tts
        if (voice == null && engine == null) return false
        if (utterance != null || gain == 0f) return false
        track?.pause()
        if (!acquireFocus()) return false
        val id = UUID.randomUUID().toString()
        utterance = id
        if (voice == null) return speakSystem(checkNotNull(engine), text, id)
        val requested = SystemClock.elapsedRealtime()
        voice.render(text) { pcm -> handler.post {
            if (id != utterance) return@post
            if (pcm != null && play(pcm, id)) {
                android.util.Log.i(TAG, "Supertonic 출력까지 ${SystemClock.elapsedRealtime() - requested}ms · 음성 ${pcm.millis}ms")
                return@post
            }
            android.util.Log.w(TAG, "Supertonic 음성을 만들지 못해 기본 음성으로 안내합니다.")
            if (tts?.let { speakSystem(it, text, id) } != true) finishedNow(id)
        } }
        return true
    }

    override fun prefetch(text: String) { neuralVoice()?.prefetch(text) }

    private fun speakSystem(engine: TextToSpeech, text: String, id: String): Boolean {
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, gain) }, id) == TextToSpeech.SUCCESS) return true
        utterance = null; releaseFocus(); return false
    }

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
            override fun onMarkerReached(track: AudioTrack?) = finishedNow(id)
            override fun onPeriodicNotification(track: AudioTrack?) = Unit
        }, handler)
        handler.postDelayed({ finishedNow(id) }, pcm.millis + 1000)
        output.play()
    }.onFailure { android.util.Log.w(TAG, "Supertonic 음성 출력 실패", it); releaseVoiceTrack() }.isSuccess

    private fun releaseVoiceTrack() { voiceTrack?.let { runCatching { it.stop() }; it.release() }; voiceTrack = null }

    private fun acquireFocus(): Boolean {
        if (focus != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
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

    override fun silence() { setOverSpeedAlert(false); utterance = null; tts?.stop(); releaseVoiceTrack(); releaseFocus() }
    private fun releaseFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null

    }
    private fun shutdownSystemVoice() { tts?.shutdown(); tts = null }
    /** The neural engine is app-wide and stays loaded; only this speaker's audio is released. */
    override fun close() { silence(); shutdownSystemVoice() }

    private companion object { const val TAG = "TeslaNaviAudio" }
}
