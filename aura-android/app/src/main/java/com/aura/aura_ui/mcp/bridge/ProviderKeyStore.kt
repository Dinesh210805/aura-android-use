package com.aura.aura_ui.mcp.bridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aura.aura_ui.agent.llm.GenerationConfig
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encrypted on-device store for the on-device agent's **LLM endpoint** secrets and
 * selection: one API key per endpoint id, plus the currently selected endpoint and
 * its model id (D2).
 *
 * Keyed by endpoint **id string** — any id in [LlmEndpointCatalog.BUILTINS], or `"custom:<slug>"`.
 * The five original built-in ids (`groq`, `openrouter`, `gemini`, `openai`, `anthropic`) equal the
 * pre-D2 lowercased enum names, so `keyNameFor` reproduces the old pref names
 * (`provider_key_groq`, …) exactly — a user upgrading keeps every saved key with zero migration.
 * Presets added later carry no such constraint; they simply must never collide with those five.
 *
 * Mirrors [TavilyKeyStore] exactly (AES-256 GCM via an Android-Keystore [MasterKey],
 * with a graceful plain-prefs fallback on ROMs with broken keystores) and shares
 * the same `aura_secure_keys` prefs file — only the key names differ, so provider
 * keys live alongside the Tavily/Exa keys without a second encrypted store.
 */
class ProviderKeyStore(context: Context) {

    /**
     * B2 — whether the API keys are actually at-rest encrypted. False means the Keystore was broken
     * and we fell back to plaintext prefs; the Settings UI should warn the user (their provider keys
     * are stored in the clear) rather than reporting silent success. Failing closed is wrong here —
     * it would make the app unusable on a broken ROM — so keys are still stored, but not silently.
     */
    val isEncrypted: Boolean

    private val prefs: SharedPreferences

    init {
        val encrypted = runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        isEncrypted = encrypted.isSuccess
        prefs = encrypted.getOrElse { t ->
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", t)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** Per-endpoint API key. Returns null when the user has not configured one. */
    fun getKey(endpointId: String): String? =
        prefs.getString(keyNameFor(endpointId), null)?.takeIf { it.isNotBlank() }

    fun setKey(endpointId: String, value: String) {
        prefs.edit().putString(keyNameFor(endpointId), value.trim()).apply()
    }

    fun clearKey(endpointId: String) {
        prefs.edit().remove(keyNameFor(endpointId)).apply()
    }

    fun isConfigured(endpointId: String): Boolean = !getKey(endpointId).isNullOrBlank()

    /** The endpoint the agent should use. Defaults to Groq; legacy uppercase values are normalized. */
    fun getSelectedEndpointId(): String =
        prefs.getString(KEY_SELECTED_PROVIDER, null)?.let { normalizeSelectedId(it) }
            ?: LlmEndpointCatalog.GROQ_ID

    fun setSelectedEndpointId(endpointId: String) {
        prefs.edit().putString(KEY_SELECTED_PROVIDER, endpointId).apply()
    }

    /** The model id selected for [endpointId], or null if none chosen yet. */
    fun getSelectedModel(endpointId: String): String? =
        prefs.getString(keyModelFor(endpointId), null)?.takeIf { it.isNotBlank() }

    fun setSelectedModel(endpointId: String, modelId: String) {
        prefs.edit().putString(keyModelFor(endpointId), modelId.trim()).apply()
    }

    /**
     * The selected model's context window in tokens, or null when the provider never told us.
     *
     * Stored at model-selection time because that is the only moment the number is in hand:
     * `ModelCatalog.fetch` reads it from the provider's own `/models` listing (`context_window` on
     * the OpenAI shape, `inputTokenLimit` on Gemini's), and a run cannot afford a network call to
     * re-learn it. Providers that publish no limit — and every custom endpoint — simply leave this
     * unset, which callers must treat as "unknown" rather than "zero".
     */
    fun getSelectedModelContextWindow(endpointId: String): Int? =
        prefs.getInt(keyCtxFor(endpointId), 0).takeIf { it > 0 }

    /**
     * Whether the selected model can accept image parts — **tri-state on purpose**.
     *
     * `true` / `false` when the provider's `/models` listing told us; `null` when it did not, which
     * includes every custom endpoint. Callers must treat null as "declare vision anyway": a false
     * negative silently disables screen automation, which is a worse failure than one honest 400.
     *
     * Stored at model-selection time for the same reason as the context window — that is the only
     * moment the fact is in hand, and a run cannot afford a network call to re-learn it.
     */
    fun getSelectedModelVisionCapable(endpointId: String): Boolean? =
        if (prefs.contains(keyVisionFor(endpointId))) prefs.getBoolean(keyVisionFor(endpointId), true) else null

    fun setSelectedModelVisionCapable(endpointId: String, visionCapable: Boolean?) {
        prefs.edit().apply {
            if (visionCapable != null) putBoolean(keyVisionFor(endpointId), visionCapable)
            else remove(keyVisionFor(endpointId))
        }.apply()
    }

    fun setSelectedModelContextWindow(endpointId: String, contextWindow: Int?) {
        prefs.edit().apply {
            if (contextWindow != null && contextWindow > 0) putInt(keyCtxFor(endpointId), contextWindow)
            else remove(keyCtxFor(endpointId))
        }.apply()
    }

    /**
     * The per-endpoint generation controls (thinking level + temperature, D2 piece 2). Absent or
     * corrupt storage decodes to [GenerationConfig] defaults — so an upgrading user, or one who
     * never opened the controls, drives the agent with a byte-identical request to before.
     */
    fun getGeneration(endpointId: String): GenerationConfig =
        decodeGeneration(prefs.getString(keyGenFor(endpointId), null))

    fun setGeneration(endpointId: String, config: GenerationConfig) {
        prefs.edit().putString(keyGenFor(endpointId), JSON.encodeToString(config)).apply()
    }

    /**
     * The user's opt-in per-run request cap for this endpoint, or null for "no cap" (the default).
     *
     * Per-endpoint rather than global because the thing it protects is per-key: a free tier with a
     * daily request allowance wants a cap so one runaway run cannot spend the day, and a paid key
     * wants nothing of the sort. The app cannot tell which a given key is, so it asks.
     *
     * Stored as 0-means-unset rather than a nullable, matching [getSelectedModelContextWindow] —
     * a cap of zero would mean a run that cannot make its first call, so the value is not lost.
     */
    fun getRunMaxRequests(endpointId: String): Int? =
        prefs.getInt(keyRunCapFor(endpointId), 0).takeIf { it > 0 }

    fun setRunMaxRequests(endpointId: String, maxRequests: Int?) {
        prefs.edit().apply {
            if (maxRequests != null && maxRequests > 0) putInt(keyRunCapFor(endpointId), maxRequests)
            else remove(keyRunCapFor(endpointId))
        }.apply()
    }

    companion object {
        private const val TAG = "ProviderKeyStore"
        // Shared with TavilyKeyStore — same encrypted file, distinct key names.
        private const val PREFS_NAME = "aura_secure_keys"
        private const val KEY_SELECTED_PROVIDER = "provider_selected"

        private val JSON = Json { ignoreUnknownKeys = true }

        // Pref-name derivation kept in the companion + PUBLIC so the migration contract is
        // unit-tested without an Android runtime. Built-in endpoint ids are the legacy lowercased
        // enum names, so these reproduce the pre-D2 pref names exactly ("provider_key_groq", …) —
        // a user upgrading keeps their saved key.
        fun keyNameFor(endpointId: String) = "provider_key_$endpointId"
        fun keyModelFor(endpointId: String) = "provider_model_$endpointId"
        fun keyGenFor(endpointId: String) = "provider_gen_$endpointId"
        fun keyCtxFor(endpointId: String) = "provider_ctx_$endpointId"
        fun keyVisionFor(endpointId: String) = "provider_vision_$endpointId"
        fun keyRunCapFor(endpointId: String) = "provider_runcap_$endpointId"

        /**
         * Decode stored generation JSON, failing safe to defaults. PUBLIC + pure so the
         * absent/corrupt → default contract is unit-tested without an Android runtime.
         */
        fun decodeGeneration(raw: String?): GenerationConfig =
            raw?.let { runCatching { JSON.decodeFromString<GenerationConfig>(it) }.getOrNull() }
                ?: GenerationConfig()

        /**
         * The legacy `provider_selected` value was the UPPERCASE enum name ("GROQ"); the new value
         * is the endpoint id ("groq"). A value that matches a legacy built-in name (case-insensitively)
         * maps to that built-in's id; anything else (a real endpoint id incl. "custom:…") passes through.
         */
        fun normalizeSelectedId(stored: String): String =
            LlmEndpointCatalog.builtinById(stored.lowercase())?.id ?: stored
    }
}
