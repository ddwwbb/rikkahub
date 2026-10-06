package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackTransformerTest {
    @Test
    fun `ordinary messages receive no voice context`() {
        val messages = listOf(UIMessage.user("hello"), UIMessage.assistant("answer"))
        assertSame(messages, withVoicePlaybackContext(messages))
    }

    @Test
    fun `interrupted answer gets synthetic context without rewriting user text`() {
        val answer = UIMessage.assistant("stored complete answer").copy(voicePlaybackInterrupted = true)
        val user = UIMessage.user("next utterance")
        val messages = withVoicePlaybackContext(listOf(answer, user))
        assertEquals(MessageRole.SYSTEM, messages.first().role)
        assertTrue(messages.first().isSynthetic)
        assertTrue(messages.first().toText().contains("may not have heard"))
        assertTrue(messages.first().toText().contains(answer.id.toString()))
        assertSame(answer, messages[1])
        assertSame(user, messages[2])
        assertEquals("next utterance", user.toText())
        assertEquals("stored complete answer", answer.toText())
    }
}
