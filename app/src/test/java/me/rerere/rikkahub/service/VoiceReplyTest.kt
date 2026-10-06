package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationControl
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceReplyTest {
    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        val errors = mutableListOf<Exception>()

        fun submit(text: String, generate: suspend (VoiceReply, GenerationControl) -> Unit): VoiceReply {
            val reply = VoiceReply()
            val control = GenerationControl()
            val previous = session.getJob()
            session.replaceVoiceReply(reply, control)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                executeVoiceTurn(session, reply, control, previous, "approval required", {
                    val current = session.state.value
                    session.updateConversation(current.copy(messageNodes = current.messageNodes + UIMessage.user(text).toMessageNode()))
                }, { generate(reply, control) }, { errors.add(it) })
            }
            session.setJob(job, cancelPrevious = false)
            return reply
        }

        fun close() { session.cleanup(); scope.cancel() }
    }

    @Test
    fun `incremental snapshots exclude previous answers and freeze after supersession`() {
        val previous = UIMessage.assistant("old answer")
        val reply = VoiceReply()
        reply.start(listOf(previous))
        val new = UIMessage.assistant("first")
        reply.publish(listOf(previous, UIMessage.user("question"), new))
        assertEquals(listOf(new), reply.updates.value.messages)
        reply.publish(listOf(previous, new.copy(parts = listOf(UIMessagePart.Text("first second")))))
        assertEquals("first second", reply.updates.value.messages.single().toText())
        reply.supersede()
        reply.publish(listOf(UIMessage.assistant("late foreign answer")))
        assertEquals("first second", reply.updates.value.messages.single().toText())
        assertTrue(reply.updates.value.finished)
        assertTrue(reply.updates.value.isSuperseded)
        assertNull(reply.updates.value.error)
    }

    @Test
    fun `continuous utterances wait for executing tool and preserve every input and result`() = runBlocking {
        val h = Harness()
        try {
            val toolDone = CompletableDeferred<Unit>()
            val toolStarted = CompletableDeferred<Unit>()
            val generated = mutableListOf<String>()
            val first = h.submit("first") { reply, control ->
                generated.add("first")
                assertTrue(control.beginTools())
                toolStarted.complete(Unit)
                toolDone.await()
                val toolMessage = UIMessage(role = MessageRole.ASSISTANT, voiceReplyId = reply.id, parts = listOf(
                    UIMessagePart.Tool("write-1", "write_file", "{}", listOf(UIMessagePart.Text("file updated")))
                ))
                val current = h.session.state.value
                h.session.updateConversation(current.copy(messageNodes = current.messageNodes + toolMessage.toMessageNode()))
                reply.publish(h.session.state.value.currentMessages)
                // The collector's real result commit precedes releasing the guard.
                assertFalse(control.finishTools())
            }
            toolStarted.await()
            val second = h.submit("second") { _, _ -> generated.add("second") }
            val third = h.submit("third") { reply, _ ->
                generated.add("third")
                val current = h.session.state.value
                val answer = UIMessage.assistant("latest answer").copy(voiceReplyId = reply.id)
                h.session.updateConversation(current.copy(messageNodes = current.messageNodes + answer.toMessageNode()))
                reply.publish(h.session.state.value.currentMessages)
            }
            assertTrue(first.updates.value.isSuperseded)
            assertTrue(second.updates.value.isSuperseded)
            assertFalse(third.updates.value.finished)
            assertEquals(listOf("first"), generated)
            toolDone.complete(Unit)
            h.session.getJob()?.join()
            assertEquals(listOf("first", "third"), generated)
            assertEquals(listOf("first", "second", "third"), h.session.state.value.currentMessages.filter { it.role == MessageRole.USER }.map { it.toText() })
            assertEquals("file updated", (h.session.state.value.currentMessages.flatMap { it.getTools() }.single().output.single() as UIMessagePart.Text).text)
            assertEquals("latest answer", third.updates.value.messages.single().toText())
            assertFalse(h.session.messageQueue.state.value.paused)
            assertTrue(h.errors.isEmpty())
        } finally { h.close() }
    }

    @Test
    fun `model interruption cancels request without pausing queue or losing partial text`() = runBlocking {
        val h = Harness()
        try {
            val first = h.submit("first") { reply, _ ->
                val current = h.session.state.value
                val partial = UIMessage.assistant("partial").copy(voiceReplyId = reply.id)
                h.session.updateConversation(current.copy(messageNodes = current.messageNodes + partial.toMessageNode()))
                reply.publish(h.session.state.value.currentMessages)
                awaitCancellation()
            }
            val second = h.submit("second") { _, _ -> }
            h.session.getJob()?.join()
            assertTrue(first.updates.value.isSuperseded)
            assertTrue(second.updates.value.finished)
            assertEquals(listOf("first", "partial", "second"), h.session.state.value.currentMessages.map { it.toText() })
            assertFalse(h.session.messageQueue.state.value.paused)
            assertTrue(h.errors.isEmpty())
        } finally { h.close() }
    }

    @Test
    fun `pending approval reports error after preserving submitted utterance`() = runBlocking {
        val h = Harness()
        try {
            val pending = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool("pending-1", "write_file", "{}", approvalState = ToolApprovalState.Pending)
            ))
            h.session.updateConversation(h.session.state.value.copy(messageNodes = listOf(pending.toMessageNode())))
            var generated = false
            val reply = h.submit("do not lose this") { _, _ -> generated = true }
            h.session.getJob()?.join()
            assertEquals("approval required", reply.updates.value.error)
            assertTrue(reply.updates.value.finished)
            assertFalse(generated)
            assertEquals("do not lose this", h.session.state.value.currentMessages.last().toText())
            assertTrue(h.session.state.value.currentMessages.first().getTools().single().isPending)
        } finally { h.close() }
    }

    @Test
    fun `generation failure resolves reply and retains user and partial answer`() = runBlocking {
        val h = Harness()
        try {
            val reply = h.submit("question") { _, _ ->
                val current = h.session.state.value
                h.session.updateConversation(current.copy(messageNodes = current.messageNodes + UIMessage.assistant("partial").toMessageNode()))
                throw IllegalStateException("upstream disconnected")
            }
            h.session.getJob()?.join()
            assertEquals("upstream disconnected", reply.updates.value.error)
            assertTrue(reply.updates.value.finished)
            assertEquals(listOf("question", "partial"), h.session.state.value.currentMessages.map { it.toText() })
            assertEquals(1, h.errors.size)
            assertFalse(h.session.messageQueue.state.value.paused)
        } finally { h.close() }
    }

    @Test
    fun `tool admission and interruption are mutually exclusive`() {
        val control = GenerationControl()
        val job = Job()
        control.attach(job)
        control.interrupt()
        assertTrue(job.isCancelled)
        assertFalse(control.beginTools())
        val tools = GenerationControl()
        val toolJob = Job()
        tools.attach(toolJob)
        assertTrue(tools.beginTools())
        tools.interrupt()
        assertFalse(toolJob.isCancelled)
        assertFalse(tools.finishTools())
        assertFalse(tools.beginTools())
    }
}
