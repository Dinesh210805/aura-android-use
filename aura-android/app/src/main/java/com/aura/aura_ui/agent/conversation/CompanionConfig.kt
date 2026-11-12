package com.aura.aura_ui.agent.conversation

import android.content.Context
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore

/**
 * BYOK + opt-in gate for the companion. The Gemini key is reused from ProviderKeyStore (AES-256, never
 * logged); a null key means the companion is simply unavailable (surfaced in Settings, never a crash).
 * `enabled` defaults false: with the companion off, nothing here runs and behaviour is byte-for-byte
 * today's. The Live model is fixed: Gemini 3.8 Live (`LiveWire.MODEL`).
 */
class CompanionConfig(context: Context) {
    private val app = context.applicationContext
    private val keys = ProviderKeyStore(app)
    private val prefs = app.getSharedPreferences("aura_companion_prefs", Context.MODE_PRIVATE)
    private val voicePrefs = app.getSharedPreferences("aura_voice_settings", Context.MODE_PRIVATE)
    // Master voice-mode switch, shared with AuraOverlayService which reads the same
    // key to decide Gemini Live vs classic TTS/STT. Kept here so the Companion screen
    // owns the whole voice-mode surface in one config object.
    private val settingsPrefs = app.getSharedPreferences("aura_settings", Context.MODE_PRIVATE)

    fun byokKey(): String? = keys.getKey(LlmEndpointCatalog.GEMINI_ID)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    /**
     * True ⇒ voice runs through Gemini Live; false ⇒ classic TTS/STT. This is the
     * single boolean behind the Companion screen's two mutually-exclusive toggles.
     */
    var useGeminiLive: Boolean
        get() = settingsPrefs.getBoolean(KEY_USE_GEMINI_LIVE, false)
        set(v) { settingsPrefs.edit().putBoolean(KEY_USE_GEMINI_LIVE, v).apply() }

    fun voice(): String = voicePrefs.getString("gemini_live_voice", "Achird") ?: "Achird"

    companion object {
        private const val KEY_ENABLED = "companion_enabled"
        private const val KEY_USE_GEMINI_LIVE = "use_gemini_live"
    }
}
