package me.rerere.asr.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.asr.DuplexASREvent
import org.junit.Assert.*
import org.junit.Test

class DuplexAsrProtocolTest {
    @Test fun `continuous realtime utterances retain boundaries and finals without resetting connection`() {
        val protocol = DuplexAsrProtocol()
        for (id in listOf("a", "b", "c")) {
            assertEquals(listOf(DuplexASREvent.Started(id)), protocol.realtime("""{"type":"input_audio_buffer.speech_started","item_id":"$id"}"""))
            assertEquals(listOf(DuplexASREvent.Transcript(id, "hello")), protocol.realtime("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"$id","delta":"hello"}"""))
            assertEquals(listOf(DuplexASREvent.Ended(id)), protocol.realtime("""{"type":"input_audio_buffer.speech_stopped","item_id":"$id"}"""))
            assertEquals(listOf(DuplexASREvent.Final(id, "hello")), protocol.realtime("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"$id","transcript":"hello"}"""))
        }
    }

    @Test fun `final can arrive out of order and never includes another turn preview`() {
        val protocol = DuplexAsrProtocol()
        protocol.realtime("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"a","delta":"old"}""")
        protocol.realtime("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"b","delta":"new"}""")
        assertEquals(listOf(DuplexASREvent.Final("b", "new!")), protocol.realtime("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"b","transcript":"new!"}"""))
        assertEquals(listOf(DuplexASREvent.Final("a", "old!")), protocol.realtime("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"a","transcript":"old!"}"""))
        assertTrue(protocol.realtime("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"a","transcript":"old!"}""").isEmpty())
    }

    @Test fun `gateway missing optional service onset does not manufacture one from text`() {
        val protocol = DuplexAsrProtocol()
        assertEquals(listOf(DuplexASREvent.Transcript("session:0", "preview")), protocol.gateway("""{"type":"interim","utterance_id":"session:0","text":"preview"}"""))
        assertEquals(listOf(DuplexASREvent.Transcript("session:0", "corrected")), protocol.gateway("""{"type":"correction","utterance_id":"session:0","text":"corrected"}"""))
        assertEquals(listOf(DuplexASREvent.Ended("session:0")), protocol.gateway("""{"type":"speech_ended","utterance_id":"session:0"}"""))
        assertEquals(listOf(DuplexASREvent.Final("session:0", "")), protocol.gateway("""{"type":"final","utterance_id":"session:0","text":""}"""))
        assertEquals(listOf(DuplexASREvent.Started("session:1")), protocol.gateway("""{"type":"speech_started","utterance_id":"session:1"}"""))
    }

    @Test fun `gateway legacy dictation accepts text snapshots without utterance IDs`() {
        val protocol = DuplexAsrProtocol()
        assertEquals(listOf(DuplexASREvent.Transcript("dictation", "preview")), protocol.gateway("""{"type":"interim","text":"preview"}""", dictation = true))
        assertEquals(listOf(DuplexASREvent.Final("dictation", "result")), protocol.gateway("""{"type":"final","text":"result"}""", dictation = true))
        assertTrue(runCatching { protocol.gateway("""{"type":"final","text":"missing id"}""") }.isFailure)
    }

    @Test fun `acoustic activity only tracks physical changes and does not submit local final`() {
        val protocol = DuplexAsrProtocol()
        assertTrue(protocol.acousticActivity(false).isEmpty())
        assertEquals(listOf(DuplexASREvent.AcousticActivity(true)), protocol.acousticActivity(true))
        assertTrue(protocol.acousticActivity(true).isEmpty())
        assertEquals(listOf(DuplexASREvent.AcousticActivity(false)), protocol.acousticActivity(false))
        assertEquals(listOf(DuplexASREvent.AcousticActivity(true)), protocol.acousticActivity(true))
    }

    @Test fun `volc cumulative sentences all finalize once including empty and adjusted timestamps`() {
        val protocol = DuplexAsrProtocol()
        assertEquals(listOf(DuplexASREvent.Transcript("volc:0", "preview")), protocol.volc(result("""{"utterances":[{"start_time":120,"text":"preview","definite":false}]}""")))
        val final = result("""{"utterances":[{"start_time":100,"text":"first","definite":true},{"text":"","definite":true},{"text":"next","definite":false}]}""")
        assertEquals(listOf(DuplexASREvent.Ended("volc:0"), DuplexASREvent.Final("volc:0", "first"),
            DuplexASREvent.Ended("volc:1"), DuplexASREvent.Final("volc:1", ""), DuplexASREvent.Transcript("volc:2", "next")), protocol.volc(final))
        assertEquals(listOf(DuplexASREvent.Transcript("volc:2", "next")), protocol.volc(final))
        assertEquals(listOf(DuplexASREvent.Ended("volc:2"), DuplexASREvent.Final("volc:2", "next!")), protocol.volc(result("""{"utterances":[{"text":"first","definite":true},{"text":"","definite":true},{"text":"next!","definite":true}]}""")))
    }

    @Test fun `volc transcript alone is neither acoustic onset nor final`() {
        assertTrue(DuplexAsrProtocol().volc(result("""{"text":"hello"}""")).isEmpty())
    }

    @Test fun `upstream failures remain fatal with actual messages`() {
        val protocol = DuplexAsrProtocol()
        assertEquals("real failure", runCatching { protocol.gateway("""{"type":"error","error":{"message":"real failure"}}""") }.exceptionOrNull()?.message)
        assertEquals("failed", runCatching { protocol.realtime("""{"type":"conversation.item.input_audio_transcription.failed","error":{"message":"failed"}}""") }.exceptionOrNull()?.message)
    }

    private fun result(json: String) = Json.parseToJsonElement(json).jsonObject
}
