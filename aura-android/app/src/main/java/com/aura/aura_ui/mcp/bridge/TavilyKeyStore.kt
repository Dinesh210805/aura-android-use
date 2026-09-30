package com.aura.aura_ui.mcp.bridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted on-device store for the Tavily API key used by the [AppWebSearchBridge].
 *
 * Backed by [EncryptedSharedPreferences] (AES-256 GCM) keyed by an Android-Keystore-
 * backed [MasterKey], so the key never appears in plaintext on disk. The store
 * degrades gracefully if EncryptedSharedPreferences fails to initialise (rare,
 * but happens on some manufacturer ROMs with broken keystore implementations):
 * it falls back to a plain [SharedPreferences] and logs a warning so the user
 * can still configure a key — the on-device server is loopback-only via
 * `adb forward`, so the threat model is low.
 */
class TavilyKeyStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
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
    }.getOrElse { t ->
        Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", t)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Returns the stored key, or null if the user has not configured one. */
    fun getApiKey(): String? = prefs.getString(KEY_TAVILY, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(value: String) {
        prefs.edit().putString(KEY_TAVILY, value.trim()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TAVILY).apply()
    }

    fun isConfigured(): Boolean = !getApiKey().isNullOrBlank()

    companion object {
        private const val TAG = "TavilyKeyStore"
        private const val PREFS_NAME = "aura_secure_keys"
        private const val KEY_TAVILY = "tavily_api_key"
    }
}
