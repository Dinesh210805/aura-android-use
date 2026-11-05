package com.aura.aura_ui.audio

import android.content.Context
import android.media.AudioAttributes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Developer switch: make the Gemini Live voice recordable by a screen recorder.
 *
 * - Why it exists: [PcmStreamPlayer] plays Live audio as `USAGE_VOICE_COMMUNICATION`, which hands the
 *   hardware AEC a far-end reference so the user can barge in. Android's playback-capture API (what
 *   screen recorders use) only captures `USAGE_MEDIA`, `USAGE_GAME` and `USAGE_UNKNOWN`, so that same
 *   usage makes demo recordings silent.
 * - On: Live plays as `USAGE_MEDIA` (capturable). Cost: no AEC reference, so Live can hear itself and
 *   interrupt its own sentence. That is why it is off by default and behind developer mode.
 * - Not involved: the on-device voice (`AuraTTSManager`, `PiperSpeaker`) always plays as media.
 * - Reads/Writes: prefs `aura_settings` key `recordable_voice_enabled`; hydrated in
 *   `AuraApplication.onCreate` so service-started processes see the right value.
 */
object RecordableVoiceStore {
    private const val PREFS_NAME = "aura_settings"
    private const val KEY_ENABLED = "recordable_voice_enabled"

    private val _enabled = MutableStateFlow(false)

    /** Observed by the developer-mode UI. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** True when playback should use the capturable (but AEC-blind) media route. */
    val isRecordable: Boolean get() = _enabled.value

    /** The `AudioAttributes` usage [PcmStreamPlayer] builds with. */
    fun audioUsage(): Int =
        if (isRecordable) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION

    /** Read the persisted value once per process. Called from `AuraApplication.onCreate`. */
    fun hydrate(context: Context) {
        _enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        _enabled.value = value
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
