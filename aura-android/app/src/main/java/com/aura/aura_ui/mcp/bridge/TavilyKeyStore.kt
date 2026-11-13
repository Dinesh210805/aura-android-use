package com.aura.aura_ui.mcp.bridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted on-device store for the Tavily API key used by the [AppWebSearchBridge].
 *
 * Backed by [EncryptedSharedPreferences] (AES-256 GCM) keyed by an Android-Keystore-backed
 * [MasterKey], so the key never appears in plain text on disk.
 *
 * - Fails: closed, as [ProviderKeyStore]: with no working Keystore nothing is stored or read.
 */
class TavilyKeyStore(context: Context) {

    private val prefs: SharedPreferences? = runCatching {
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
        Log.w(TAG, "EncryptedSharedPreferences unavailable; the Tavily key cannot be stored", t)
        null
    }

    /** Returns the stored key, or null if the user has not configured one. */
    fun getApiKey(): String? = prefs?.getString(KEY_TAVILY, null)?.takeIf { it.isNotBlank() }

    /** False, and nothing written, when encryption is unavailable. */
    fun setApiKey(value: String): Boolean {
        val store = prefs ?: return false
        store.edit().putString(KEY_TAVILY, value.trim()).apply()
        return true
    }

    fun clear() {
        prefs?.edit()?.remove(KEY_TAVILY)?.apply()
    }

    fun isConfigured(): Boolean = !getApiKey().isNullOrBlank()

    companion object {
        private const val TAG = "TavilyKeyStore"
        private const val PREFS_NAME = "aura_secure_keys"
        private const val KEY_TAVILY = "tavily_api_key"
    }
}
