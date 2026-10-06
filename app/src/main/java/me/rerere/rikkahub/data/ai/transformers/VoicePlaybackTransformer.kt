package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage

/** Adds model-only context without changing stored answers or the user's utterance. */
object VoicePlaybackTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        withVoicePlaybackContext(messages)
}

internal fun withVoicePlaybackContext(messages: List<UIMessage>): List<UIMessage> {
    val interrupted = messages.filter { it.voicePlaybackInterrupted }
    if (interrupted.isEmpty()) return messages
    val notice = UIMessage.system(
        "Voice playback was interrupted for assistant message(s): " +
            interrupted.joinToString(", ") { it.id.toString() } +
            ". The user may not have heard those answers completely. The stored text is not " +
            "an exact record of what was heard. Address all subsequent user utterances; do not " +
            "assume the interrupted answer was fully heard."
    ).copy(isSynthetic = true)
    return listOf(notice) + messages
}
