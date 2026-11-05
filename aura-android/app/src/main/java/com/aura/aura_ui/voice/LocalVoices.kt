package com.aura.aura_ui.voice

/**
 * The on-device voices the user can pick, shared by the Voice screen, `AuraTTSManager` and the Companion screen.
 *
 * - Contract: [Voice.id] is what is stored in prefs `aura_voice_settings` / `selected_voice_id`; it keeps its
 *   edge-tts form so saved selections survive. Each voice is a separate Piper download ([Voice.model]).
 * - Change together: `AuraTTSManager.voiceProfiles` (system-TTS fallback, keyed by the same ids).
 */
object LocalVoices {
    data class Voice(val id: String, val name: String, val accent: String, val gender: String, val piper: String) {
        val model: LocalVoiceModels.Model get() = LocalVoiceModels.piper(piper)
    }

    const val DEFAULT_ID = "en-US-AriaNeural"
    const val PREVIEW_TEXT = "Hi, I'm AURA. Tell me what you need and I'll take care of it."

    val all = listOf(
        Voice("en-US-AriaNeural", "Lessac", "American", "Female", "en_US-lessac-medium"),
        Voice("en-US-JennyNeural", "Amy", "American", "Female", "en_US-amy-medium"),
        Voice("en-US-GuyNeural", "Ryan", "American", "Male", "en_US-ryan-medium"),
        Voice("en-US-ChristopherNeural", "Joe", "American", "Male", "en_US-joe-medium"),
        Voice("en-GB-SoniaNeural", "Cori", "British", "Female", "en_GB-cori-medium"),
        Voice("en-GB-RyanNeural", "Alan", "British", "Male", "en_GB-alan-medium"),
    )

    fun byId(id: String?): Voice = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }
}
