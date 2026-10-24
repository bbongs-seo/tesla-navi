package dev.teslanavi.navigation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SupertonicVoiceTest {
    private val dir = File("model")

    @Test fun sentencesAreNormalizedRenderedOnceAndJoinedInOrder() = runBlocking {
        val renderer = FakeRenderer()
        val voice = SupertonicVoice({ dir }, { renderer })
        assertNull(voice.load())
        assertTrue(voice.ready)
        val first = render(voice, "300미터 앞 과속 단속 카메라가 있습니다. 제한속도 시속 60킬로미터입니다.")!!
        assertEquals(listOf("삼백미터 앞 과속 단속 카메라가 있습니다.", "제한속도 시속 육십킬로미터입니다."), renderer.rendered)
        assertEquals(renderer.lengthOf(renderer.rendered[0]) + renderer.lengthOf(renderer.rendered[1]), first.frames)
        assertEquals("little-endian 16-bit", 1000, (first.bytes[0].toInt() and 0xFF) or (first.bytes[1].toInt() shl 8))
        assertEquals(24_000, first.sampleRate)
        render(voice, "500미터 앞 과속 단속 카메라가 있습니다. 제한속도 시속 60킬로미터입니다.")
        assertEquals("cached limit sentence is reused", 3, renderer.rendered.size)
        assertEquals("오백미터 앞 과속 단속 카메라가 있습니다.", renderer.rendered.last())
    }

    @Test fun prefetchPreparesSentencesSoTheAnnouncementNeedsNoRendering() = runBlocking {
        val renderer = FakeRenderer()
        val voice = SupertonicVoice({ dir }, { renderer })
        voice.load()
        voice.prefetch("제한속도 60킬로미터 초과입니다.")
        render(voice, "주행시작")
        val count = renderer.rendered.size
        assertNotNull(render(voice, "제한속도 60킬로미터 초과입니다."))
        assertEquals(count, renderer.rendered.size)
        assertTrue("제한속도 육십킬로미터 초과입니다." in renderer.rendered)
    }

    @Test fun cacheEvictsLeastRecentlyUsedSentencesBeyondItsBudget() = runBlocking {
        val renderer = FakeRenderer()
        val voice = SupertonicVoice({ dir }, { renderer }, cacheSamples = 10) // two 4-sample sentences
        voice.load()
        render(voice, "가."); render(voice, "나."); render(voice, "가."); render(voice, "다.")
        assertEquals(listOf("가.", "나.", "다."), renderer.rendered)
        render(voice, "가.")
        assertEquals("가 stayed cached because it was used recently", 3, renderer.rendered.size)
        render(voice, "나.")
        assertEquals(4, renderer.rendered.size)
    }

    @Test fun missingModelOrEngineFailureIsReportedAndRenderingYieldsNothing() = runBlocking {
        val missing = SupertonicVoice({ null }, { FakeRenderer() })
        assertNotNull(missing.load())
        assertFalse(missing.ready)
        assertNull(render(missing, "주행시작"))
        val broken = SupertonicVoice({ dir }, { throw UnsatisfiedLinkError("no native library") })
        assertEquals("Supertonic 음성 엔진을 시작하지 못했습니다.", broken.load())
        val failing = SupertonicVoice({ dir }, { FakeRenderer(fail = true) })
        failing.load()
        assertNull(render(failing, "주행시작"))
    }

    @Test fun releaseFreesTheEngineAfterQueuedWorkAndAllowsReload() = runBlocking {
        val renderer = FakeRenderer()
        val voice = SupertonicVoice({ dir }, { renderer })
        voice.load()
        render(voice, "주행시작")
        voice.release()
        assertFalse(voice.ready)
        assertNull(render(voice, "새 문장."))
        assertTrue(renderer.released)
        assertNull(voice.load())
        assertNotNull(render(voice, "주행시작"))
        assertEquals("cache was cleared with the engine", 2, renderer.rendered.count { it == "주행시작" })
    }

    private fun render(voice: SupertonicVoice, text: String): Pcm? {
        val result = CompletableFuture<Pcm?>()
        voice.render(text) { result.complete(it) }
        return result.get(5, TimeUnit.SECONDS)
    }
}

private class FakeRenderer(private val fail: Boolean = false) : SpeechRenderer {
    val rendered = mutableListOf<String>()
    var released = false
    override val sampleRate = 24_000
    fun lengthOf(sentence: String) = sentence.length * 2
    override fun render(sentence: String): FloatArray {
        if (fail) error("inference failed")
        rendered += sentence
        return FloatArray(lengthOf(sentence)) { if (it == 0) 1000f / Short.MAX_VALUE else 0f }
    }
    override fun release() { released = true }
}
