package me.rerere.asr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Owns communication mode, focus and route. Recorder-bound AEC belongs to the ASR recorder. */
class VoiceAudioSession(context: Context, private val onLost: () -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var opened = false
    private var lost = false
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldSpeaker = false
    private var oldSco = false
    private var oldDevice: AudioDeviceInfo? = null
    private var selectedDeviceId: Int? = null
    private var scoStarted = false
    private var receiverRegistered = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) lose()
        }, handler)
        .build()
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = reroute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = reroute()
    }
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (opened && scoStarted && intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ==
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED) lose()
        }
    }
    private val scoDeadline = Runnable {
        if (opened && scoStarted && !audio.isBluetoothScoOn) lose()
    }

    fun open() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Voice audio session must open on the main thread" }
        if (opened) return
        oldMode = audio.mode
        oldSpeaker = audio.isSpeakerphoneOn
        oldSco = audio.isBluetoothScoOn
        if (Build.VERSION.SDK_INT >= 31) oldDevice = audio.communicationDevice
        opened = true
        lost = false
        try {
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Voice audio focus is unavailable"
            }
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            check(audio.mode == AudioManager.MODE_IN_COMMUNICATION) { "Voice communication audio mode is unavailable" }
            audio.registerAudioDeviceCallback(devices, handler)
            if (Build.VERSION.SDK_INT < 31) {
                this.context.registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                receiverRegistered = true
            }
            selectRoute()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun reroute() {
        if (!opened || lost) return
        try {
            selectRoute()
        } catch (_: Exception) {
            lose()
        }
    }

    private fun selectRoute() {
        if (Build.VERSION.SDK_INT >= 31) {
            val candidates = audio.availableCommunicationDevices
            val preferred = candidates.minByOrNull { priority(it.type) }
                ?: error("No communication audio route is available")
            if (preferred.id == selectedDeviceId) return
            if (selectedDeviceId != null) {
                // The recorder's AEC qualification belongs to its original route/session.
                // A new route requires an explicit restart and qualification, not silent reuse.
                lose()
                return
            }
            check(audio.setCommunicationDevice(preferred)) { "Cannot select voice communication audio route" }
            selectedDeviceId = preferred.id
        } else {
            val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val headset = outputs.firstOrNull { it.type in wiredTypes }
                ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            val routeId = headset?.id ?: -1
            if (selectedDeviceId != null && selectedDeviceId != routeId) {
                lose()
                return
            }
            selectedDeviceId = routeId
            val wired = headset?.type in wiredTypes
            val bluetooth = headset?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            if (bluetooth) {
                if (!scoStarted) {
                    check(audio.isBluetoothScoAvailableOffCall) { "Bluetooth voice audio is unavailable; use wired headphones" }
                    scoStarted = true
                    audio.startBluetoothSco()
                    audio.isBluetoothScoOn = true
                    handler.postDelayed(scoDeadline, 5000)
                }
                audio.isSpeakerphoneOn = false
            } else {
                if (scoStarted) {
                    scoStarted = false
                    handler.removeCallbacks(scoDeadline)
                    audio.stopBluetoothSco()
                    audio.isBluetoothScoOn = false
                }
                audio.isSpeakerphoneOn = !wired
            }
        }
    }

    private fun lose() {
        if (!opened || lost) return
        lost = true
        onLost()
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Voice audio session must close on the main thread" }
        if (!opened) return
        opened = false
        audio.unregisterAudioDeviceCallback(devices)
        handler.removeCallbacks(scoDeadline)
        if (receiverRegistered) {
            context.unregisterReceiver(scoReceiver)
            receiverRegistered = false
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (audio.communicationDevice?.id == selectedDeviceId) {
                val previous = oldDevice?.let { old -> audio.availableCommunicationDevices.firstOrNull { it.id == old.id } }
                if (previous == null) audio.clearCommunicationDevice() else audio.setCommunicationDevice(previous)
            }
            selectedDeviceId = null
        } else {
            if (scoStarted) audio.stopBluetoothSco()
            scoStarted = false
            audio.isBluetoothScoOn = oldSco
            audio.isSpeakerphoneOn = oldSpeaker
        }
        selectedDeviceId = null
        if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = oldMode
        audio.abandonAudioFocusRequest(focus)
    }

    private fun priority(type: Int): Int = when {
        type in wiredTypes -> 0
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET) -> 1
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 2
        type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 3
        else -> 4
    }

    private val wiredTypes = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
}
