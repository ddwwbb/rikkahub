package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGenerationInvariantTest {
    @Test fun `unfinished emphasis and parentheses do not leak partial format or excluded text`() {
        assertEquals(0, stableSpeechBoundary("**强调还没结束。"))
        assertEquals(0, stableSpeechBoundary("（不朗读的备注。"))
        assertEquals("**完整强调。**\n".length, stableSpeechBoundary("**完整强调。**\n还有"))
        assertTrue(stableSpeechBoundary("正常第一句。未完成[链接") > 0)
    }
}
