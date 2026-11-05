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
}
