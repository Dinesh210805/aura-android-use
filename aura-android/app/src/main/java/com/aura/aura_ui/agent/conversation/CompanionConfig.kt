package com.aura.aura_ui.agent.conversation

import android.content.Context
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore

/**
 * BYOK + opt-in gate for the companion. The Gemini key is reused from ProviderKeyStore (AES-256, never
 * logged); a null key means the companion is simply unavailable (surfaced in Settings, never a crash).
 * `enabled` defaults false: with the companion off, nothing here runs and behaviour is byte-for-byte
 * today's. Model id is a single constant (V-2: BYOK gates availability; flip to the half-cascade
 * fallback if on-device validation shows flaky drive_phone calls).
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

    /**
     * User's explicit Live model choice (Settings -> Voice -> Gemini Live model), or null to let
     * [LiveModelResolver] pick automatically from whatever the key exposes. Null is the default so
     * a fresh install behaves exactly as before this picker existed.
     */
    var liveModel: String?
        get() = prefs.getString(KEY_LIVE_MODEL, null)
        set(v) { prefs.edit().putString(KEY_LIVE_MODEL, v?.takeIf { it.isNotBlank() }).apply() }

    companion object {
        /**
         * The Live model AURA prefers (owner's choice, 2026-07-29).
         *
         * This is both the default AND the top preference in [LiveModelResolver.pick] — the
         * resolver checks whether the user's key exposes it and only falls back to native-audio /
         * other live ids when it does not. Before this it was purely a fallback constant that the
         * resolver ignored whenever ListModels succeeded, so changing it appeared to do nothing.
         *
         * Note for the async-function-calling path: Google's docs at time of writing said async
         * (NON_BLOCKING) function calling was "not yet supported" on 3.1 Flash Live. We attempt it
         * anyway — such notes age quickly and the model ids churn — and demote to the blocking path
         * automatically if the server rejects it. See [LiveTaskTracker.demoteToBlocking].
         */
        const val LIVE_MODEL = "models/gemini-3.1-flash-live-preview"

        /** Native-audio alternative, kept as the quality/free-tier option if 3.1 is unavailable. */
        const val LIVE_MODEL_NATIVE_AUDIO = "models/gemini-2.5-flash-native-audio-latest"
        const val TRIGGER_TOKENS = 25_000
        const val SLIDING_WINDOW = 8_000
        const val THINKING_LEVEL = "minimal"
        /**
         * How long the server waits for silence before deciding the user's turn ended. It is paid
         * in full on EVERY reply, so it is a direct latency lever: 600 ms was measured as pure dead
         * air on top of the ~2.1 s think+speak time. 400 ms keeps a natural mid-sentence pause from
         * cutting the user off while giving back 200 ms per turn.
         */
        const val SILENCE_DURATION_MS = 400
        const val DEFAULT_LANGUAGE = "en-US"
        private const val KEY_ENABLED = "companion_enabled"
        private const val KEY_USE_GEMINI_LIVE = "use_gemini_live"
        private const val KEY_LIVE_MODEL = "live_model"
    }
}
