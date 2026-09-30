package com.aura.aura_ui.presentation.overlay

import com.aura.aura_ui.presentation.overlay.StatusStripVisibility.Action
import com.aura.aura_ui.presentation.overlay.StatusStripVisibility.decide
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The caption strip's one decision, pinned.
 *
 * Worth a test because the bug it replaces was not a wrong branch — it was the branch not
 * existing: "allowed on screen" and "has something to say right now" were a single `if`, so a
 * momentary blank between two spoken sentences tore the whole `WindowManager` window down and
 * built it back 26 ms later. Every case below is one of the four inputs that used to be able
 * to answer this question on its own.
 */
class StatusStripVisibilityTest {

    @Test fun `content shows the strip at once`() {
        assertEquals(Action.SHOW, decide(enabled = true, hasContent = true, hiddenForCapture = false))
    }

    /**
     * THE BUG. A blank publish — the transcript pacer between sentences, `clearSpeech()`,
     * `AuraTTSManager` finishing an utterance — must not be answered with an immediate hide,
     * because the next line is typically milliseconds away.
     */
    @Test fun `an empty moment is given time to come back before the strip goes dark`() {
        assertEquals(
            Action.HIDE_AFTER_LINGER,
            decide(enabled = true, hasContent = false, hiddenForCapture = false),
        )
    }

    /**
     * No grace here, and the asymmetry is the point: a late hide costs a frame of caption, while
     * a late hide DURING A CAPTURE puts the strip in the screenshot the agent then reasons about
     * — which is the whole reason `OverlayCaptureGate` exists.
     */
    @Test fun `a capture hides immediately, with no linger`() {
        assertEquals(
            Action.HIDE_NOW,
            decide(enabled = true, hasContent = true, hiddenForCapture = true),
        )
    }

    @Test fun `a capture outranks content, present or absent`() {
        assertEquals(
            Action.HIDE_NOW,
            decide(enabled = true, hasContent = false, hiddenForCapture = true),
        )
    }

    /**
     * Not allowed beats everything, including a capture flag left set. Without this a stale
     * `hiddenForCapture` and a stale `enabled` could argue, and the strip's state would depend
     * on which one was written last.
     */
    @Test fun `a forbidden strip hides whatever else is true`() {
        for (content in listOf(true, false)) {
            for (capture in listOf(true, false)) {
                assertEquals(
                    "enabled=false must hide regardless (content=$content capture=$capture)",
                    Action.HIDE_NOW,
                    decide(enabled = false, hasContent = content, hiddenForCapture = capture),
                )
            }
        }
    }
}
