package me.rerere.asr.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.asr.DuplexASREvent

/** Protocol events, not UI snapshots, determine utterance completion. */
internal class DuplexAsrProtocol {
    private val partials = mutableMapOf<String, String>()
    private val completedIds = LinkedHashSet<String>()
    private var volcFinalCount = 0
    private var localWasSpeech = false

    fun realtime(text: String): List<DuplexASREvent> {
        val event = Json.parseToJsonElement(text).jsonObject
        val type = event.string("type")
        if (type == "error" || type == "conversation.item.input_audio_transcription.failed") {
            error(event["error"]?.jsonObject?.string("message") ?: "ASR recognition failed")
        }
        if (type !in setOf("input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped",
                "conversation.item.input_audio_transcription.delta", "conversation.item.input_audio_transcription.text",
                "conversation.item.input_audio_transcription.completed")) return emptyList()
        val id = event.string("item_id").also { require(it.isNotBlank()) { "ASR event has no utterance ID" } }
        return when (type) {
            "input_audio_buffer.speech_started" -> listOf(DuplexASREvent.Started(id))
            "input_audio_buffer.speech_stopped" -> listOf(DuplexASREvent.Ended(id))
            "conversation.item.input_audio_transcription.completed" -> {
                partials.remove(id)
                if (!rememberFinal(id)) emptyList() else listOf(DuplexASREvent.Final(id, event.string("transcript").trim()))
            }
            else -> {
                check(partials.size < 32 || id in partials) { "Too many outstanding ASR utterances" }
                val value = if (type.endsWith(".delta")) (partials[id] ?: "") + event.string("delta")
                    else event.string("text") + event.string("stash")
                check(value.length <= 65_536) { "ASR utterance text exceeds limit" }
                partials[id] = value
                listOf(DuplexASREvent.Transcript(id, value))
            }
        }
    }

    fun gateway(text: String, dictation: Boolean = false): List<DuplexASREvent> {
        val event = Json.parseToJsonElement(text).jsonObject
        val type = event.string("type")
        if (type == "error") error(event["error"]?.jsonObject?.string("message") ?: "Voice gateway failed")
        if (type !in setOf("speech_started", "speech_ended", "interim", "correction", "final")) return emptyList()
        val id = if (dictation) "dictation" else event.string("utterance_id").also {
            require(it.isNotBlank()) { "Gateway event has no utterance ID" }
        }
        return when (type) {
            "speech_started" -> listOf(DuplexASREvent.Started(id))
            "speech_ended" -> listOf(DuplexASREvent.Ended(id))
            "final" -> {
                if (!rememberFinal(id)) emptyList() else {
                    listOf(DuplexASREvent.Final(id, event.string("text").trim()))
                }
            }
            else -> listOf(DuplexASREvent.Transcript(id, event.string("text")))
        }
    }

    /** WebRTC activity never assigns service utterance IDs or determines finalization. */
    fun acousticActivity(isSpeech: Boolean): List<DuplexASREvent> {
        if (isSpeech == localWasSpeech) return emptyList()
        localWasSpeech = isSpeech
        return listOf(DuplexASREvent.AcousticActivity(isSpeech))
    }

    fun volc(result: JsonObject?): List<DuplexASREvent> {
        val utterances = result?.get("utterances")?.jsonArray ?: return emptyList()
        check(utterances.size >= volcFinalCount) { "ASR cumulative utterance history was reset unexpectedly" }
        val events = mutableListOf<DuplexASREvent>()
        while (volcFinalCount < utterances.size) {
            val utterance = utterances[volcFinalCount].jsonObject
            val id = "volc:$volcFinalCount"
            val text = utterance.string("text")
            if (utterance["definite"]?.jsonPrimitive?.booleanOrNull != true) {
                events += DuplexASREvent.Transcript(id, text)
                break
            }
            // definite is the upstream second-pass finalized sentence, not a guessed silence timer.
            events += DuplexASREvent.Ended(id)
            events += DuplexASREvent.Final(id, text.trim())
            volcFinalCount++
        }
        return events
    }

    private fun rememberFinal(id: String): Boolean {
        val fresh = completedIds.add(id)
        if (completedIds.size > 256) completedIds.remove(completedIds.first())
        return fresh
    }
}

internal fun JsonObject.string(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
