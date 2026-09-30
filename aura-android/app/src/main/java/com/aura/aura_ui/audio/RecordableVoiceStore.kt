package com.aura.aura_ui.audio

import android.content.Context
import android.media.AudioAttributes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Developer switch: make AURA's voice recordable by a screen recorder.
 *
 * ## Why a switch is needed at all
 *
 * Both output paths ([PcmStreamPlayer] for Gemini Live, [AuraTTSManager] for the cascade voice)
 * normally play as `USAGE_VOICE_COMMUNICATION`. That is not cosmetic: it is what hands the
 * hardware AEC a far-end reference, so the mic can subtract AURA's own voice and server VAD can
 * still hear the user barge in.
 *
 * Android's playback-capture API — what every screen recorder uses for internal audio — can only
 * capture `USAGE_MEDIA`, `USAGE_GAME` and `USAGE_UNKNOWN`. `VOICE_COMMUNICATION` is treated as
 * call audio and is unrecordable by design; no manifest flag or permission changes that. So the
 * *same* property that makes barge-in work is the one that makes demo recordings silent.
 *
 * ## What flipping this on does, and costs
 *
 *  - Playback moves to `USAGE_MEDIA`, which a screen recorder can capture.
 *  - [CommAudioModeController] becomes a no-op, because `MODE_IN_COMMUNICATION` breaks capture on
 *    many OEM builds even when the usage is right.
 *  - **Barge-in degrades.** With no AEC reference, AURA hears itself through the mic and can
 *    interrupt its own sentence. That is the trade; it is why this is off by default and lives
 *    behind developer mode rather than in normal Settings.
 *
 * Loudspeaker routing is safe either way: `USAGE_MEDIA` goes to the speaker on its own, which is
 * exactly what it did before the AEC work moved routing into the controller.
 *
 * Hydrated from `AuraApplication.onCreate`, so the value is correct in every entry point —
 * including a service-started process where no Compose screen ever runs.
 */
object RecordableVoiceStore {
    private const val PREFS_NAME = "aura_settings"
    private const val KEY_ENABLED = "recordable_voice_enabled"

    private val _enabled = MutableStateFlow(false)

    /** Observed by the developer-mode UI. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** True when playback should use the capturable (but AEC-blind) media route. */
    val isRecordable: Boolean get() = _enabled.value

    /**
     * The `AudioAttributes` usage both output paths should build with. One accessor so the two
     * players can never disagree — a mismatch would leave one voice recordable and the other not.
     */
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
