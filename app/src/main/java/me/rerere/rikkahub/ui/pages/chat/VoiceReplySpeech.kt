package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.removeBracketedContent
import me.rerere.rikkahub.utils.stripMarkdown
import kotlin.uuid.Uuid

/** Only committed text may leave this buffer; later snapshots cannot retract spoken words. */
internal class VoiceReplySpeech(
    private val waitForFinish: Boolean,
    private val quotedOnly: Boolean,
    private val outsideBracketsOnly: Boolean,
) {
    private data class Cursor(var committed: String = "", var offset: Int = 0)
    private val cursors = mutableMapOf<Uuid, Cursor>()

    fun append(messages: List<UIMessage>, finished: Boolean): List<String> = buildList {
        for (message in messages) {
            if (message.role != MessageRole.ASSISTANT) continue
            val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            val cursor = cursors.getOrPut(message.id) { Cursor() }
            check(text.startsWith(cursor.committed)) {
                "回答改写了已经播报的正文，语音已停止；请查看文字回复。"
            }
            if (waitForFinish && !finished) continue
            val finalText = finished || message.finishedAt != null
            val tail = text.substring(cursor.offset)
            val end = if (finalText) tail.length else stableSpeechBoundary(tail)
            if (end == 0) continue
            val raw = tail.substring(0, end)
            cursor.offset += end
            cursor.committed = text.substring(0, cursor.offset)
            var spoken = raw
            if (quotedOnly) spoken = spoken.extractQuotedContentAsText() ?: spoken
            if (outsideBracketsOnly) spoken = spoken.removeBracketedContent() ?: spoken
            spoken = spoken.stripMarkdown()
            if (spoken.isNotBlank()) add(spoken)
        }
    }
}

/** Sentence boundaries inside unfinished Markdown must not expose URLs or code to TTS. */
internal fun stableSpeechBoundary(text: String): Int {
    var index = 0
    var boundary = 0
    var bracketDepth = 0
    var parenDepth = 0
    var codeDelimiter = ""
    var emphasis = ""
    while (index < text.length) {
        val char = text[index]
        if (char == '`') {
            var count = 1
            while (index + count < text.length && text[index + count] == '`') count++
            val delimiter = "`".repeat(count)
            if (codeDelimiter.isEmpty()) codeDelimiter = delimiter
            else if (codeDelimiter == delimiter) codeDelimiter = ""
            index += count
            continue
        }
        if (codeDelimiter.isNotEmpty()) { index++; continue }
        if (char == '\\') { index += 2; continue }
        when (char) {
            '[' -> bracketDepth++
            ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
            '(', '（' -> parenDepth++
            ')', '）' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
            '*', '_' -> {
                var count = 1
                while (index + count < text.length && text[index + count] == char) count++
                val delimiter = char.toString().repeat(count)
                val listMarker = char == '*' && (index == 0 || text[index - 1] == '\n') &&
                    text.getOrNull(index + count) == ' '
                val insideWord = char == '_' && text.getOrNull(index - 1)?.isLetterOrDigit() == true
                if (!listMarker && !insideWord) {
                    if (emphasis.isEmpty()) emphasis = delimiter
                    else if (emphasis == delimiter) emphasis = ""
                }
                index += count
                continue
            }
        }
        if (bracketDepth == 0 && parenDepth == 0 && emphasis.isEmpty() &&
            char in "。！？!?；;\n"
        ) boundary = index + 1
        // A trailing dot may be a decimal or URL; only commit it with following whitespace.
        if (char == '.' && text.getOrNull(index + 1)?.isWhitespace() == true &&
            bracketDepth == 0 && parenDepth == 0 && emphasis.isEmpty()
        ) boundary = index + 1
        index++
    }
    return boundary
}
