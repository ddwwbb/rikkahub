package me.rerere.tts.provider

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import me.rerere.common.http.withCancellableResponse
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import okhttp3.OkHttpClient
import okhttp3.Request

/** Reads the real response bytes, without collecting or transcoding the audio. */
internal suspend fun FlowCollector<AudioChunk>.emitHttpAudio(
    client: OkHttpClient,
    request: Request,
    format: AudioFormat,
    sampleRate: Int? = null,
    metadata: Map<String, String> = emptyMap(),
) {
    client.newCall(request).withCancellableResponse { response ->
        if (!response.isSuccessful) {
            throw TTSProviderException(
                "TTS request failed: HTTP ${response.code} ${response.message}",
                response.code,
            )
        }
        val source = response.body.source()
        val buffer = ByteArray(8192)
        var hasAudio = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = source.read(buffer)
            if (count == -1) break
            if (count == 0) continue
            hasAudio = true
            emit(AudioChunk(buffer.copyOf(count), format, sampleRate, metadata = metadata))
        }
        check(hasAudio) { "TTS returned no audio" }
        emit(AudioChunk(byteArrayOf(), format, sampleRate, isLast = true, metadata = metadata))
    }
}
