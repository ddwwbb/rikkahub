package me.rerere.tts.controller

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import java.io.IOException

/** One stable text segment at a time; returns only after its last sample has played. */
@OptIn(UnstableApi::class)
class VoiceTtsPlayer(context: Context, private val ttsManager: TTSManager) {
    private val context = context.applicationContext
    private val lock = Any()
    private var current: Session? = null
    private var generation = 0L
    private var released = false
    // Accessed only on the main looper. The audio session, not this player, owns focus.
    private var player: ExoPlayer? = null

    suspend fun speak(provider: TTSProviderSetting, text: String, speed: Float = 1f) {
        require(text.isNotBlank()) { "Voice TTS text is empty" }
        val epoch = synchronized(lock) { generation }
        for (chunk in TextChunker(160).split(text)) {
            play(ttsManager.generateSpeech(provider, TTSRequest(chunk.text)), speed, epoch)
        }
    }
    internal suspend fun play(chunks: Flow<AudioChunk>, speed: Float = 1f, epoch: Long? = null) = coroutineScope {
        require(speed.isFinite() && speed > 0f) { "Invalid voice playback speed" }
        val session = Session(currentCoroutineContext()[Job]!!)
        synchronized(lock) {
            check(!released) { "Voice TTS player is released" }
            if (epoch != null && epoch != generation) throw CancellationException("Voice playback interrupted")
            check(current == null) { "Voice TTS already has an active segment" }
            current = session
        }
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                session.stopAudio()
            }
        }
        try {
            val first = CompletableDeferred<AudioChunk>()
            val producer = launch(Dispatchers.IO) {
                var format: AudioFormat? = null
                var sampleRate: Int? = null
                var last = false
                var hasAudio = false
                chunks.collect { chunk ->
                    ensureActive()
                    check(!last) { "TTS sent audio after its final chunk" }
                    if (format == null) {
                        format = chunk.format
                        sampleRate = chunk.sampleRate
                        first.complete(chunk)
                    }
                    check(chunk.format == format) { "TTS changed audio format mid-stream" }
                    if (chunk.format == AudioFormat.PCM) {
                        check(chunk.sampleRate == sampleRate) { "TTS changed PCM sample rate mid-stream" }
                    }
                    hasAudio = hasAudio || chunk.data.isNotEmpty()
                    session.pipe.write(chunk.data)
                    last = chunk.isLast
                }
                check(hasAudio) { "TTS returned no audio" }
                session.pipe.finish()
            }
            val descriptor = first.await()
            if (descriptor.format == AudioFormat.PCM) {
                playPcm(session, descriptor, speed)
            } else {
                playEncoded(session, speed)
            }
            producer.join()
        } finally {
            session.stopAudio()
            cancellation.cancel()
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                synchronized(lock) {
                    if (current === session) {
                        player?.stop()
                        player?.clearMediaItems()
                        current = null
                    }
                }
            }
            session.releaseTrack()
        }
    }

    fun stop() {
        val session = synchronized(lock) { generation++; current } ?: return
        session.stopAudio()
        session.job.cancel(CancellationException("Voice playback interrupted"))
    }

    fun release() {
        synchronized(lock) { released = true }
        stop()
        // ExoPlayer must always be released on its application looper.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            player?.release()
            player = null
        }
    }

    /** Test/diagnostic observation of the platform playback clock, never queued byte counts. */
    internal suspend fun playbackPositionMs(): Long = withContext(Dispatchers.Main.immediate) {
        val session = synchronized(lock) { current }
        session?.pcmPositionMs() ?: player?.currentPosition ?: 0L
    }

    private suspend fun playEncoded(session: Session, speed: Float) = withContext(Dispatchers.Main.immediate) {
        ensureActive()
        val exo = player ?: ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(100, 1000, 50, 50)
                .setTargetBufferBytes(128 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build())
            .build().also {
                it.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_VOICE_COMMUNICATION)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
                player = it
            }
        val completed = CompletableDeferred<Unit>()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) completed.complete(Unit)
            }
            override fun onPlayerError(error: PlaybackException) {
                completed.completeExceptionally(error)
            }
        }
        val source = ProgressiveMediaSource.Factory(DataSource.Factory { PipeDataSource(session.pipe) })
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(0) {
                override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
            })
            .createMediaSource(MediaItem.fromUri("voice://segment"))
        exo.addListener(listener)
        try {
            exo.playbackParameters = PlaybackParameters(speed)
            exo.setMediaSource(source)
            exo.prepare()
            exo.play()
            completed.await()
        } finally {
            exo.removeListener(listener)
            exo.stop()
            exo.clearMediaItems()
        }
    }

    private suspend fun playPcm(session: Session, descriptor: AudioChunk, speed: Float) = withContext(Dispatchers.IO) {
        val rate = descriptor.sampleRate ?: descriptor.metadata["sampleRate"]?.toIntOrNull()
            ?: throw IOException("PCM TTS must declare its sample rate")
        val channels = descriptor.metadata["channels"]?.toIntOrNull() ?: 1
        val bitDepth = descriptor.metadata["bitDepth"]?.toIntOrNull() ?: 16
        require(channels in 1..2 && bitDepth == 16) { "Voice PCM requires mono/stereo PCM16LE" }
        val mask = if (channels == 1) AndroidAudioFormat.CHANNEL_OUT_MONO else AndroidAudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(rate, mask, AndroidAudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "Unsupported voice PCM sample rate: $rate" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AndroidAudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, rate * channels * 2 / 10))
            .build()
        session.startTrack(track, speed)
        ensureActive()
        val frameSize = channels * 2
        val buffer = ByteArray(8192 + frameSize)
        var pending = 0
        var frames = 0L
        while (true) {
            ensureActive()
            val count = session.pipe.read(buffer, pending, buffer.size - pending)
            if (count == -1) break
            val available = pending + count
            val aligned = available - available % frameSize
            var offset = 0
            while (offset < aligned) {
                ensureActive()
                val written = track.write(buffer, offset, aligned - offset, AudioTrack.WRITE_NON_BLOCKING)
                check(written >= 0) { "Voice PCM write failed: $written" }
                if (written == 0) delay(5) else offset += written
            }
            frames += aligned / frameSize
            pending = available - aligned
            if (pending > 0) buffer.copyInto(buffer, 0, aligned, available)
        }
        check(pending == 0) { "TTS ended with an incomplete PCM sample" }
        var stalled = 0
        var previous = -1L
        while ((track.playbackHeadPosition.toLong() and 0xffffffffL) < frames) {
            ensureActive()
            val head = track.playbackHeadPosition.toLong() and 0xffffffffL
            stalled = if (head == previous) stalled + 1 else 0
            check(stalled < 3000) { "Voice PCM playback did not drain" }
            previous = head
            delay(10)
        }
    }

    private class Session(val job: Job) {
        val pipe = VoiceAudioPipe()
        private var track: AudioTrack? = null
        private var stopped = false
        private var sampleRate = 1
        @Synchronized fun pcmPositionMs(): Long? = track?.let {
            (it.playbackHeadPosition.toLong() and 0xffffffffL) * 1000 / sampleRate
        }
        @Synchronized fun startTrack(value: AudioTrack, speed: Float) {
            if (stopped) {
                value.release()
                throw CancellationException("Voice playback interrupted")
            }
            track = value
            sampleRate = value.sampleRate
            check(value.state == AudioTrack.STATE_INITIALIZED) { "Voice AudioTrack initialization failed" }
            value.playbackParams = PlaybackParams().setSpeed(speed)
            value.play()
        }
        @Synchronized fun stopAudio() {
            stopped = true
            pipe.abort()
            track?.let {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    it.pause()
                    it.flush()
                }
            }
        }
        @Synchronized fun releaseTrack() {
            track?.release()
            track = null
        }
    }

    private class PipeDataSource(private val pipe: VoiceAudioPipe) : BaseDataSource(false) {
        private var uri: Uri? = null
        override fun open(dataSpec: DataSpec): Long {
            if (dataSpec.position != 0L) throw IOException("Voice audio container requires unsupported seeking")
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = pipe.read(buffer, offset, length)
            if (count > 0) bytesTransferred(count)
            return count
        }
        override fun getUri(): Uri? = uri
        override fun close() {
            if (uri != null) {
                uri = null
                transferEnded()
            }
        }
    }
}
