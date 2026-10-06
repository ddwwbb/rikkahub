package me.rerere.asr.providers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.DuplexASRController
import me.rerere.asr.DuplexASREvent
import me.rerere.asr.DuplexEventQueue
import me.rerere.asr.VOLCENGINE_ASR_WEBSOCKET_URL
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import kotlin.uuid.Uuid

fun createDuplexAsr(context: Context, client: OkHttpClient, provider: ASRProviderSetting): DuplexASRController {
    require(provider.supportsServerVadVoiceMode) { "Selected ASR has no server sentence detection" }
    when (provider) {
        is ASRProviderSetting.OpenAIRealtime -> require(provider.apiKey.isNotBlank()) { "Configure the ASR API key" }
        is ASRProviderSetting.DashScope -> require(provider.apiKey.isNotBlank()) { "Configure the ASR API key" }
        is ASRProviderSetting.Volcengine -> {
            require(provider.apiKey.isNotBlank()) { "Configure the ASR API key" }
            require(provider.websocketUrl.trim().trimEnd('/') != "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel") {
                "Update the Volcengine ASR URL to $VOLCENGINE_ASR_WEBSOCKET_URL"
            }
        }
        is ASRProviderSetting.VoiceGateway -> require(provider.websocketUrl.isNotBlank()) { "Configure the Voice Gateway WebSocket URL" }
        else -> error("Selected ASR has no server sentence detection")
    }
    return ContinuousDuplexASRController(context.applicationContext, client, provider, duplex = true)
}

/** Dictionary input retains explicit stop/finish and never enables conversational playback. */
class VoiceGatewayASRController(context: Context, client: OkHttpClient, provider: ASRProviderSetting.VoiceGateway) :
    ASRController by ContinuousDuplexASRController(context.applicationContext, client, provider, duplex = false)

internal class ContinuousDuplexASRController(
    private val context: Context,
    private val client: OkHttpClient,
    private val provider: ASRProviderSetting,
    private val duplex: Boolean,
) : DuplexASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ASRState(isAvailable = true))
    override val state = mutableState.asStateFlow()
    private val eventQueue = DuplexEventQueue()
    override val events = eventQueue.events
    private val capture = ContinuousPcmCapture(context)
    private var protocol = DuplexAsrProtocol()
    private var socket: WebSocket? = null
    private var recording: Job? = null
    private var finishing: Job? = null
    private var callback: ((String) -> Unit)? = null
    private var disposed = false

    @Synchronized
    override fun start(onTranscriptChange: (String) -> Unit) {
        if (disposed || state.value.isRecording) return
        if (duplex && state.value.status == ASRStatus.Error) return
        if (recording?.isCompleted == false) return
        recording = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is required")
            return
        }
        callback = onTranscriptChange
        protocol = DuplexAsrProtocol()
        mutableState.value = ASRState(status = ASRStatus.Connecting, isAvailable = true)
        try {
            socket = client.newWebSocket(request(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(this@ContinuousDuplexASRController) {
                    if (socket !== webSocket || state.value.status != ASRStatus.Connecting) { webSocket.cancel(); return@synchronized }
                    try {
                        when (val setting = provider) {
                            is ASRProviderSetting.OpenAIRealtime -> sendText(webSocket, setting.sessionUpdateEvent().toString())
                            is ASRProviderSetting.DashScope -> sendText(webSocket, setting.sessionUpdateEvent().toString())
                            is ASRProviderSetting.Volcengine -> sendBinary(webSocket, VolcengineASRProtocol.initialFrame(setting).toByteString())
                            is ASRProviderSetting.VoiceGateway -> sendText(webSocket, JSONObject().put("type", "start")
                                .put("mode", if (duplex) "voice" else "dictation").toString())
                            else -> error("Unsupported continuous ASR")
                        }
                        if (provider !is ASRProviderSetting.VoiceGateway) beginCapture(webSocket)
                    } catch (error: Exception) { fail(error.message ?: "Unable to start ASR") }
                }
                override fun onMessage(webSocket: WebSocket, text: String) = synchronized(this@ContinuousDuplexASRController) {
                    if (socket !== webSocket) return@synchronized
                    try {
                        val event = Json.parseToJsonElement(text).jsonObject
                        val type = event.string("type")
                        when {
                            provider is ASRProviderSetting.VoiceGateway && type == "ready" -> {
                                check(event.string("sample_rate") == "16000" && event.string("channels") == "1") { "Gateway PCM format mismatch" }
                                if (duplex) check(event.string("mode") == "voice") { "Gateway does not support voice mode" }
                                beginCapture(webSocket)
                            }
                            type == "finished" || type == "session.finished" -> {
                                check(state.value.status == ASRStatus.Stopping) { "ASR session ended while microphone was active" }
                                completeStop(webSocket)
                            }
                            provider is ASRProviderSetting.VoiceGateway -> {
                                publish(protocol.gateway(text, dictation = !duplex))
                                if (!duplex && type == "final" && socket === webSocket) completeStop(webSocket)
                            }
                            else -> publish(protocol.realtime(text))
                        }
                    } catch (error: Exception) { fail(error.message ?: "Invalid ASR event") }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(this@ContinuousDuplexASRController) {
                    if (socket !== webSocket) return@synchronized
                    try {
                        check(provider is ASRProviderSetting.Volcengine) { "Unexpected binary ASR event" }
                        val result = VolcengineASRProtocol.decode(bytes.toByteArray())
                        result.error?.let { error(it) }
                        publish(protocol.volc(result.result))
                        if (result.isLast) {
                            check(state.value.status == ASRStatus.Stopping) { "Volcengine ended the stream while microphone was active" }
                            completeStop(webSocket)
                        }
                    } catch (error: Exception) { fail(error.message ?: "Invalid ASR response") }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(this@ContinuousDuplexASRController) {
                    if (socket === webSocket) fail(t.message ?: "ASR connection failed")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@ContinuousDuplexASRController) {
                    if (socket !== webSocket) return@synchronized
                    if (state.value.status == ASRStatus.Stopping && provider is ASRProviderSetting.OpenAIRealtime) completeStop(webSocket)
                    else fail("ASR connection closed before completion (code $code)")
                }
            })
        } catch (error: Exception) { fail(error.message ?: "Invalid ASR configuration") }
    }

    private fun request(): Request {
        val builder = Request.Builder()
        when (val setting = provider) {
            is ASRProviderSetting.OpenAIRealtime -> builder.url(setting.websocketEndpoint()).header("Authorization", "Bearer ${setting.apiKey}")
            is ASRProviderSetting.DashScope -> builder.url(setting.websocketEndpoint()).header("Authorization", "Bearer ${setting.apiKey}")
            is ASRProviderSetting.Volcengine -> builder.url(setting.websocketUrl).header("X-Api-Key", setting.apiKey)
                .header("X-Api-Resource-Id", setting.resourceId).header("X-Api-Request-Id", Uuid.random().toString())
            is ASRProviderSetting.VoiceGateway -> {
                builder.url(setting.websocketUrl)
                if (setting.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${setting.apiKey}")
            }
            else -> error("Unsupported continuous ASR")
        }
        return builder.build()
    }

    private fun beginCapture(webSocket: WebSocket) {
        check(recording == null) { "ASR sent duplicate readiness" }
        mutableState.update { it.copy(status = ASRStatus.Listening) }
        val sampleRate = when (val setting = provider) {
            is ASRProviderSetting.OpenAIRealtime -> setting.sampleRate
            is ASRProviderSetting.DashScope -> setting.sampleRate
            else -> 16000
        }
        recording = scope.launch(Dispatchers.IO) {
            try {
                capture.capture(sampleRate, duplex, duplex && (provider is ASRProviderSetting.Volcengine || provider is ASRProviderSetting.VoiceGateway)) { bytes, count, speech ->
                    synchronized(this@ContinuousDuplexASRController) {
                        if (socket === webSocket && state.value.status == ASRStatus.Listening) {
                            speech?.let {
                                publish(protocol.acousticActivity(it))
                                check(socket === webSocket) { "ASR stopped while publishing onset" }
                            }
                            mutableState.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(calculateRmsAmplitude(bytes, count))) }
                            when (provider) {
                                is ASRProviderSetting.Volcengine -> sendBinary(webSocket, VolcengineASRProtocol.audioFrame(bytes).toByteString())
                                is ASRProviderSetting.VoiceGateway -> sendBinary(webSocket, bytes.toByteString(0, count))
                                else -> sendText(webSocket, JSONObject().put("type", "input_audio_buffer.append")
                                    .put("audio", Base64.encodeToString(bytes, 0, count, Base64.NO_WRAP)).toString())
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                synchronized(this@ContinuousDuplexASRController) {
                    if (socket === webSocket && state.value.status == ASRStatus.Listening) fail(error.message ?: "Microphone capture failed")
                }
            }
        }
    }

    private fun sendText(webSocket: WebSocket, text: String) {
        check(webSocket.queueSize() + text.length * 3L <= 128_000) { "ASR network audio buffer overflow; session stopped without dropping audio" }
        check(webSocket.send(text)) { "ASR refused audio or control message" }
    }
    private fun sendBinary(webSocket: WebSocket, bytes: ByteString) {
        check(webSocket.queueSize() + bytes.size <= 128_000) { "ASR network audio buffer overflow; session stopped without dropping audio" }
        check(webSocket.send(bytes)) { "ASR refused audio or control message" }
    }

    private fun publish(events: List<DuplexASREvent>) {
        for (event in events) {
            if (duplex && !eventQueue.offer(event)) { fail("ASR event buffer overflow; session stopped without silently losing utterances"); return }
            when (event) {
                is DuplexASREvent.Transcript -> mutableState.update { it.copy(transcript = event.text) }
                is DuplexASREvent.Final -> mutableState.update { it.copy(transcript = event.text) }
                else -> Unit
            }
            if (event is DuplexASREvent.Transcript || event is DuplexASREvent.Final) {
                val text = state.value.transcript
                scope.launch { callback?.invoke(text) }
            }
        }
    }

    @Synchronized
    override fun pauseCapture() { stop() }

    @Synchronized
    override fun stop() {
        if (state.value.status == ASRStatus.Stopping) return
        val current = socket ?: return
        if (state.value.status == ASRStatus.Connecting) {
            socket = null
            current.cancel()
            mutableState.update { it.copy(status = ASRStatus.Idle) }
            return
        }
        mutableState.update { it.copy(status = ASRStatus.Stopping) }
        capture.stop()
        recording?.cancel()
        val recorderJob = recording
        finishing = scope.launch {
            recorderJob?.join()
            synchronized(this@ContinuousDuplexASRController) {
                if (socket !== current) return@synchronized
                try {
                    when (provider) {
                        is ASRProviderSetting.Volcengine -> sendBinary(current, VolcengineASRProtocol.audioFrame(ByteArray(0), last = true).toByteString())
                        is ASRProviderSetting.VoiceGateway -> sendText(current, "{\"type\":\"finish\"}")
                        is ASRProviderSetting.DashScope -> sendText(current, "{\"type\":\"session.finish\",\"event_id\":\"finish\"}")
                        is ASRProviderSetting.OpenAIRealtime -> current.close(1000, "voice stopped")
                        else -> error("Unsupported ASR")
                    }
                } catch (error: Exception) { fail(error.message ?: "ASR finish failed") }
            }
            delay(10_000)
            synchronized(this@ContinuousDuplexASRController) {
                if (socket === current) fail("Timed out waiting for ASR completion")
            }
        }
    }

    private fun completeStop(current: WebSocket) {
        socket = null
        finishing?.cancel()
        recording = null
        capture.stop()
        current.close(1000, "recognition finished")
        mutableState.update { it.copy(status = ASRStatus.Idle) }
    }

    private fun fail(message: String) {
        capture.stop()
        recording?.cancel()
        finishing?.cancel()
        val current = socket
        socket = null
        current?.cancel()
        mutableState.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
        eventQueue.fail(message)
        if (duplex) disposed = true
    }

    @Synchronized
    override fun dispose() {
        disposed = true
        capture.stop()
        socket?.cancel()
        socket = null
        scope.cancel()
        eventQueue.close()
    }
}
