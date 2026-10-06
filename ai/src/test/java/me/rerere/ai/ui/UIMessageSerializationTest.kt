package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UIMessageSerializationTest {

    @Test
    fun `synthetic marker is not serialized`() {
        val message = UIMessage.user("internal").copy(isSynthetic = true)

        val encoded = Json.encodeToString(message)
        val decoded = Json.decodeFromString<UIMessage>(encoded)

        assertTrue(message.isSynthetic)
        assertFalse(encoded.contains("isSynthetic"))
        assertFalse(decoded.isSynthetic)
    }

    @Test
    fun `voice interruption metadata persists without changing display text`() {
        val replyId = kotlin.uuid.Uuid.random()
        val message = UIMessage.assistant("original answer").copy(
            voiceReplyId = replyId,
            voicePlaybackInterrupted = true,
        )
        val decoded = Json.decodeFromString<UIMessage>(Json.encodeToString(message))
        assertTrue(decoded.voicePlaybackInterrupted)
        org.junit.Assert.assertEquals(replyId, decoded.voiceReplyId)
        org.junit.Assert.assertEquals("original answer", decoded.toText())
        org.junit.Assert.assertEquals("[ASSISTANT]: original answer", decoded.summaryAsText())
    }
}
