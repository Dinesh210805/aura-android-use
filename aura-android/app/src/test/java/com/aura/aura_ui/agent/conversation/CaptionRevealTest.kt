package com.aura.aura_ui.agent.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The caption's reveal point — the half of the strip fix that unfreezes a stuck caption.
 *
 * The bug these pin: `PcmStreamPlayer.playedSeconds()` reads the AudioTrack's playback head, and
 * `stop()` releases the track, so the moment a turn's audio ends the clock reads 0f again. Taken
 * literally that is "nothing has been spoken yet", and the caption stopped dead on whatever the
 * last tick had published — `AURA  Hey, Dines`, one letter short, for four seconds.
 */
class CaptionRevealTest {

    private val sentence = "Hey, Dinesh."

    @Test fun `while the speaker runs, the reveal follows the playback head`() {
        // 0.4 s at 15 chars/s = 6 characters.
        assertEquals(
            6,
            CaptionReveal.next(
                previous = 0, playing = true, heardAnything = true,
                playedSeconds = 0.4f, fullLength = sentence.length,
            ),
        )
    }

    /**
     * THE FREEZE. The player is gone and its clock has reset to zero; everything that was going
     * to be spoken already has been, so the reveal belongs at the end — not back at nothing.
     */
    @Test fun `once the speaker is released the reveal snaps to the end`() {
        assertEquals(
            sentence.length,
            CaptionReveal.next(
                previous = 10, playing = false, heardAnything = true,
                playedSeconds = 0f, fullLength = sentence.length,
            ),
        )
    }

    /**
     * `!playing` is also true before the FIRST frame of a turn, because transcript deltas arrive
     * ahead of the audio. Snapping there would dump the whole reply on screen in front of the
     * voice — the exact failure an earlier pacer had, for a different reason.
     */
    @Test fun `before any audio has played, nothing is revealed`() {
        assertEquals(
            0,
            CaptionReveal.next(
                previous = 0, playing = false, heardAnything = false,
                playedSeconds = 0f, fullLength = sentence.length,
            ),
        )
    }

    /**
     * The drain restarts the player for the next turn and the playback head starts from zero, so
     * a reveal that could move backwards would rewind the caption mid-sentence.
     */
    @Test fun `the reveal never moves backwards when the clock resets`() {
        assertEquals(
            9,
            CaptionReveal.next(
                previous = 9, playing = true, heardAnything = true,
                playedSeconds = 0f, fullLength = sentence.length,
            ),
        )
    }

    /**
     * THE LOST TAIL. `CHARS_PER_SECOND` is an estimate, so when it runs slow the reveal is
     * still short of the text at the moment the audio ends. The old code jumped straight to
     * `fullLength`, which skipped every word between — the caption landed on the last
     * sentence and the ones the user had not read yet were simply never drawn.
     *
     * Catching up draws them. One tick moves the reveal by a bounded step, not to the end.
     */
    @Test fun `a long tail is caught up to, not jumped over`() {
        val long = "Okay. I have opened YouTube and the home feed is loading now."
        assertEquals(
            14,
            CaptionReveal.next(
                previous = 10, playing = false, heardAnything = true,
                playedSeconds = 0f, fullLength = long.length,
            ),
        )
    }

    /**
     * The catch-up must TERMINATE. The freeze it replaced was a caption stopping one letter
     * short for four seconds, so a step that could stall would be that bug wearing a hat.
     */
    @Test fun `the catch-up always reaches the end`() {
        val full = "Okay. I have opened YouTube and the home feed is loading now."
        var revealed = 0
        repeat(100) {
            revealed = CaptionReveal.next(
                previous = revealed, playing = false, heardAnything = true,
                playedSeconds = 0f, fullLength = full.length,
            )
        }
        assertEquals(full.length, revealed)
    }

    /** A snap cannot shrink a reveal that already ran past a still-growing buffer either. */
    @Test fun `the snap is a floor, not an assignment`() {
        assertEquals(
            20,
            CaptionReveal.next(
                previous = 20, playing = false, heardAnything = true,
                playedSeconds = 0f, fullLength = 12,
            ),
        )
    }

    // ── whole-word reveal ──────────────────────────────────────────────────
    //
    // The reveal point is characters-per-second times seconds heard, so it lands mid-word on
    // most ticks. Published raw it clipped the word being spoken — the user reported "one"
    // showing as "on" — and repainted the view ~12x/s with one-letter deltas, which is what
    // made the strip read as jittery rather than smooth.

    @Test fun `a partially revealed word is held back until it is whole`() {
        assertEquals("Only", CaptionReveal.sentence("Only one thing", spoken = 7))
    }

    @Test fun `the reveal advances a whole word at a time`() {
        assertEquals("Only one", CaptionReveal.sentence("Only one thing", spoken = 11))
    }

    @Test fun `the last word is never held back once everything has arrived`() {
        val full = "Only one"
        assertEquals(full, CaptionReveal.sentence(full, spoken = full.length))
    }

    @Test fun `nothing shows before the first word is complete`() {
        assertEquals("", CaptionReveal.sentence("Only one thing", spoken = 2))
    }

    @Test fun `the caption restarts at the sentence being spoken`() {
        val full = "Hey there. This is AURA speaking"
        assertEquals("This is AURA", CaptionReveal.sentence(full, spoken = 27))
    }
}
