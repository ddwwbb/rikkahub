package me.rerere.asr.providers

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.AcousticEchoCanceler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRStatus
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Local real RFC6455 peer + Android microphone. Fixture text is not an upstream ASR claim. */
@RunWith(AndroidJUnit4::class)
class VoiceGatewayRuntimeTest {
    @Test fun dictationUsesRealMicrophoneAndWebSocket() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        }
        val client = OkHttpClient()
        LocalGatewayPeer(voice = false).use { peer ->
            val controller = VoiceGatewayASRController(context, client,
                ASRProviderSetting.VoiceGateway(websocketUrl = peer.url))
            val finalCallback = CountDownLatch(1)
            try {
                instrumentation.runOnMainSync { controller.start { if (it == "gateway final fixture") finalCallback.countDown() } }
                assertTrue("No real microphone PCM reached the local WebSocket peer", peer.audioReceived.await(10, TimeUnit.SECONDS))
                assertTrue(peer.audioBytes.get() > 0)
                assertEquals(0, peer.audioBytes.get() % 2)
                withTimeout(10_000) { controller.state.first { it.transcript == "gateway interim fixture" } }
                instrumentation.runOnMainSync { controller.stop() }
                withTimeout(10_000) { controller.state.first { it.status == ASRStatus.Idle || it.status == ASRStatus.Error } }
                assertEquals(controller.state.value.errorMessage, ASRStatus.Idle, controller.state.value.status)
                assertEquals("gateway final fixture", controller.state.value.transcript)
                assertTrue("Final text did not reach the dictation callback", finalCallback.await(5, TimeUnit.SECONDS))
                assertTrue("Gateway finish command was not received", peer.finishReceived.await(5, TimeUnit.SECONDS))
                assertTrue("WebSocket did not close after real final", peer.closed.await(5, TimeUnit.SECONDS))
                peer.failure.get()?.let { throw AssertionError("Local WebSocket peer failed", it) }
            } finally {
                instrumentation.runOnMainSync { controller.dispose() }
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun duplexWithoutAecExplicitlyRequiresHeadphones() = runBlocking<Unit> {
        assumeFalse("This check targets devices without system AEC", AcousticEchoCanceler.isAvailable())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        }
        val client = OkHttpClient()
        LocalGatewayPeer(voice = true).use { peer ->
            val controller = createDuplexAsr(context, client, ASRProviderSetting.VoiceGateway(websocketUrl = peer.url))
            try {
                instrumentation.runOnMainSync { controller.start {} }
                withTimeout(10_000) { controller.state.first { it.status == ASRStatus.Error } }
                assertTrue(controller.state.value.errorMessage.orEmpty().contains("headphones"))
                assertEquals("Unqualified speaker capture must not send PCM", 0, peer.audioBytes.get())
            } finally {
                instrumentation.runOnMainSync { controller.dispose() }
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun webRtcNativeClassifierLoads() {
        VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320, Mode.VERY_AGGRESSIVE).use { classifier ->
            assertFalse("Digital silence must not classify as speech", classifier.isSpeech(ShortArray(320)))
        }
    }
}

/** A actual loopback protocol endpoint: receives masked Android frames; never contacts upstream. */
private class LocalGatewayPeer(private val voice: Boolean) : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val url = "ws://127.0.0.1:${server.localPort}/v1/asr"
    val audioReceived = CountDownLatch(1)
    val finishReceived = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val audioBytes = AtomicInteger()
    val failure = AtomicReference<Throwable?>()
    @Volatile private var client: Socket? = null
    @Volatile private var disposed = false
    private val worker = thread(name = "gateway-runtime-peer", isDaemon = true) {
        try {
            server.accept().use { socket ->
                client = socket
                socket.soTimeout = 15_000
                val input = BufferedInputStream(socket.getInputStream())
                val headers = readHeaders(input)
                val key = headers.lineSequence().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
                socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                val start = readFrame(input) ?: error("Missing gateway start")
                check(start.first == 1)
                val startText = start.second.toString(Charsets.UTF_8)
                check(startText.contains("\"type\":\"start\"") && startText.contains(if (voice) "\"voice\"" else "\"dictation\""))
                send(socket, 1, ("{\"type\":\"ready\",\"request_id\":\"runtime\",\"format\":\"pcm_s16le\",\"sample_rate\":16000,\"channels\":1,\"max_chunk_bytes\":6400" +
                    (if (voice) ",\"mode\":\"voice\"" else "") + "}").toByteArray(Charsets.UTF_8))
                var interimSent = false
                while (true) {
                    val frame = readFrame(input) ?: break
                    when (frame.first) {
                        2 -> {
                            check(frame.second.isNotEmpty() && frame.second.size <= 6400 && frame.second.size % 2 == 0)
                            audioBytes.addAndGet(frame.second.size)
                            audioReceived.countDown()
                            if (!interimSent) {
                                send(socket, 1, "{\"type\":\"interim\",\"text\":\"gateway interim fixture\"}".toByteArray())
                                interimSent = true
                            }
                        }
                        1 -> {
                            check(frame.second.toString(Charsets.UTF_8) == "{\"type\":\"finish\"}")
                            finishReceived.countDown()
                            send(socket, 1, "{\"type\":\"final\",\"text\":\"gateway final fixture\"}".toByteArray())
                        }
                        8 -> { send(socket, 8, byteArrayOf(3, -24)); break }
                        9 -> send(socket, 10, frame.second)
                        else -> error("Unexpected client opcode ${frame.first}")
                    }
                }
            }
        } catch (error: Throwable) {
            if (!disposed && audioBytes.get() > 0) failure.set(error)
        } finally { closed.countDown() }
    }

    private fun readHeaders(input: BufferedInputStream): String {
        val output = ByteArrayOutputStream()
        var tail = 0
        while (output.size() < 8192) {
            val byte = input.read().also { check(it >= 0) }
            output.write(byte)
            tail = (tail shl 8) or byte
            if (tail == 0x0d0a0d0a) return output.toString(Charsets.US_ASCII.name())
        }
        error("WebSocket headers exceeded limit")
    }

    private fun readFrame(input: BufferedInputStream): Pair<Int, ByteArray>? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read().also { check(it >= 0 && it and 128 != 0) { "Client frames must be masked" } }
        var length = second and 127
        if (length == 126) length = (input.read() shl 8) or input.read()
        check(length in 0..8192) { "Unexpected client frame size" }
        val mask = ByteArray(4).also { readExact(input, it) }
        val payload = ByteArray(length).also { readExact(input, it) }
        for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        return (first and 15) to payload
    }

    private fun readExact(input: BufferedInputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            check(count > 0) { "Truncated WebSocket frame" }
            offset += count
        }
    }

    private fun send(socket: Socket, opcode: Int, payload: ByteArray) {
        val output = socket.getOutputStream()
        output.write(128 or opcode)
        if (payload.size < 126) output.write(payload.size) else {
            output.write(126); output.write(payload.size ushr 8); output.write(payload.size and 255)
        }
        output.write(payload)
        output.flush()
    }

    override fun close() {
        disposed = true
        client?.close()
        server.close()
        worker.join(1000)
    }
}
