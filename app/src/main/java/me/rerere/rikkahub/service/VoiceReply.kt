package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationControl
import me.rerere.rikkahub.data.ai.VoiceGenerationSuperseded
import kotlin.uuid.Uuid

/** An observer of one submitted utterance; its lifetime does not own chat generation. */
class VoiceReply(
    val id: Uuid = Uuid.random(),
    updates: StateFlow<VoiceReplyState>? = null,
) {
    private val state = MutableStateFlow(VoiceReplyState())
    val updates: StateFlow<VoiceReplyState> = updates ?: state.asStateFlow()
    internal var playbackInterrupted = false
        private set
    private var previousIds: Set<Uuid> = emptySet()

    @Synchronized
    internal fun start(messages: List<UIMessage>) {
        previousIds = messages.mapTo(mutableSetOf()) { it.id }
    }

    @Synchronized
    internal fun publish(messages: List<UIMessage>) {
        if (state.value.finished) return
        state.value = state.value.copy(messages = messages.filter {
            it.role == MessageRole.ASSISTANT && it.id !in previousIds
        })
    }

    @Synchronized
    internal fun finish(error: String? = null) {
        if (!state.value.finished) state.value = state.value.copy(finished = true, error = error)
    }

    @Synchronized
    internal fun supersede() {
        state.value = state.value.copy(finished = true, error = null, isSuperseded = true)
    }

    @Synchronized
    internal fun markPlaybackInterrupted() {
        playbackInterrupted = true
    }
}

data class VoiceReplyState(
    val messages: List<UIMessage> = emptyList(),
    val finished: Boolean = false,
    val error: String? = null,
    val isSuperseded: Boolean = false,
)

/** The same turn lifecycle is used by ChatService and deterministic service regressions. */
internal suspend fun executeVoiceTurn(
    session: ConversationSession,
    reply: VoiceReply,
    control: GenerationControl,
    previousJob: Job?,
    pendingApprovalError: String,
    saveInput: suspend () -> Unit,
    generate: suspend () -> Unit,
    onError: (Exception) -> Unit,
) {
    try {
        previousJob?.join()
        saveInput()
        if (!session.isLatestVoiceReply(reply)) return
        if (session.state.value.currentMessages.any { message -> message.getTools().any { it.isPending } }) {
            reply.finish(pendingApprovalError)
            return
        }
        control.attach(currentCoroutineContext()[Job]!!)
        reply.start(session.state.value.currentMessages)
        generate()
        reply.finish()
    } catch (error: Exception) {
        if (error is VoiceGenerationSuperseded) {
            reply.supersede()
        } else {
            reply.finish(error.message ?: error.javaClass.simpleName)
            if (error is CancellationException) throw error
            onError(error)
        }
    } finally {
        control.finish()
    }
}
