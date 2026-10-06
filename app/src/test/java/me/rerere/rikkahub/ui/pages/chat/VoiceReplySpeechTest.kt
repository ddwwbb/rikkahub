package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class VoiceReplySpeechTest {
    @Test fun `first sentence speaks before generation finishes without repeated prefix`() {
        val buffer = VoiceReplySpeech(false, false, false)
        val first = UIMessage.assistant("第一句。第二句还在生成")
        assertEquals(listOf("第一句。"), buffer.append(listOf(first), false))
        assertEquals(emptyList<String>(), buffer.append(listOf(first), false))
        val second = first.copy(parts = listOf(UIMessagePart.Text("第一句。第二句完成。")))
        assertEquals(listOf("第二句完成。"), buffer.append(listOf(second), true))
    }

    @Test fun `code and unfinished links never become spoken fragments`() {
        val buffer = VoiceReplySpeech(false, false, false)
        val first = UIMessage.assistant("```kotlin\nprintln(\"不要读。\")\n")
        assertTrue(buffer.append(listOf(first), false).isEmpty())
        val second = first.copy(parts = listOf(UIMessagePart.Text("```kotlin\nprintln(\"不要读。\")\n```\n[说明](https://example.com")))
        assertTrue(buffer.append(listOf(second), false).isEmpty())
        val third = first.copy(parts = listOf(UIMessagePart.Text("```kotlin\nprintln(\"不要读。\")\n```\n[说明](https://example.com)。")))
        assertEquals(listOf("说明。"), buffer.append(listOf(third), true))
    }

    @Test fun `global transformations and quote fallback wait for completed reply`() {
        val buffer = VoiceReplySpeech(true, true, false)
        val first = UIMessage.assistant("叙述第一句。")
        assertTrue(buffer.append(listOf(first), false).isEmpty())
        val second = first.copy(parts = listOf(UIMessagePart.Text("叙述第一句。她说：“只读这里。”")))
        assertEquals(listOf("只读这里。"), buffer.append(listOf(second), true))
    }

    @Test fun `rewrite of an unspoken tail is accepted but spoken prefix is not`() {
        val buffer = VoiceReplySpeech(false, false, false)
        val first = UIMessage.assistant("已经播报。还没说")
        assertEquals(listOf("已经播报。"), buffer.append(listOf(first), false))
        val second = first.copy(parts = listOf(UIMessagePart.Text("已经播报。修改尾部。")))
        assertEquals(listOf("修改尾部。"), buffer.append(listOf(second), false))
        val third = first.copy(parts = listOf(UIMessagePart.Text("重写了全部。")))
        assertThrows(IllegalStateException::class.java) { buffer.append(listOf(third), true) }
    }

    @Test fun `decimal numbers do not create premature fragments`() {
        assertEquals(0, stableSpeechBoundary("数值是3.14"))
        assertEquals("数值是3.14。".length, stableSpeechBoundary("数值是3.14。还有"))
    }
}
