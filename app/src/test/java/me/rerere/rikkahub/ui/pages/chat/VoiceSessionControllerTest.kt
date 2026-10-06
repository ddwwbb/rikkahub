package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.DuplexASRController
import me.rerere.asr.DuplexASREvent
import me.rerere.rikkahub.service.VoiceReply
import me.rerere.rikkahub.service.VoiceReplyState
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceSessionControllerTest {
    private class Recorder : DuplexASRController {
        override val state = MutableStateFlow(ASRState())
        val input = Channel<DuplexASREvent>(32)
        override val events = input.receiveAsFlow()
        var disposed = false
        override fun start(onTranscriptChange: (String) -> Unit) {
            state.value = ASRState(status = ASRStatus.Listening)
        }
        override fun stop() { state.value = state.value.copy(status = ASRStatus.Idle) }
        override fun dispose() { disposed = true; input.close() }
        suspend fun utterance(id: String, text: String) {
            input.send(DuplexASREvent.Started(id))
            input.send(DuplexASREvent.Ended(id))
            input.send(DuplexASREvent.Final(id, text))
        }
    }
    private class Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val recorder = Recorder()
        val submitted = mutableListOf<String>()
        val replies = mutableListOf<MutableStateFlow<VoiceReplyState>>()
        val spoken = mutableListOf<String>()
        val interrupted = mutableListOf<Uuid>()
        val playback = CompletableDeferred<Unit>()
        val voice = VoiceSessionController(scope, { it.toString() }, { text ->
            submitted += text
            val updates = MutableStateFlow(VoiceReplyState())
            replies += updates
            VoiceReply(updates = updates)
        }, { interrupted += it })
        fun start(failPlayback: Boolean = false) = voice.start(
            createAsr = { recorder },
            speak = { text ->
                spoken += text
                if (failPlayback) error("playback failed")
                playback.await()
            },
            stopSpeaking = {},
        )
        fun reply(index: Int, text: String, finished: Boolean = false) {
            val previous = replies[index].value.messages.firstOrNull()
            val message = previous?.copy(parts = UIMessage.assistant(text).parts) ?: UIMessage.assistant(text)
            replies[index].value = VoiceReplyState(messages = listOf(message), finished = finished)
        }
        fun close() { voice.stop(); scope.cancel() }
    }

    @Test fun `speech onset stops playback without closing microphone or submitting partial`() = runBlocking {
        val rig = Rig()
        try {
            rig.start()
            rig.recorder.utterance("one", "question")
            awaitCondition { rig.replies.size == 1 }
            rig.reply(0, "第一句。后续还在生成")
            awaitCondition { rig.voice.state.value.isSpeaking }
            assertTrue(rig.voice.state.value.isListening)
            rig.recorder.input.send(DuplexASREvent.Started("two"))
            awaitCondition { !rig.voice.state.value.isSpeaking }
            assertFalse(rig.recorder.disposed)
            assertEquals(listOf("question"), rig.submitted)
            assertTrue(rig.interrupted.isNotEmpty())
            rig.recorder.input.send(DuplexASREvent.Transcript("two", "临时"))
            assertEquals(1, rig.submitted.size)
        } finally { rig.close() }
    }

    @Test fun `final replaces old speech and late old snapshots never play`() = runBlocking {
        val rig = Rig()
        try {
            rig.start()
            rig.recorder.utterance("one", "first")
            awaitCondition { rig.replies.size == 1 }
            rig.reply(0, "旧回答。")
            awaitCondition { rig.spoken.size == 1 }
            rig.recorder.utterance("two", "second")
            awaitCondition { rig.replies.size == 2 }
            rig.reply(0, "旧回答。迟到文字。", true)
            rig.reply(1, "新回答。", true)
            awaitCondition { rig.spoken.size == 2 }
            assertEquals(listOf("first", "second"), rig.submitted)
            assertEquals(listOf("旧回答。", "新回答。"), rig.spoken)
            assertFalse(rig.recorder.disposed)
        } finally { rig.close() }
    }

    @Test fun `speech end waits for final and duplicates do not resubmit`() = runBlocking {
        val rig = Rig()
        try {
            rig.start()
            rig.recorder.input.send(DuplexASREvent.Started("one"))
            rig.recorder.input.send(DuplexASREvent.Ended("one"))
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Transcribing }
            assertTrue(rig.submitted.isEmpty())
            rig.recorder.input.send(DuplexASREvent.Final("one", "停"))
            awaitCondition { rig.submitted.size == 1 }
            rig.recorder.input.send(DuplexASREvent.Final("one", "重复"))
            rig.recorder.utterance("two", "嗯")
            awaitCondition { rig.submitted.size == 2 }
            assertEquals(listOf("停", "嗯"), rig.submitted)
        } finally { rig.close() }
    }

    @Test fun `empty final neither submits nor resumes interrupted reply`() = runBlocking {
        val rig = Rig()
        try {
            rig.start()
            rig.recorder.utterance("one", "question")
            awaitCondition { rig.replies.size == 1 }
            rig.reply(0, "第一句。")
            awaitCondition { rig.spoken.size == 1 }
            rig.recorder.utterance("noise", "")
            awaitCondition { !rig.voice.state.value.isSpeaking }
            rig.reply(0, "第一句。第二句。", true)
            rig.playback.complete(Unit)
            delay(20)
            assertEquals(listOf("question"), rig.submitted)
            assertEquals(listOf("第一句。"), rig.spoken)
        } finally { rig.close() }
    }

    @Test fun `ending voice detaches late replies without canceling accepted chat`() = runBlocking {
        val rig = Rig()
        try {
            rig.start()
            rig.recorder.utterance("one", "keep")
            awaitCondition { rig.replies.size == 1 }
            rig.voice.stop()
            awaitCondition { rig.recorder.disposed }
            rig.reply(0, "迟到回复。", true)
            assertEquals(listOf("keep"), rig.submitted)
            assertTrue(rig.spoken.isEmpty())
            assertEquals(VoicePhase.Off, rig.voice.state.value.phase)
        } finally { rig.close() }
    }

    @Test fun `recognition generation and playback failures stop recording`() = runBlocking {
        for (failure in listOf("recognition", "generation", "playback")) {
            val rig = Rig()
            try {
                rig.start(failPlayback = failure == "playback")
                if (failure == "recognition") rig.recorder.input.send(DuplexASREvent.Failed("offline"))
                else {
                    rig.recorder.utterance("one", "question")
                    awaitCondition { rig.replies.size == 1 }
                    if (failure == "generation") rig.replies[0].value = VoiceReplyState(error = "generation failed")
                    else rig.reply(0, "回答。")
                }
                awaitCondition { rig.voice.state.value.phase == VoicePhase.Error }
                awaitCondition { rig.recorder.disposed }
                assertFalse(rig.voice.state.value.isListening)
                assertFalse(rig.voice.state.value.isSpeaking)
            } finally { rig.close() }
        }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(3000) { while (!predicate()) delay(1) }
    }
}
