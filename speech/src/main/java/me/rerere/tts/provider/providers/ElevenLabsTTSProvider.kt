package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import me.rerere.tts.provider.emitHttpAudio
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ElevenLabsTTSProvider"

class ElevenLabsTTSProvider : TTSProvider<TTSProviderSetting.ElevenLabs> {
    private val httpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.ElevenLabs,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("text", request.text)
            put("model_id", providerSetting.model)
            put("voice_settings", JSONObject().apply {
                put("stability", providerSetting.stability.toDouble())
                put("similarity_boost", providerSetting.similarityBoost.toDouble())
            })
        }

        Log.i(TAG, "generateSpeech: model=${providerSetting.model}, voiceId=${providerSetting.voiceId}")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/v1/text-to-speech/${providerSetting.voiceId}?output_format=mp3_44100_128")
            .addHeader("xi-api-key", providerSetting.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        emitHttpAudio(httpClient, httpRequest, AudioFormat.MP3, metadata = mapOf(
            "provider" to "elevenlabs", "model" to providerSetting.model,
            "voiceId" to providerSetting.voiceId,
        ))
    }.flowOn(Dispatchers.IO)
}
