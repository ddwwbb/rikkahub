package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import me.rerere.common.http.withCancellableResponse
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiTTSProvider"

class GeminiTTSProvider : TTSProvider<TTSProviderSetting.Gemini> {
    private val httpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class GeminiTTSResponse(
        val candidates: List<Candidate>
    )

    @Serializable
    data class Candidate(
        val content: Content
    )

    @Serializable
    data class Content(
        val parts: List<Part>
    )

    @Serializable
    data class Part(
        val inlineData: InlineData
    )

    @Serializable
    data class InlineData(
        val data: String,
        val mimeType: String
    )

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Gemini,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", request.text)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply {
                    put("AUDIO")
                })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", providerSetting.voiceName)
                        })
                    })
                })
            })
            put("model", providerSetting.model)
        }

        Log.i(TAG, "generateSpeech: $requestBody")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/models/${providerSetting.model}:generateContent")
            .addHeader("x-goog-api-key", providerSetting.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(httpRequest).withCancellableResponse { response ->

        if (!response.isSuccessful) {
            val statusCode = response.code
            val statusMessage = response.message
            throw TTSProviderException(
                message = "Gemini TTS request failed: $statusCode $statusMessage",
                statusCode = statusCode
            )
        }

        val responseJson = response.body.string()
        val geminiResponse = json.decodeFromString<GeminiTTSResponse>(responseJson)

        if (geminiResponse.candidates.isEmpty() ||
            geminiResponse.candidates[0].content.parts.isEmpty()
        ) {
            throw Exception("No audio data returned from Gemini TTS")
        }

        val inline = geminiResponse.candidates[0].content.parts[0].inlineData
        val audioData = Base64.decode(inline.data, Base64.DEFAULT)
        val mime = inline.mimeType.lowercase()
        val format = when {
            mime.startsWith("audio/l16") || mime.startsWith("audio/pcm") -> AudioFormat.PCM
            mime.startsWith("audio/wav") || mime.startsWith("audio/x-wav") -> AudioFormat.WAV
            mime.startsWith("audio/mpeg") -> AudioFormat.MP3
            else -> error("Unsupported Gemini TTS audio MIME: ${inline.mimeType}")
        }
        val sampleRate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toInt() ?: 24000

        emit(
            AudioChunk(
                data = audioData,
                format = format,
                sampleRate = sampleRate,
                isLast = true,
                metadata = mapOf(
                    "provider" to "gemini",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voiceName,
                    "sampleRate" to sampleRate.toString(),
                    "channels" to "1",
                    "bitDepth" to "16"
                )
            )
        )
        }
    }.flowOn(Dispatchers.IO)
}
