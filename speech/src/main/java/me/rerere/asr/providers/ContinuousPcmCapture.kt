package me.rerere.asr.providers

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** One recorder for the whole session, including remote utterance turnover. */
internal class ContinuousPcmCapture(private val context: Context) {
    @Volatile private var recorder: AudioRecord? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @SuppressLint("MissingPermission")
    suspend fun capture(
        sampleRate: Int,
        duplex: Boolean,
        localSpeechDetection: Boolean,
        onFrame: (ByteArray, Int, Boolean?) -> Unit,
    ) {
        val frameBytes = sampleRate / 50 * 2 // 20ms PCM16 mono, never exceeds gateway 6400B.
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microphone does not support ${sampleRate}Hz PCM16 mono" }
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, frameBytes * 8))
        recorder = audio
        var echoCanceler: AcousticEchoCanceler? = null
        var vad: VadWebRTC? = null
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Unable to initialize communication microphone" }
            val needsEchoControl = duplex && !hasHeadphones()
            if (duplex && AcousticEchoCanceler.isAvailable()) {
                echoCanceler = runCatching { AcousticEchoCanceler.create(audio.audioSessionId) }.getOrNull()
                echoCanceler?.let { effect ->
                    runCatching { if (effect.hasControl()) effect.setEnabled(true) }
                }
            }
            if (localSpeechDetection) {
                vad = try {
                    VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320,
                        Mode.VERY_AGGRESSIVE, speechDurationMs = 40, silenceDurationMs = 300)
                } catch (error: LinkageError) {
                    throw IllegalStateException("WebRTC speech detection is unavailable on this device", error)
                }
            }
            currentCoroutineContext().ensureActive()
            if (needsEchoControl) requireEchoControl(echoCanceler)
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start recording" }
            val buffer = ByteArray(frameBytes)
            val vadSamples = if (localSpeechDetection) ShortArray(320) else null
            var filled = 0
            while (currentCoroutineContext().isActive) {
                if (needsEchoControl) requireEchoControl(echoCanceler)
                val count = audio.read(buffer, filled, buffer.size - filled, AudioRecord.READ_BLOCKING)
                currentCoroutineContext().ensureActive()
                check(count >= 0) { "Microphone read failed: $count" }
                if (count == 0) continue
                filled += count
                if (filled == buffer.size) {
                    val speech = vadSamples?.let { samples ->
                        for (index in samples.indices) {
                            samples[index] = ((buffer[index * 2].toInt() and 255) or
                                (buffer[index * 2 + 1].toInt() shl 8)).toShort()
                        }
                        vad!!.isSpeech(samples)
                    }
                    onFrame(buffer, filled, speech)
                    filled = 0
                }
            }
        } finally {
            runCatching { audio.stop() }
            runCatching { vad?.close() }
            runCatching { echoCanceler?.release() }
            runCatching { audio.release() }
            if (recorder === audio) recorder = null
        }
    }

    fun stop() { runCatching { recorder?.stop() } }

    private fun requireEchoControl(effect: AcousticEchoCanceler?) {
        check(effect != null && effect.hasControl() && effect.enabled) {
            "Speaker duplex requires working system acoustic echo cancellation; connect headphones and restart voice mode"
        }
    }

    @Suppress("DEPRECATION")
    private fun hasHeadphones(): Boolean {
        val manager = audioManager
        if (Build.VERSION.SDK_INT >= 31) {
            return when (manager.communicationDevice?.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET -> true
                else -> false
            }
        }
        return manager.isWiredHeadsetOn || manager.isBluetoothScoOn
    }
}
