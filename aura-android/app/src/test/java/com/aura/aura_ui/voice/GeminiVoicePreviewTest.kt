package com.aura.aura_ui.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiVoicePreviewTest {
    @Test
    fun `prefers a flash tts model`() {
        val names = listOf("models/gemini-2.5-flash", "models/gemini-2.5-pro-preview-tts", "models/gemini-2.5-flash-preview-tts")
        assertEquals("models/gemini-2.5-flash-preview-tts", GeminiVoicePreview.pickTtsModel(names))
    }

    @Test
    fun `falls back to any tts model`() {
        assertEquals("models/x-pro-tts", GeminiVoicePreview.pickTtsModel(listOf("models/gemini-2.5-flash", "models/x-pro-tts")))
    }

    @Test
    fun `null when the key has no tts model`() {
        assertNull(GeminiVoicePreview.pickTtsModel(listOf("models/gemini-2.5-flash")))
    }
}
