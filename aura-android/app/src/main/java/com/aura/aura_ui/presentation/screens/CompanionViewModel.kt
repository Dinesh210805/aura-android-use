package com.aura.aura_ui.presentation.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import com.aura.aura_ui.agent.conversation.CompanionConfig
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
 * Settings → Voice: the voice-mode selector. The Gemini key itself is entered on the API keys page.
 * The two toggles map onto a single boolean (`useGeminiLive`) so exactly one mode
 * is always active; Gemini Live requires a saved key, otherwise TTS/STT holds.
 */
class CompanionViewModel(context: Context) : ViewModel() {
    private val app = context.applicationContext
    private val config = CompanionConfig(app)
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
            classicVoice = com.aura.aura_ui.voice.LocalVoices.byId(
                voicePrefs.getString("selected_voice_id", null),
            ).name,
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
}
