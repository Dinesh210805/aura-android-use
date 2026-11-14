package com.aura.aura_ui.agentcore

import android.content.Context
import com.aura.aura_ui.audio.AuraTTSManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reads one of AURA's replies aloud in the voice chosen in Voice settings.
 * - Contract: one reply at a time; reading another, or the same one again, stops the first.
 *   [reading] is the text being read, or null.
 * - Reads/Writes: prefs `aura_voice_settings` key `selected_voice_id` (read only).
 * - Why one engine for the process: system TTS needs a moment to start, so the chat warms it on
 *   open ([warm]) and keeps it, rather than making one per tap and losing the first words.
 */
object ReadAloud {
    private var tts: AuraTTSManager? = null
    private val _reading = MutableStateFlow<String?>(null)
    val reading: StateFlow<String?> get() = _reading

    @Synchronized
    fun warm(context: Context): AuraTTSManager = tts ?: AuraTTSManager(context.applicationContext).also { tts = it }

    fun toggle(context: Context, text: String) {
        val engine = warm(context)
        if (_reading.value == text) return stop()
        _reading.value = text
        val voice = context.getSharedPreferences("aura_voice_settings", Context.MODE_PRIVATE).getString("selected_voice_id", null).orEmpty()
        engine.speak(plainText(text), voice) { _reading.compareAndSet(text, null) }
    }

    fun stop() {
        tts?.stop()
        _reading.value = null
    }
}
