package com.aura.aura_ui.voice

import com.aura.aura_ui.voice.PlaybackCaption.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCaptionTest {
    private val chunks = listOf("I'm AURA.", "I use your phone.")
    private val full = chunks.joinToString(" ")
    private val offsets = PlaybackCaption.offsets(chunks)

    // 900 frames for chunk 0, 1700 for chunk 1: 100 frames per character.
    private val segments = listOf(
        Segment(startFrame = 0, frames = 900, charStart = offsets[0], chars = chunks[0].length),
        Segment(startFrame = 900, frames = 1700, charStart = offsets[1], chars = chunks[1].length),
    )

    @Test fun `nothing shows before audio plays`() {
        assertEquals("", PlaybackCaption.at(full, segments, head = 0))
    }

    @Test fun `the word being spoken shows as it starts`() {
        assertEquals("I'm", PlaybackCaption.at(full, segments, head = 1))
    }

    @Test fun `the heard chunk is captioned even when the next one is already written`() {
        assertEquals("I'm AURA.", PlaybackCaption.at(full, segments, head = 880))
    }

    @Test fun `the next chunk takes over when its audio starts`() {
        assertEquals("I", PlaybackCaption.at(full, segments, head = 905))
    }

    @Test fun `the end of the audio shows the whole last sentence`() {
        assertEquals("I use your phone.", PlaybackCaption.at(full, segments, head = 2600))
    }

    @Test fun `a sentence split across chunks stays whole`() {
        val parts = listOf("I tap, type, scroll,", "and move between apps.")
        val text = parts.joinToString(" ")
        val at = PlaybackCaption.offsets(parts)
        val segs = listOf(
            Segment(0, 2000, at[0], parts[0].length),
            Segment(2000, 2200, at[1], parts[1].length),
        )
        assertEquals(text, PlaybackCaption.at(text, segs, head = 4200))
    }

    @Test fun `offsets match the joined text`() {
        assertEquals(chunks[1], full.substring(offsets[1], offsets[1] + chunks[1].length))
    }

    @Test fun `a partially revealed word is held back until it is whole`() {
        assertEquals("Only", PlaybackCaption.sentence("Only one thing", spoken = 7))
    }

    @Test fun `the reveal advances a whole word at a time`() {
        assertEquals("Only one", PlaybackCaption.sentence("Only one thing", spoken = 11))
    }

    @Test fun `the last word is never held back once everything has arrived`() {
        val full = "Only one"
        assertEquals(full, PlaybackCaption.sentence(full, spoken = full.length))
    }

    @Test fun `nothing shows before the first word is complete`() {
        assertEquals("", PlaybackCaption.sentence("Only one thing", spoken = 2))
    }

    @Test fun `the caption restarts at the sentence being spoken`() {
        val full = "Hey there. This is AURA speaking"
        assertEquals("This is AURA", PlaybackCaption.sentence(full, spoken = 27))
    }

    @Test fun `a reply ending in a full stop shows its last word`() {
        val full = "I use your phone."
        assertEquals(full, PlaybackCaption.sentence(full, spoken = full.length))
    }

    @Test fun `a sentence keeps its last word until the next one starts`() {
        assertEquals("Hey there.", PlaybackCaption.sentence("Hey there. This is AURA", spoken = 11))
    }

    @Test fun `a closing quote does not start a new sentence`() {
        val full = "\"This is why people come to NASA.\" - @nasaadmin"
        assertEquals(full, PlaybackCaption.sentence(full, spoken = full.length))
    }

    @Test fun `dots inside a word or number do not start a new sentence`() {
        val full = "Cruise at Mach .75, details at nasa.gov/aero now"
        assertEquals(full, PlaybackCaption.sentence(full, spoken = full.length))
    }
}
