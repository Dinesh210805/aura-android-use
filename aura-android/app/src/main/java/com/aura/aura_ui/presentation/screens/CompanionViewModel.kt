package com.aura.aura_ui.presentation.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import com.aura.aura_ui.agent.conversation.CompanionConfig
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CompanionUiState(
    /** true ⇒ Gemini Live is the active voice mode; false ⇒ classic TTS/STT. Exactly one is always on. */
    val liveMode: Boolean = false,
    val hasKey: Boolean = false,
    /** Gemini Live voice (e.g. "Achird"). */
    val liveVoice: String = "Achird",
    /** Classic TTS voice display name (e.g. "Aria"). */
    val classicVoice: String = "Aria",
)

/**
 * Settings → Companion: BYOK key entry (never logged) + the voice-mode selector.
 * The two toggles map onto a single boolean (`useGeminiLive`) so exactly one mode
 * is always active; Gemini Live requires a saved key, otherwise TTS/STT holds.
 */
class CompanionViewModel(context: Context) : ViewModel() {
    private val app = context.applicationContext
    private val config = CompanionConfig(app)
    private val keys = ProviderKeyStore(app)
    private val voicePrefs =
        app.getSharedPreferences("aura_voice_settings", android.content.Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(CompanionUiState())
    val state: StateFlow<CompanionUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val hasKey = config.byokKey() != null
        // Guard: without a key, Gemini Live cannot run — force classic mode.
        val liveMode = config.useGeminiLive && hasKey
        _state.value = CompanionUiState(
            liveMode = liveMode,
            hasKey = hasKey,
            liveVoice = config.voice(),
            classicVoice = classicVoiceName(
                voicePrefs.getString("selected_voice_id", "en-US-AriaNeural") ?: "en-US-AriaNeural"
            ),
        )
    }

    /**
     * Select the voice mode. `live = true` needs a saved key; the companion's BYOK-direct
     * path is enabled in lock-step so [CompanionConfig.enabled] and `useGeminiLive` never drift.
     */
    fun setLiveMode(live: Boolean) {
        val effectiveLive = live && config.byokKey() != null
        config.useGeminiLive = effectiveLive
        config.enabled = effectiveLive
        refresh()
    }

    fun saveKey(key: String) {
        if (key.isNotBlank()) keys.setKey(LlmEndpointCatalog.GEMINI_ID, key)
        refresh()
    }

    private fun classicVoiceName(voiceId: String): String = when (voiceId) {
        "en-US-AriaNeural" -> "Aria"
        "en-US-GuyNeural" -> "Guy"
        "en-US-JennyNeural" -> "Jenny"
        "en-US-ChristopherNeural" -> "Christopher"
        "en-GB-SoniaNeural" -> "Sonia"
        "en-GB-RyanNeural" -> "Ryan"
        "en-AU-NatashaNeural" -> "Natasha"
        "en-US-EmmaNeural" -> "Emma"
        else -> voiceId.substringAfter("-").substringBefore("Neural")
    }
}
