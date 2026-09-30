package com.aura.aura_ui.mcp

import android.content.Context
import com.aura.mcp.bridge.RestrictedAppsStore

/**
 * Builds the on-device [RestrictedAppsStore] backed by SharedPreferences.
 *
 * Storage lives in the same `aura_prefs` file [AppTrustedClients] uses, so all
 * of AURA's small user-managed lists stay in one place.
 *
 * Exposed as a process singleton so the Restricted Apps settings screen and the
 * (Phase 2) foreground-gate enforcement observe the exact same instance/StateFlow.
 */
object AppRestrictedApps {

    private const val PREFS = "aura_prefs"
    private const val KEY_JSON = "restricted_apps_v1"

    @Volatile
    private var instance: RestrictedAppsStore? = null

    fun get(context: Context): RestrictedAppsStore {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val store = RestrictedAppsStore(
                load = { prefs.getString(KEY_JSON, null) },
                save = { prefs.edit().putString(KEY_JSON, it).apply() },
            )
            instance = store
            return store
        }
    }
}
