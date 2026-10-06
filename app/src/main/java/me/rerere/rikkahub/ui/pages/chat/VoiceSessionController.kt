package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.asr.ASRStatus
import me.rerere.asr.DuplexASRController
import me.rerere.asr.DuplexASREvent
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.VoiceReply
import me.rerere.rikkahub.service.VoiceReplyState
import kotlin.uuid.Uuid

enum class VoicePhase { Off, Connecting, Listening, Transcribing, Speaking, Error }

data class VoiceSessionState(
    val phase: VoicePhase = VoicePhase.Off,
    val transcript: String = "",
    val error: String? = null,
    val pendingReplies: Int = 0,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
) {
    val isActive: Boolean get() = phase != VoicePhase.Off && phase != VoicePhase.Error
}

/** A speech onset stops sound; only a final nonempty utterance changes the chat turn. */
class VoiceSessionController(
    private val scope: CoroutineScope,
    private val getString: (Int) -> String,
    private val submitMessage: suspend (String) -> VoiceReply,
    private val markInterrupted: (Uuid) -> Unit,
) {
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var stopPlayback: (() -> Unit)? = null

    private sealed interface Event {
        data class Speech(val event: DuplexASREvent) : Event
        data class Reply(val epoch: Long, val value: VoiceReplyState) : Event
        data class Playing(val epoch: Long, val value: Boolean) : Event
        data class Failed(val error: Exception) : Event
    }
    private data class Audio(val epoch: Long, val text: String)

    fun start(
        createAsr: () -> DuplexASRController,
        speak: suspend (String) -> Unit,
        stopSpeaking: () -> Unit,
        waitForReplyFinish: Boolean = false,
        quotedOnly: Boolean = false,
        outsideBracketsOnly: Boolean = false,
        openAudioSession: () -> AutoCloseable = { AutoCloseable {} },
    ) {
        if (job?.isActive == true) return
        val previous = job
        mutableState.value = VoiceSessionState(phase = VoicePhase.Connecting)
        job = scope.launch {
            previous?.join()
            stopPlayback = stopSpeaking
            var audioSession: AutoCloseable? = null
            try {
                stopSpeaking()
                audioSession = openAudioSession()
                runSession(createAsr, speak, stopSpeaking, waitForReplyFinish, quotedOnly, outsideBracketsOnly)
            } catch (error: Exception) {
                if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                mutableState.value = VoiceSessionState(
                    phase = VoicePhase.Error,
                    error = if (error is TimeoutCancellationException) {
                        "语音识别超时，请手动重新开启语音。"
                    } else error.message ?: getString(R.string.chat_page_voice_failed),
                )
            } finally {
                stopSpeaking()
                audioSession?.close()
                stopPlayback = null
            }
        }
    }

    private suspend fun runSession(
        createAsr: () -> DuplexASRController,
        speak: suspend (String) -> Unit,
        stopSpeaking: () -> Unit,
        waitForReplyFinish: Boolean,
        quotedOnly: Boolean,
        outsideBracketsOnly: Boolean,
    ) = coroutineScope {
        val events = Channel<Event>(32)
        val audio = Channel<Audio>(32)
        val asr = createAsr()
        var epoch = 0L
        var reply: VoiceReply? = null
        var observer: Job? = null
        var playback: Job? = null
        var speech = VoiceReplySpeech(waitForReplyFinish, quotedOnly, outsideBracketsOnly)
        var suppressed = false
        var acousticActive = false
        var unplayedPieces = 0
        val talking = mutableSetOf<String>()
        val awaitingFinal = mutableMapOf<String, Job>()
        val submitted = LinkedHashSet<String>()
        var acousticGeneration = 0L
        val endedAcousticGeneration = mutableMapOf<String, Long>()
        var awaitingAcousticTail = false
        var latestReplyState: VoiceReplyState? = null

        fun enqueueSpeech(update: VoiceReplyState) {
            for (text in speech.append(update.messages, update.finished)) {
                unplayedPieces++
                check(audio.trySend(Audio(epoch, text)).isSuccess) {
                    "待播内容超过语音缓冲上限，语音已停止；文字回复仍保留。"
                }
            }
        }

        fun launchPlayback(): Job = launch {
            try {
                for (piece in audio) {
                    if (piece.epoch != epoch || suppressed) continue
                    events.send(Event.Playing(piece.epoch, true))
                    speak(piece.text)
                    unplayedPieces--
                    events.send(Event.Playing(piece.epoch, false))
                }
            } catch (error: Exception) {
                if (error is CancellationException && !isActive) throw error
                events.send(Event.Failed(error))
            }
        }
        suspend fun interrupt() {
            suppressed = true
            awaitingAcousticTail = false
            stopSpeaking()
            playback?.cancelAndJoin()
            while (audio.tryReceive().isSuccess) { /* Discard every unplayed old fragment. */ }
            if (unplayedPieces > 0 || mutableState.value.pendingReplies > 0) {
                reply?.let { markInterrupted(it.id) }
            }
            unplayedPieces = 0
            mutableState.update { it.copy(isSpeaking = false, phase = VoicePhase.Listening) }
            playback = launchPlayback()
        }
        try {
            launch {
                try { asr.events.collect { events.send(Event.Speech(it)) } }
                catch (error: Exception) {
                    if (error is CancellationException && !isActive) throw error
                    events.send(Event.Failed(error))
                }
            }
            asr.start {}
            withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    it.status != ASRStatus.Connecting
                }.also { check(it.status == ASRStatus.Listening) { "无法开始录音。" } }
            }
            mutableState.update { it.copy(phase = VoicePhase.Listening, isListening = true) }
            launch {
                asr.state.collect {
                    if (it.errorMessage != null) events.send(Event.Failed(IllegalStateException(it.errorMessage)))
                    else if (it.status == ASRStatus.Idle || it.status == ASRStatus.Error) {
                        events.send(Event.Failed(IllegalStateException("语音识别连接已结束，请重新开启语音。")))
                    }
                }
            }
            playback = launchPlayback()
            while (isActive) {
                when (val event = events.receive()) {
                    is Event.Speech -> when (val input = event.event) {
                        is DuplexASREvent.AcousticActivity -> {
                            acousticActive = input.active
                            if (input.active) {
                                acousticGeneration++
                                interrupt()
                            } else if (awaitingAcousticTail && talking.isEmpty()) {
                                awaitingAcousticTail = false
                                suppressed = false
                                latestReplyState?.takeUnless { it.isSuperseded || it.error != null }?.let(::enqueueSpeech)
                            }
                        }
                        is DuplexASREvent.Started -> if (input.id !in submitted) {
                            if (talking.add(input.id)) interrupt()
                            mutableState.update { it.copy(phase = VoicePhase.Listening, transcript = "") }
                        }
                        is DuplexASREvent.Transcript -> if (input.id !in submitted) {
                            mutableState.update { it.copy(transcript = input.text) }
                        }
                        is DuplexASREvent.Ended -> if (input.id !in submitted && input.id !in awaitingFinal) {
                            talking.remove(input.id)
                            endedAcousticGeneration[input.id] = acousticGeneration
                            mutableState.update { it.copy(phase = VoicePhase.Transcribing) }
                            awaitingFinal[input.id] = launch {
                                delay(15_000)
                                events.send(Event.Failed(IllegalStateException("未收到最终转写，请重新开启语音。")))
                            }
                        }
                        is DuplexASREvent.Final -> {
                            awaitingFinal.remove(input.id)?.cancel()
                            val endedGeneration = endedAcousticGeneration.remove(input.id)
                            talking.remove(input.id)
                            if (!submitted.add(input.id)) continue
                            if (submitted.size > 128) submitted.remove(submitted.first())
                            mutableState.update { it.copy(transcript = input.text, phase = VoicePhase.Listening) }
                            if (input.text.isBlank()) continue
                            // Final can arrive without a separate onset; never let its predecessor keep talking.
                            interrupt()
                            observer?.cancelAndJoin()
                            epoch++
                            awaitingAcousticTail = acousticActive && talking.isEmpty() && endedGeneration == acousticGeneration
                            suppressed = talking.isNotEmpty() || acousticActive
                            latestReplyState = null
                            speech = VoiceReplySpeech(waitForReplyFinish, quotedOnly, outsideBracketsOnly)
                            reply = submitMessage(input.text)
                            mutableState.update { it.copy(pendingReplies = 1) }
                            val current = checkNotNull(reply)
                            val currentEpoch = epoch
                            observer = launch { current.updates.collect { events.send(Event.Reply(currentEpoch, it)) } }
                        }
                        is DuplexASREvent.Failed -> throw IllegalStateException(input.message)
                    }
                    is Event.Reply -> {
                        if (event.epoch != epoch) continue
                        val update = event.value
                        latestReplyState = update
                        check(update.error == null) { update.error.orEmpty() }
                        if (update.isSuperseded) { interrupt(); continue }
                        if (update.finished) mutableState.update { it.copy(pendingReplies = 0) }
                        if (suppressed || talking.isNotEmpty() || acousticActive) continue
                        enqueueSpeech(update)
                    }
                    is Event.Playing -> if (event.epoch == epoch && !suppressed) {
                        mutableState.update {
                            it.copy(isSpeaking = event.value, phase = if (event.value) VoicePhase.Speaking else VoicePhase.Listening)
                        }
                    }
                    is Event.Failed -> throw event.error
                }
            }
        } finally {
            stopSpeaking()
            observer?.cancel()
            playback?.cancel()
            awaitingFinal.values.forEach { it.cancel() }
            asr.dispose()
            audio.close()
            events.close()
        }
    }

    fun fail(message: String) {
        stop()
        mutableState.value = VoiceSessionState(phase = VoicePhase.Error, error = message)
    }

    fun stop() {
        stopPlayback?.invoke()
        job?.cancel()
        mutableState.value = VoiceSessionState()
    }
}
