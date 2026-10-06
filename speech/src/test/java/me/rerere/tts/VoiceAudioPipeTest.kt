package me.rerere.tts

import me.rerere.tts.controller.TextChunker
import me.rerere.tts.controller.VoiceAudioPipe
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class VoiceAudioPipeTest {
    @Test
    fun largerThanCapacityAudioKeepsOrderAcrossWrapAndEof() {
        val pipe = VoiceAudioPipe(17)
        val audio = ByteArray(1003) { (it % 127).toByte() }
        val producer = thread {
            pipe.write(audio)
            pipe.finish()
        }
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(13)
        while (true) {
            val count = pipe.read(buffer, 0, buffer.size)
            if (count == -1) break
            collected.write(buffer, 0, count)
        }
        producer.join(1000)
        assertFalse(producer.isAlive)
        assertArrayEquals(audio, collected.toByteArray())
    }

    @Test
    fun stoppingUnblocksProducerAndDropsPreviouslyBufferedAudio() {
        val pipe = VoiceAudioPipe(8)
        val started = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val producer = thread {
            started.countDown()
            try {
                pipe.write(ByteArray(1000000))
            } catch (failure: Throwable) {
                error.set(failure)
            }
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        pipe.abort()
        producer.join(1000)
        assertFalse(producer.isAlive)
        assertTrue(error.get() is IOException)
        val failure = runCatching { pipe.read(ByteArray(8), 0, 8) }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun noPunctuationTextRespectsLimitWithoutSplittingSurrogatePairs() {
        val text = "你好😀".repeat(200)
        val chunks = TextChunker(160).split(text)
        assertEquals(text, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { it.text.length <= 160 })
        assertTrue(chunks.none { it.text.last().isHighSurrogate() || it.text.first().isLowSurrogate() })
    }
}
