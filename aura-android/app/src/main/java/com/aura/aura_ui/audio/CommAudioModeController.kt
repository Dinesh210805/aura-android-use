package com.aura.aura_ui.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Puts the device audio system into voice-communication mode for the cascade voice
 * loop, so the hardware AEC has a matching far-end reference and the assistant's TTS
 * is cancelled from the mic instead of re-entering it.
 *
 * Two coupled settings, mirrored from the working Gemini Live path:
 *  - [AudioManager.MODE_IN_COMMUNICATION] engages the driver-level AEC / noise
 *    suppression / AGC and ties VOICE_COMMUNICATION capture to USAGE_VOICE_COMMUNICATION
 *    playback on one acoustic echo path.
 *  - Speakerphone ON, because comm mode otherwise defaults to the earpiece and the
 *    user would barely hear the reply.
 *
 * [enter]/[exit] are idempotent and restore the prior mode + speakerphone state, so
 * leaving a conversation returns the device to normal media routing (music un-ducks).
 * Not thread-safe — drive it from the single overlay/service thread.
 */
class CommAudioModeController(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var active = false
    private var priorMode = AudioManager.MODE_NORMAL
    private var priorSpeakerphone = false

    /** Enter communication mode + speakerphone. No-op if already active. */
    fun enter() {
        if (active) return
        // MODE_IN_COMMUNICATION breaks internal-audio capture on many OEM builds even when the
        // playback usage is already capturable, so the recordable-voice switch has to disable
        // this too — otherwise the recording is still silent and the toggle looks broken.
        if (RecordableVoiceStore.isRecordable) {
            Log.i(TAG, "recordable-voice mode: skipping comm audio mode (AEC off, capture works)")
            return
        }
        priorMode = audioManager.mode
        @Suppress("DEPRECATION")
        priorSpeakerphone = audioManager.isSpeakerphoneOn
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }.onFailure { Log.w(TAG, "enter comm mode failed: ${it.message}") }
        active = true
        Log.i(TAG, "🔊 comm audio mode ON (AEC path active, speakerphone)")
    }

    /** Restore the prior mode + speakerphone. No-op if not active. */
    fun exit() {
        if (!active) return
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = priorSpeakerphone
            audioManager.mode = priorMode
        }.onFailure { Log.w(TAG, "exit comm mode failed: ${it.message}") }
        active = false
        Log.i(TAG, "🔈 comm audio mode OFF (restored)")
    }

    companion object {
        private const val TAG = "CommAudioMode"
    }
}
