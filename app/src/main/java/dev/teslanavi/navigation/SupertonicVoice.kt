package dev.teslanavi.navigation

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** 16-bit little-endian mono PCM for a static AudioTrack, the same layout as [WarningChime]. */
class Pcm(val bytes: ByteArray, val sampleRate: Int) {
    val frames get() = bytes.size / 2
    val millis get() = frames * 1000L / sampleRate
}

/** Offline voice that renders speech off the main thread. [render] calls back on the voice's own thread. */
interface PcmVoice {
    val ready: Boolean
    suspend fun load(): String?
    fun render(text: String, done: (Pcm?) -> Unit)
    fun prefetch(text: String)
}

/** One sentence to float PCM. Production wraps sherpa-onnx; tests substitute a fake. */
interface SpeechRenderer {
    val sampleRate: Int
    fun render(sentence: String): FloatArray
    fun release()
}

/** Supertonic 2 int8 through sherpa-onnx. 5 steps and 2 threads favour latency for short guidance sentences. */
class SupertonicRenderer(dir: File) : SpeechRenderer {
    private val tts = OfflineTts(null, OfflineTtsConfig(model = OfflineTtsModelConfig(
        supertonic = OfflineTtsSupertonicModelConfig(
            durationPredictor = File(dir, "duration_predictor.int8.onnx").path,
            textEncoder = File(dir, "text_encoder.int8.onnx").path,
            vectorEstimator = File(dir, "vector_estimator.int8.onnx").path,
            vocoder = File(dir, "vocoder.int8.onnx").path,
            ttsJson = File(dir, "tts.json").path,
            unicodeIndexer = File(dir, "unicode_indexer.bin").path,
            voiceStyle = File(dir, "voice.bin").path),
        numThreads = 2, debug = false)))
    override val sampleRate = tts.sampleRate()
    override fun render(sentence: String): FloatArray = tts.generateWithConfig(sentence,
        GenerationConfig(sid = 0, speed = 1.2f, numSteps = 5, extra = mapOf("lang" to "ko"))).samples
    override fun release() = tts.release()
}

/**
 * The app keeps one engine for its lifetime. Every model call runs on a single thread, so releasing never races
 * a native inference. Guidance repeats a small set of templated sentences, cached per sentence as PCM.
 */
class SupertonicVoice(
    private val model: () -> File?,
    private val open: (File) -> SpeechRenderer = ::SupertonicRenderer,
    private val cacheSamples: Int = 44_100 * 90,
) : PcmVoice {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "supertonic").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val queued = AtomicInteger()
    private val boundary = Regex("""(?<=[.!?])\s+""")
    // Owned by the executor thread.
    private var renderer: SpeechRenderer? = null
    private val cache = LinkedHashMap<String, Pcm>(16, .75f, true)
    private var cachedSamples = 0
    @Volatile override var ready = false
        private set

    override suspend fun load(): String? = withContext(dispatcher) { loadNow() }
    fun warmUp() = submit { loadNow() }
    override fun render(text: String, done: (Pcm?) -> Unit) = submit { done(runCatching { compose(text) }.getOrNull()) }

    /** Prepares sentences while the voice is idle; never delays an announcement already queued. */
    override fun prefetch(text: String) {
        if (!ready || queued.get() > 0) return
        submit { runCatching { sentences(text).forEach(::sentence) } }
    }

    fun release() {
        ready = false
        submit { renderer?.release(); renderer = null; cache.clear(); cachedSamples = 0 }
    }

    private fun loadNow(): String? {
        if (renderer != null) return null
        val dir = model() ?: return "Supertonic 음성 모델을 먼저 받아 주세요."
        return try { renderer = open(dir); ready = true; null }
        catch (_: Throwable) { "Supertonic 음성 엔진을 시작하지 못했습니다." }
    }

    private fun submit(task: () -> Unit) {
        queued.incrementAndGet()
        executor.execute { try { task() } finally { queued.decrementAndGet() } }
    }

    private fun sentences(text: String) = text.split(boundary).map(KoreanSpeechText::normalize).filter(String::isNotEmpty)

    private fun compose(text: String): Pcm? {
        val parts = sentences(text).map { sentence(it) ?: return null }
        if (parts.size <= 1) return parts.firstOrNull()
        val joined = ByteArray(parts.sumOf { it.bytes.size })
        var at = 0
        parts.forEach { it.bytes.copyInto(joined, at); at += it.bytes.size }
        return Pcm(joined, parts.first().sampleRate)
    }

    private fun sentence(text: String): Pcm? {
        cache[text]?.let { return it }
        val engine = renderer ?: return null
        val floats = engine.render(text).takeIf { it.isNotEmpty() } ?: return null
        val bytes = ByteArray(floats.size * 2)
        floats.forEachIndexed { i, sample ->
            val value = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
            bytes[2 * i] = value.toByte()
            bytes[2 * i + 1] = (value shr 8).toByte()
        }
        val pcm = Pcm(bytes, engine.sampleRate)
        cache[text] = pcm
        cachedSamples += pcm.frames
        val oldest = cache.values.iterator()
        while (cachedSamples > cacheSamples && cache.size > 1) {
            cachedSamples -= oldest.next().frames
            oldest.remove()
        }
        return pcm
    }
}
