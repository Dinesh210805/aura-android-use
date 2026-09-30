package com.aura.aura_ui.mcp

import android.content.Context
import com.aura.mcp.bridge.TrustedClientsStore

/**
 * Builds the on-device [TrustedClientsStore] backed by SharedPreferences.
 *
 * Storage lives in the same `aura_prefs` file the legacy bare-token set used,
 * so the store's one-time migration can read `webrtc_trusted_clients` and fold
 * those tokens into the new metadata-carrying JSON blob.
 *
 * Exposed as a process singleton so the foreground service (which constructs
 * the MCP server) and the Trusted Devices screen (constructed by Compose)
 * observe the exact same instance/StateFlow.
 */
object AppTrustedClients {

    private const val PREFS = "aura_prefs"
    private const val KEY_JSON = "webrtc_trusted_clients_v2"
    private const val KEY_LEGACY = "webrtc_trusted_clients"

    @Volatile
    private var instance: TrustedClientsStore? = null

    fun get(context: Context): TrustedClientsStore {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val store = TrustedClientsStore(
                load = { prefs.getString(KEY_JSON, null) },
                save = { prefs.edit().putString(KEY_JSON, it).apply() },
                loadLegacyTokens = { prefs.getStringSet(KEY_LEGACY, emptySet()) ?: emptySet() },
            )
            instance = store
            return store
        }
    }
}
