package com.aura.aura_ui.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {

    @Test
    fun `splits sentences and strips markdown`() {
        assertEquals(
            listOf("Done.", "I opened Settings for you!", "Anything else?"),
            SpeechChunker.split("**Done.** I opened `Settings` for you!\n\nAnything else?"),
        )
    }

    @Test
    fun `long first sentence is cut at a clause so speech starts fast`() {
        val chunks = SpeechChunker.split(
            "Sure, I can help with that, and I will open your messages to find the latest one from Amma. Then I will read it.",
        )
        assertEquals("Sure, I can help with that,", chunks.first())
        assertTrue(chunks.first().length <= 30)
        assertEquals("Then I will read it.", chunks.last())
        assertEquals(
            "Sure, I can help with that, and I will open your messages to find the latest one from Amma. Then I will read it.",
            chunks.joinToString(" "),
        )
    }

    @Test
    fun `long first sentence without a clause break is cut at a word`() {
        val text = "I opened Spotify and started playing your liked songs. Enjoy."
        val chunks = SpeechChunker.split(text)
        assertEquals("I opened Spotify and started", chunks.first())
        assertEquals(listOf("playing your liked songs.", "Enjoy."), chunks.drop(1))
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `chunks grow gradually so the next one is ready before the current one ends`() {
        val text = "Your next meeting is the weekly design review with the platform team which starts at " +
            "three in the afternoon and runs for an hour in the large conference room on the fourth floor."
        val chunks = SpeechChunker.split(text)
        chunks.forEachIndexed { i, c -> assertTrue("chunk $i '$c'", c.length <= SpeechChunker.limitFor(i)) }
        assertTrue(chunks.size > 2)
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `markdown links keep their text and blanks are dropped`() {
        assertEquals(listOf("See the docs."), SpeechChunker.split("See the [docs](https://x.y).  \n\n  "))
        assertEquals(emptyList<String>(), SpeechChunker.split("  **  "))
    }
}
