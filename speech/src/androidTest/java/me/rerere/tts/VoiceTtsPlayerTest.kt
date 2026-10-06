package me.rerere.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.tts.controller.VoiceTtsPlayer
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.provider.emitHttpAudio
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/** Real platform decoding/playback, real loopback HTTP; no paid synthesis or mock echo. */
@RunWith(AndroidJUnit4::class)
class VoiceTtsPlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }

    @Test
    fun mp3PlaysBeforeHttpEofAndStopDisconnectsWithoutRevivingOldAudio() = runBlocking {
        val audio = fixture("voice-tone.mp3")
        val socket = ServerSocket(0)
        val prefixSent = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val server = launch(Dispatchers.IO) {
            socket.use { listener ->
                listener.accept().use { peer ->
                    readRequest(peer)
                    val output = peer.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: ${audio.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    val prefix = audio.size * 2 / 3
                    for (offset in 0 until prefix step 997) {
                        output.write(audio, offset, minOf(997, prefix - offset))
                        output.flush()
                        delay(15)
                    }
                    prefixSent.countDown()
                    // Deliberately withhold EOF/body tail. Cancellation must close the actual socket.
                    peer.soTimeout = 8000
                    if (peer.getInputStream().read() == -1) disconnected.countDown()
                }
            }
        }
        val player = VoiceTtsPlayer(context, TTSManager(context))
        try {
            val utterance = async(Dispatchers.Main) {
                player.speak(TTSProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:${socket.localPort}/v1"), "local audio")
            }
            assertTrue(prefixSent.await(5, TimeUnit.SECONDS))
            withTimeout(6000) {
                while (player.playbackPositionMs() < 100) delay(20)
            }
            assertFalse("speak must not complete before network/audio EOS", utterance.isCompleted)
            val start = System.nanoTime()
            player.stop()
            utterance.join()
            assertTrue("stop must cancel promptly", (System.nanoTime() - start) / 1_000_000 < 1500)
            assertTrue("upstream HTTP must disconnect", disconnected.await(3, TimeUnit.SECONDS))
            server.join()
            withTimeout(5000) {
                player.play(flow { emit(AudioChunk(fixture("voice-tone.aac"), AudioFormat.AAC, isLast = true)) })
            }
            // Decoder reuse across a subsequent real compressed stream, not a resumed old stream.
            assertTrue(utterance.isCancelled)
        } finally {
            player.release()
            socket.close()
            server.cancel()
        }
    }

    @Test
    fun pcmOddNetworkBoundariesPlayIncrementallyAndWaitForLastSample() = runBlocking {
        val pcm = tone(24000)
        val socket = ServerSocket(0)
        val tail = CountDownLatch(1)
        val server = launch(Dispatchers.IO) {
            socket.use { listener ->
                listener.accept().use { peer ->
                    readRequest(peer)
                    val output = peer.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: ${pcm.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    for (offset in 0 until pcm.size step 137) {
                        if (offset > pcm.size / 2) tail.await(5, TimeUnit.SECONDS)
                        output.write(pcm, offset, minOf(137, pcm.size - offset))
                        output.flush()
                        delay(1)
                    }
                }
            }
        }
        val player = VoiceTtsPlayer(context, TTSManager(context))
        val client = okhttp3.OkHttpClient()
        try {
            val utterance = async(Dispatchers.Main) {
                player.play(flow {
                    emitHttpAudio(client,
                        okhttp3.Request.Builder().url("http://127.0.0.1:${socket.localPort}/pcm").build(),
                        AudioFormat.PCM, 24000)
                })
            }
            withTimeout(5000) { while (player.playbackPositionMs() < 100) delay(20) }
            assertFalse(utterance.isCompleted)
            tail.countDown()
            withTimeout(5000) { utterance.await() }
            server.join()
        } finally {
            tail.countDown()
            player.release()
            socket.close()
            server.cancel()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun malformedCompressedStreamFailsWithoutRetry() = runBlocking {
        val player = VoiceTtsPlayer(context, TTSManager(context))
        try {
            val failed = async(Dispatchers.Main) {
                runCatching {
                    withTimeout(5000) {
                        player.play(flow { emit(AudioChunk(ByteArray(2048), AudioFormat.MP3, isLast = true)) })
                    }
                }.exceptionOrNull()
            }.await()
            assertTrue(failed is androidx.media3.common.PlaybackException)
        } finally {
            player.release()
        }
    }

    private fun readRequest(peer: Socket) {
        val reader = peer.getInputStream().bufferedReader()
        var length = 0
        while (true) {
            val line = reader.readLine() ?: error("Missing HTTP request")
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
        }
        val body = CharArray(length)
        var count = 0
        while (count < length) {
            val read = reader.read(body, count, length - count)
            check(read > 0)
            count += read
        }
    }

    private fun tone(samples: Int): ByteArray = ByteArray(samples * 2).also { bytes ->
        for (index in 0 until samples) {
            val value = (sin(index * 2 * Math.PI * 440 / 24000) * 5000).toInt()
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
    }
}
