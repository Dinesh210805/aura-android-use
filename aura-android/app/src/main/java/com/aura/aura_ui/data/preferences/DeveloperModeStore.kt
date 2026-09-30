package com.aura.aura_ui.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Developer mode — a *disclosure* preference, not a capability switch.
 *
 * Turning this on reveals developer-facing documentation in the app (currently
 * the `aura-adb` section on the MCP Center and Tools screens). It deliberately
 * grants nothing: `aura-adb` runs on whichever computer hosts the aura-mcp
 * daemon, against a phone already adb-paired to that machine, so the phone is
 * never consulted and could not veto a call even in principle. The real arming
 * switch is `--enable-adb` / `AURA_MCP_ENABLE_ADB=1` on the daemon host.
 *
 * Keeping that honest matters: a phone toggle that *looked* like it gated adb
 * execution would be security theatre — it would read as a boundary while
 * enforcing nothing.
 */
object DeveloperModeStore {
    private const val PREFS_NAME = "aura_settings"
    private const val KEY_ENABLED = "developer_mode_enabled"

    private val _enabled = MutableStateFlow(false)

    /** Observed by every screen that shows developer-only sections. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var hydrated = false

    /** Read the persisted value once per process; safe to call from composition. */
    fun hydrate(context: Context) {
        if (hydrated) return
        hydrated = true
        _enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        hydrated = true
        _enabled.value = value
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
