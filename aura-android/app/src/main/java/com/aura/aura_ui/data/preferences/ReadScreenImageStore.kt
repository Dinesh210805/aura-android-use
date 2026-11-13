package com.aura.aura_ui.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether `read_screen` sends an annotated screenshot alongside its character grid.
 * Off by default: the model asks with `image=true` when it needs a picture, and each image costs
 * far more tokens and time than the grid. Same shape as [DeveloperModeStore]; hydrated in `AuraApplication` because
 * the MCP servers read it from services that never open a Compose screen.
 */
object ReadScreenImageStore {
    private const val PREFS_NAME = "aura_settings"
    private const val KEY_ENABLED = "read_screen_image_enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var hydrated = false

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
