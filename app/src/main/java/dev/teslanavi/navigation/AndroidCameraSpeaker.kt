package dev.teslanavi.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioFormat
import kotlin.math.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

class AndroidCameraSpeaker(private val context: Context) : CameraSpeaker {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var focus: AudioFocusRequest? = null
    private var alarm = false
    private var track: AudioTrack? = null
    private var gain = 1f
    private val alarmLoop = object : Runnable {
        override fun run() {
            if (!alarm) return
            if (utterance == null && acquireFocus()) {
                runCatching {
                    track?.release()
                    val rate = 22050
                    val samples = ShortArray(rate) { i ->
                        val t = i.toDouble() / rate
                        val local = if (t < .4) t else t - .4
                        val frequency = if (t < .4) 880.0 else 660.0
                        if (local in 0.0..0.28) (sin(2 * PI * frequency * local) * exp(-local * 9) * 26000).toInt().toShort() else 0
                    }
                    track = AudioTrack.Builder().setAudioAttributes(attributes)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build().apply {
                            val bytes = ByteArray(samples.size * 2)
                            samples.forEachIndexed { i, value ->
                                bytes[i * 2] = value.toByte(); bytes[i * 2 + 1] = (value.toInt() shr 8).toByte()
                            }
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
        close()
        val status = CompletableDeferred<Int>()
        tts = TextToSpeech(context.applicationContext) { status.complete(it) }
        if (withTimeoutOrNull(7000) { status.await() } != TextToSpeech.SUCCESS) {
            close(); return "음성 엔진을 시작하지 못했습니다. 화면으로 카메라 정보를 확인해 주세요."
        }
        val engine = checkNotNull(tts)
        val language = engine.setLanguage(Locale.KOREAN)
        if (language < 0) { close(); return "휴대폰에 한국어 TTS 음성 데이터를 설치해 주세요." }
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { finished(id) }
            @Deprecated("Required by the Android TTS API")
            override fun onError(id: String?) { finished(id) }
            override fun onError(id: String?, errorCode: Int) { finished(id) }
        })
        return null
    }

    private fun finished(id: String?) { handler.post {
        if (id == utterance) { utterance = null; if (!alarm) releaseFocus() }
    } }

    override fun speak(text: String): Boolean {
        val engine = tts ?: return false
        if (utterance != null || gain == 0f) return false
        track?.pause()
        if (!acquireFocus()) return false
        val id = UUID.randomUUID().toString()
        utterance = id
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, gain) }, id) != TextToSpeech.SUCCESS) {
            utterance = null; releaseFocus(); return false
        }
        return true
    }

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

    override fun silence() { setOverSpeedAlert(false); utterance = null; tts?.stop(); releaseFocus() }
    private fun releaseFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null

    }
    override fun close() { silence(); tts?.shutdown(); tts = null }
}
