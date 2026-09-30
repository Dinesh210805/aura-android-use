package com.aura.aura_ui.uistream

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * On/off for [UiStreamServer]. Off by default, and deliberately so: the stream carries
 * every label on the user's screen, so it must be something they turned on, not
 * something that was already running when they looked.
 *
 * Same shape as [com.aura.aura_ui.data.preferences.DeveloperModeStore] — a hydrated
 * [StateFlow] over one SharedPreferences boolean — because the accessibility service
 * and a Compose screen both need to observe it and neither owns the other.
 */
object UiStreamStore {
    private const val PREFS_NAME = "aura_settings"
    private const val KEY_ENABLED = "ui_stream_enabled"

    private val _enabled = MutableStateFlow(false)

    /** Observed by the accessibility service and by the settings row. */
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
