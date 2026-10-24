package dev.teslanavi.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

class SupertonicModelTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun verifiedArchiveInstallsOnlyExpectedFilesFlattened() = runBlocking {
        val archive = archive(SupertonicModel.FILES.associateWith { "data-$it" } +
            mapOf("LICENSE" to "MIT", "../../escape.txt" to "x", "README.md" to "readme"))
        val root = temp.newFolder("root")
        val model = model(root, archive)
        assertEquals(ModelPhase.MISSING, model.state.value.phase)
        assertTrue(model.install())
        assertEquals(ModelPhase.READY, model.state.value.phase)
        assertEquals(model.dir, model.installedDir())
        assertEquals((SupertonicModel.FILES + "LICENSE").sorted(), model.dir.list()!!.sorted())
        assertEquals("data-voice.bin", model.dir.resolve("voice.bin").readText())
        assertFalse(temp.root.resolve("escape.txt").exists())
        assertEquals(listOf("supertonic2"), root.list()!!.toList())
        assertEquals("state survives restart", ModelPhase.READY, model(root, archive).state.value.phase)
    }

    @Test fun corruptOrTruncatedDownloadsFailWithoutPartialFiles() = runBlocking {
        val good = archive(SupertonicModel.FILES.associateWith { "data" })
        val root = temp.newFolder("root")
        val tampered = good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val model = SupertonicModel(root, CoroutineScope(Dispatchers.IO), source(good)) { tampered.inputStream() }
        assertFalse(model.install())
        assertEquals(ModelState(ModelPhase.FAILED, message = "받은 파일이 손상되었습니다. 다시 받아 주세요."), model.state.value)
        val incomplete = archive(SupertonicModel.FILES.drop(1).associateWith { "data" })
        assertFalse(model(root, incomplete).install())
        assertNull(model.installedDir())
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun networkFailureExplainsRetryAndDeleteReturnsToMissing() = runBlocking {
        val root = temp.newFolder("root")
        val offline = SupertonicModel(root, CoroutineScope(Dispatchers.IO), source(ByteArray(1))) { throw IOException("offline") }
        assertFalse(offline.install())
        assertTrue(offline.state.value.message!!.contains("네트워크"))
        val archive = archive(SupertonicModel.FILES.associateWith { "data" })
        val model = model(root, archive)
        val ready = CompletableDeferred<Unit>()
        model.download { ready.complete(Unit) }
        withTimeout(5000) { ready.await() }
        assertTrue(model.installed())
        model.delete()
        assertEquals(ModelPhase.MISSING, model.state.value.phase)
        assertFalse(model.dir.exists())
    }

    private fun model(root: java.io.File, archive: ByteArray) =
        SupertonicModel(root, CoroutineScope(Dispatchers.IO), source(archive)) { archive.inputStream() }

    private fun source(bytes: ByteArray) = SupertonicModel.Source("test://model",
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong())

    private fun archive(files: Map<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("sherpa-onnx-supertonic/"))
            tar.closeArchiveEntry()
            files.forEach { (name, text) ->
                val data = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry("sherpa-onnx-supertonic/$name", true).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
    }.toByteArray()
}
