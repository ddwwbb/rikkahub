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

class VoiceAcousticActivityTest {
    @Test fun `next physical speech suppresses a late final without mixing utterance identities`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val events = Channel<DuplexASREvent>(32)
        val recorder = object : DuplexASRController {
            override val state = MutableStateFlow(ASRState())
            override val events = events.receiveAsFlow()
            override fun start(onTranscriptChange: (String) -> Unit) { state.value = ASRState(status = ASRStatus.Listening) }
            override fun stop() {}
            override fun dispose() { events.close() }
        }
        val submitted = mutableListOf<String>()
        val replies = mutableListOf<MutableStateFlow<VoiceReplyState>>()
        val spoken = mutableListOf<String>()
        val voice = VoiceSessionController(scope, { it.toString() }, {
            submitted += it
            val update = MutableStateFlow(VoiceReplyState())
            replies += update
            VoiceReply(updates = update)
        }, {})
        try {
            voice.start({ recorder }, { spoken += it; CompletableDeferred<Unit>().await() }, {})
            events.send(DuplexASREvent.AcousticActivity(true))
            events.send(DuplexASREvent.AcousticActivity(false))
            events.send(DuplexASREvent.Ended("a"))
            events.send(DuplexASREvent.AcousticActivity(true))
            events.send(DuplexASREvent.Final("a", "北京天气"))
            awaitCondition { replies.size == 1 }
            replies[0].value = VoiceReplyState(messages = listOf(UIMessage.assistant("不该在续说中播报。")), finished = true)
            events.send(DuplexASREvent.AcousticActivity(false))
            delay(20)
            assertTrue(spoken.isEmpty())
            events.send(DuplexASREvent.Ended("b"))
            events.send(DuplexASREvent.Final("b", "还有上海"))
            awaitCondition { replies.size == 2 }
            replies[1].value = VoiceReplyState(messages = listOf(UIMessage.assistant("两个城市的天气。")), finished = true)
            awaitCondition { spoken.isNotEmpty() }
            assertEquals(listOf("北京天气", "还有上海"), submitted)
            assertEquals(listOf("两个城市的天气。"), spoken)
        } finally { voice.stop(); scope.cancel() }
    }

    @Test fun `speech classifier hangover does not permanently suppress the completed utterance reply`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val events = Channel<DuplexASREvent>(32)
        val recorder = object : DuplexASRController {
            override val state = MutableStateFlow(ASRState())
            override val events = events.receiveAsFlow()
            override fun start(onTranscriptChange: (String) -> Unit) { state.value = ASRState(status = ASRStatus.Listening) }
            override fun stop() {}
            override fun dispose() { events.close() }
        }
        val update = MutableStateFlow(VoiceReplyState())
        val spoken = mutableListOf<String>()
        val voice = VoiceSessionController(scope, { it.toString() }, { VoiceReply(updates = update) }, {})
        try {
            voice.start({ recorder }, { spoken += it }, {})
            events.send(DuplexASREvent.AcousticActivity(true))
            events.send(DuplexASREvent.Ended("a"))
            events.send(DuplexASREvent.Final("a", "问题"))
            awaitCondition { voice.state.value.pendingReplies == 1 }
            update.value = VoiceReplyState(messages = listOf(UIMessage.assistant("回答。")), finished = true)
            events.send(DuplexASREvent.AcousticActivity(false))
            awaitCondition { spoken.isNotEmpty() }
            assertEquals(listOf("回答。"), spoken)
        } finally { voice.stop(); scope.cancel() }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(3000) { while (!predicate()) delay(1) }
    }
}
