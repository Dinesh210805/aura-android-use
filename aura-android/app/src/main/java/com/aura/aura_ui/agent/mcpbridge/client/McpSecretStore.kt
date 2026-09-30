package com.aura.aura_ui.agent.mcpbridge.client

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted per-server token store. Mirrors ProviderKeyStore; shares aura_secure_keys. */
class McpSecretStore(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "aura_secure_keys", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        Log.w("McpSecretStore", "EncryptedSharedPreferences unavailable, plain fallback", it)
        context.getSharedPreferences("aura_secure_keys", Context.MODE_PRIVATE)
    }

    fun getToken(serverId: String): String? = prefs.getString(key(serverId), null)?.takeIf { it.isNotBlank() }
    fun setToken(serverId: String, token: String) = prefs.edit().putString(key(serverId), token.trim()).apply()
    fun clearToken(serverId: String) = prefs.edit().remove(key(serverId)).apply()
    private fun key(id: String) = "mcp_token_$id"
}
