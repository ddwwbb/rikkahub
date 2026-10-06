package me.rerere.asr

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow

/** A single consumer receives every utterance event; slow consumers fail rather than lose turns. */
interface DuplexASRController : ASRController {
    val events: Flow<DuplexASREvent>
}

sealed interface DuplexASREvent {
    /** Local acoustic activity after microphone preprocessing; never a server sentence boundary. */
    data class AcousticActivity(val active: Boolean) : DuplexASREvent
    data class Started(val id: String) : DuplexASREvent
    data class Transcript(val id: String, val text: String) : DuplexASREvent
    data class Ended(val id: String) : DuplexASREvent
    data class Final(val id: String, val text: String) : DuplexASREvent
    data class Failed(val message: String) : DuplexASREvent
}

internal class DuplexEventQueue(capacity: Int = 64) {
    private val channel = Channel<DuplexASREvent>(capacity)
    val events: Flow<DuplexASREvent> = channel.receiveAsFlow().catch { cause ->
        emit(DuplexASREvent.Failed(cause.message ?: "ASR event delivery failed"))
    }

    fun offer(event: DuplexASREvent): Boolean = channel.trySend(event).isSuccess
    fun fail(message: String) { channel.close(IllegalStateException(message)) }
    fun close() { channel.close() }
}
