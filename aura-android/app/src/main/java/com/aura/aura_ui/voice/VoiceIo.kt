package com.aura.aura_ui.voice

/**
 * AURA's ears and mouth, with no brain: Gemini 3.8 Live ([com.aura.aura_ui.voice.live.LiveVoice]) or
 * Moonshine + Piper on the phone ([com.aura.aura_ui.voice.local.LocalVoice]).
 *
 * - Contract: the implementation decides nothing about what was said. It reports speech through
 *   [Listener] and speaks only the text given to [say]. [Listener] callbacks arrive on any thread.
 *   [open] once, then any number of [listen] / [say]; [close] ends it for good.
 * - Change together: [VoiceLoop], the only caller.
 */
interface VoiceIo {

    interface Listener {
        /** The person started or stopped speaking (local voice detection, or the server's). */
        fun onUserSpeech(started: Boolean) {}

        /** What has been heard so far of the current utterance; replaced, not appended. */
        fun onHearing(partial: String) {}

        /** One finished utterance. */
        fun onHeard(text: String) {}

        /** The words AURA has said so far of the current reply. */
        fun onSpeaking(words: String) {}

        /** A reply ended: [completed] false when it was cut off by the person or by [stopSpeaking]. */
        fun onSpoken(completed: Boolean) {}

        /** Mic or speaker level, 0..1, for the waveform. */
        fun onLevel(level: Float) {}

        /** Something the person should know (no connection, model missing). The voice stays usable when it can. */
        fun onTrouble(message: String) {}
    }

    /** Connect or load. False when this voice cannot work now; [Listener.onTrouble] says why. */
    suspend fun open(): Boolean

    /** Open or close the microphone. Closed means nothing is heard or sent anywhere. */
    fun listen(on: Boolean)

    val listening: Boolean

    /** Speak [text]. A reply already playing is replaced. */
    fun say(text: String)

    fun stopSpeaking()

    val speaking: Boolean

    fun close()
}
