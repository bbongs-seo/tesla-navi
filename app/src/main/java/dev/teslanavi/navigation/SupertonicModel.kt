package dev.teslanavi.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

enum class ModelPhase { MISSING, DOWNLOADING, READY, FAILED }
data class ModelState(val phase: ModelPhase, val progress: Float = 0f, val message: String? = null)

/** Downloads the Supertonic 2 int8 model once, verifies its SHA-256 and installs it under app-private storage. */
class SupertonicModel(
    root: File,
    private val scope: CoroutineScope,
    private val source: Source = SUPERTONIC_2,
    private val open: (String) -> InputStream = ::https,
) {
    data class Source(val url: String, val sha256: String, val bytes: Long)

    val dir = File(root, "supertonic2")
    private val staging = File(root, "supertonic2.part")
    private val archive = File(root, "supertonic2.tar.bz2.part")
    private val mutable = MutableStateFlow(ModelState(if (installed()) ModelPhase.READY else ModelPhase.MISSING))
    val state = mutable.asStateFlow()
    private var job: Job? = null

    init { if (!installed()) cleanup() }

    fun installed(): Boolean = FILES.all { File(dir, it).length() > 0 }
    fun installedDir(): File? = dir.takeIf { installed() }

    fun download(onReady: () -> Unit = {}) {
        if (job?.isActive == true || installed()) return
        job = scope.launch { if (install()) onReady() }
    }

    fun cancel() { job?.cancel() }

    fun delete() {
        cancel()
        dir.deleteRecursively()
        cleanup()
        mutable.value = ModelState(ModelPhase.MISSING)
    }

    /** Returns true when the model is installed. Failures leave no partial files behind. */
    suspend fun install(): Boolean {
        mutable.value = ModelState(ModelPhase.DOWNLOADING)
        return try {
            withContext(Dispatchers.IO) {
                fetch()
                mutable.value = ModelState(ModelPhase.DOWNLOADING, 1f, "설치하는 중")
                extract()
            }
            mutable.value = ModelState(ModelPhase.READY)
            true
        } catch (error: CancellationException) {
            cleanup(); mutable.value = ModelState(ModelPhase.MISSING); throw error
        } catch (error: Exception) {
            cleanup()
            mutable.value = ModelState(ModelPhase.FAILED, message = if (error is CorruptDownload)
                "받은 파일이 손상되었습니다. 다시 받아 주세요." else "음성 모델을 받지 못했습니다. 네트워크를 확인한 뒤 다시 시도해 주세요.")
            false
        }
    }

    private suspend fun fetch() {
        val context = currentCoroutineContext()
        archive.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        DigestInputStream(open(source.url), digest).use { input ->
            archive.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var reported = 0
                while (true) {
                    context.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    received += read
                    val percent = (received * 100 / source.bytes).toInt().coerceAtMost(99)
                    if (percent != reported) { reported = percent; mutable.value = ModelState(ModelPhase.DOWNLOADING, percent / 100f) }
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (received != source.bytes || hash != source.sha256) throw CorruptDownload()
    }

    /** Only the expected file names are written, flattened into the staging directory. */
    private fun extract() {
        staging.deleteRecursively()
        check(staging.mkdirs()) { "모델 폴더를 만들지 못했습니다." }
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || (name !in FILES && name != "LICENSE")) continue
                File(staging, name).outputStream().use { tar.copyTo(it) }
            }
        }
        if (!FILES.all { File(staging, it).length() > 0 }) throw CorruptDownload()
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) throw IOException("모델 설치 실패")
        archive.delete()
    }

    private fun cleanup() { staging.deleteRecursively(); archive.delete() }

    private class CorruptDownload : IOException()

    companion object {
        val SUPERTONIC_2 = Source(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-tts-int8-2026-03-06.tar.bz2",
            "8c74359f63edd5045d47747f65331f0f6dbcbc91d7e898dd756d631295fe3259", 84_692_981L)
        val FILES = setOf("duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
            "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin")

        private fun https(url: String): InputStream = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 30_000
            readTimeout = 60_000
            if (responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP $responseCode")
            inputStream
        }
    }
}
