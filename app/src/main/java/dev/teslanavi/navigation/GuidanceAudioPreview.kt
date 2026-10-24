package dev.teslanavi.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PreviewSound { CAMERA_VOICE, OVERSPEED_CHIME }
data class AudioPreviewState(val playing: PreviewSound? = null, val message: String? = null)

/** A short sample through the same speaker as driving guidance, with its own lifetime. */
class GuidanceAudioPreview(
    private val scope: CoroutineScope,
    private val createSpeaker: () -> CameraSpeaker,
) {
    private val mutable = MutableStateFlow(AudioPreviewState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var speaker: CameraSpeaker? = null
    private var generation = 0

    fun play(sound: PreviewSound, volumePercent: Int) {
        stop()
        if (volumePercent <= 0) {
            mutable.value = AudioPreviewState(message = "안내 음량이 0%입니다. 음량을 올린 뒤 테스트해 주세요.")
            return
        }
        val token = generation
        mutable.value = AudioPreviewState(playing = sound)
        job = scope.launch {
            val output = createSpeaker()
            speaker = output
            try {
                output.setVolume(volumePercent)
                when (sound) {
                    PreviewSound.CAMERA_VOICE -> {
                        val error = output.initialize()
                        if (error != null) {
                            mutable.value = AudioPreviewState(message = error)
                            return@launch
                        }
                        if (!output.speak("300미터 앞 과속 단속 카메라가 있습니다. 제한속도 시속 60킬로미터입니다.")) {
                            mutable.value = AudioPreviewState(message = "음성을 재생하지 못했습니다. 잠시 후 다시 테스트해 주세요.")
                            return@launch
                        }
                        delay(10_000)
                    }
                    PreviewSound.OVERSPEED_CHIME -> {
                        output.setOverSpeedAlert(true)
                        delay(6_500)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == token) mutable.value = AudioPreviewState(message = "소리를 재생하지 못했습니다. 다시 테스트해 주세요.")
            } finally {
                output.close()
                if (speaker === output) speaker = null
                if (generation == token) mutable.value = mutable.value.copy(playing = null)
            }
        }
    }

    fun setVolume(percent: Int) {
        speaker?.setVolume(percent)
        if (percent <= 0 && state.value.playing != null) {
            stop()
            mutable.value = AudioPreviewState(message = "안내 음량이 0%입니다. 음량을 올린 뒤 테스트해 주세요.")
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        speaker?.silence()
        mutable.value = AudioPreviewState()
    }
}
