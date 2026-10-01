package com.aura.aura_ui.mcp

import android.content.Context
import com.aura.mcp.bridge.TrustedClientsStore

/**
 * Builds the on-device [TrustedClientsStore] backed by SharedPreferences.
 *
 * - Contract: a process singleton, so the foreground service (which builds the MCP server) and
 *   the Trusted Devices screen observe the same instance and StateFlow.
 * - Reads/Writes: prefs file [PREFS], keys [KEY_JSON] and the legacy bare-token set
 *   [KEY_LEGACY] (folded into the JSON by the store on first read). Entries found in
 *   `aura_prefs` are moved here once, then removed there.
 * - Why a file of its own: it holds pairing tokens, which are bearer credentials for full device
 *   control, so it is excluded from cloud backup and device transfer.
 * - Change together: `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`.
 */
object AppTrustedClients {

    private const val PREFS = "aura_mcp_trust"
    private const val OLD_PREFS = "aura_prefs"
    private const val KEY_JSON = "webrtc_trusted_clients_v2"
    private const val KEY_LEGACY = "webrtc_trusted_clients"

    @Volatile
    private var instance: TrustedClientsStore? = null

    fun get(context: Context): TrustedClientsStore {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            moveOutOfOldPrefs(app, prefs)
            val store = TrustedClientsStore(
                load = { prefs.getString(KEY_JSON, null) },
                save = { prefs.edit().putString(KEY_JSON, it).apply() },
                loadLegacyTokens = { prefs.getStringSet(KEY_LEGACY, emptySet()) ?: emptySet() },
            )
            instance = store
            return store
        }
    }

    private fun moveOutOfOldPrefs(context: Context, prefs: android.content.SharedPreferences) {
        val old = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
        if (!old.contains(KEY_JSON) && !old.contains(KEY_LEGACY)) return
        prefs.edit().apply {
            if (!prefs.contains(KEY_JSON)) old.getString(KEY_JSON, null)?.let { putString(KEY_JSON, it) }
            if (!prefs.contains(KEY_LEGACY)) old.getStringSet(KEY_LEGACY, null)?.let { putStringSet(KEY_LEGACY, it) }
        }.commit()
        old.edit().remove(KEY_JSON).remove(KEY_LEGACY).apply()
    }
}
