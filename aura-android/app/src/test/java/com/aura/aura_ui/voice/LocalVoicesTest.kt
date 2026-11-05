package com.aura.aura_ui.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVoicesTest {

    /**
     * Each Piper export names an espeak voice (`en-us`, `en-gb-x-rp` or `en`, from its `.onnx.json`)
     * and fails to load without that voice's `lang/gmw` file — the British voices need `en-GB-x-rp`.
     */
    @Test
    fun `every voice downloads its model, tokens and every English espeak lang file`() {
        for (voice in LocalVoices.all) {
            val files = voice.model.files
            assertEquals("${voice.piper}.onnx", files.first())
            assertTrue(voice.name, "tokens.txt" in files)
            for (lang in listOf("en", "en-US", "en-GB-x-rp")) {
                assertTrue("${voice.name} lacks $lang", "espeak-ng-data/lang/gmw/$lang" in files)
            }
        }
    }

    @Test
    fun `unknown or retired saved ids fall back to the default voice`() {
        assertEquals(LocalVoices.DEFAULT_ID, LocalVoices.byId("en-AU-NatashaNeural").id)
        assertEquals(LocalVoices.DEFAULT_ID, LocalVoices.byId(null).id)
        assertEquals(LocalVoices.all.size, LocalVoices.all.map { it.id }.toSet().size)
    }
}
