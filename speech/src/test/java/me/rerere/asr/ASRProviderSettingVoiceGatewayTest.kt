package me.rerere.asr

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ASRProviderSettingVoiceGatewayTest {

    @Test fun `saving editing and polymorphic reload retain every gateway field`() {
        val original = ASRProviderSetting.VoiceGateway(name = "Local", apiKey = "test-key", websocketUrl = "ws://127.0.0.1:8080/v1/asr")
        val edited = original.copyProvider(name = "Updated") as ASRProviderSetting.VoiceGateway
        val saved = Json.decodeFromString<ASRProviderSetting>(Json.encodeToString<ASRProviderSetting>(edited))
        assertEquals(edited, saved)
    }
}
