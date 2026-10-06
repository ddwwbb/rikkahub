package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.VoiceAudioSession
import me.rerere.asr.providers.createDuplexAsr
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.tts.controller.VoiceTtsPlayer
import me.rerere.tts.provider.TTSManager
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

/** Lives above adaptive drawer branches so resizing cannot recreate a voice session. */
@Composable
fun rememberVoiceModeStarter(vm: ChatVM, settings: Settings): () -> Unit {
    val context = LocalContext.current.applicationContext
    val client = koinInject<OkHttpClient>()
    val ttsManager = koinInject<TTSManager>()
    val player = remember(vm) { VoiceTtsPlayer(context, ttsManager) }
    val asr = LocalASRState.current
    val ordinaryTts = LocalTTSState.current
    val toaster = LocalToaster.current
    val permission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permission)
    val voice = vm.voiceSession
    val state by voice.state.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val provider = settings.getSelectedASRProvider()
    val ttsProvider = settings.getSelectedTTSProvider()
    val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
    val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(voice, lifecycleOwner, provider, ttsProvider, assistant, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) voice.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voice.stop()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    val start: () -> Unit = {
        val blocked = when {
            provider == null -> context.getString(R.string.chat_page_voice_configure_asr)
            !provider.supportsServerVadVoiceMode -> context.getString(R.string.chat_page_voice_unsupported_asr)
            ttsProvider == null -> "请先配置 TTS，双工语音需要可用的播报服务。"
            model == null -> context.getString(R.string.chat_page_voice_select_model)
            asr.state.value.isRecording -> context.getString(R.string.chat_page_voice_finish_dictation)
            vm.messageQueue.value.paused && vm.messageQueue.value.messages.isNotEmpty() ->
                context.getString(R.string.chat_page_voice_resume_queue)
            conversation.currentMessages.any { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            } -> context.getString(R.string.chat_page_voice_pending_tools)
            else -> null
        }
        when {
            blocked != null -> toaster.show(message = blocked)
            !permission.allRequiredPermissionsGranted -> permission.requestPermissions()
            else -> {
                ordinaryTts.stop()
                voice.start(
                    createAsr = { createDuplexAsr(context, client, checkNotNull(provider)) },
                    speak = { player.speak(checkNotNull(ttsProvider), it, settings.defaultTTSPlaybackSpeed) },
                    stopSpeaking = player::stop,
                    // Arbitrary regexes and quote fallback can revise an earlier prefix; never guess a delta.
                    waitForReplyFinish = assistant.regexes.isNotEmpty() || settings.displaySetting.ttsOnlyReadQuoted,
                    quotedOnly = settings.displaySetting.ttsOnlyReadQuoted,
                    outsideBracketsOnly = settings.displaySetting.ttsOnlyReadOutsideBrackets,
                    openAudioSession = {
                        VoiceAudioSession(context) {
                            voice.fail("音频焦点或通话设备已变化，语音已停止，请重新开启。")
                        }.also { it.open() }
                    },
                )
            }
        }
    }
    if (state.isActive) {
        val view = LocalView.current
        DisposableEffect(view) {
            val previous = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = previous }
        }
    }
    return start
}
