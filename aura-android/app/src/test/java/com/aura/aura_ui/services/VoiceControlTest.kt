package com.aura.aura_ui.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec 2026-07-31 — taking the wheel by voice.
 *
 * "Pause" spoken aloud is the most natural way to grab control, and it is the only one
 * that works hands-free or across the room. It drives the exact same `ControlLockStore`
 * as a touch, so there is still one lock rather than a second parallel mechanism.
 *
 * The whole difficulty is telling a **control command** from a **task**: "pause" means
 * stop what you are doing; "pause the video" is a job for the agent. Getting that wrong
 * in either direction is bad — swallow a task and the agent goes deaf to real requests;
 * miss a command and the user is left saying "stop, STOP" at a phone that keeps going.
 *
 * The rule: only a bare utterance counts. Extra words mean it is a task.
 */
class VoiceControlTest {

    @Test
    fun `pause is a command`() {
        assertEquals(VoiceControl.Command.PAUSE, VoiceControl.parse("pause"))
    }

    @Test
    fun `stop is a command`() {
        assertEquals(VoiceControl.Command.STOP, VoiceControl.parse("stop"))
    }

    @Test
    fun `continue is a command`() {
        assertEquals(VoiceControl.Command.RESUME, VoiceControl.parse("continue"))
    }

    @Test
    fun `resume and carry on are commands`() {
        assertEquals(VoiceControl.Command.RESUME, VoiceControl.parse("resume"))
        assertEquals(VoiceControl.Command.RESUME, VoiceControl.parse("carry on"))
    }

    @Test
    fun `transcription noise does not defeat it`() {
        // Real STT output arrives capitalised, padded, and punctuated. A rule this small
        // is worthless if "Pause." misses.
        assertEquals(VoiceControl.Command.PAUSE, VoiceControl.parse("  Pause. "))
        assertEquals(VoiceControl.Command.RESUME, VoiceControl.parse("Continue!"))
    }

    @Test
    fun `a polite command still counts`() {
        // People say "ok stop" and "please pause" constantly. Treating those as tasks
        // would mean the phone ignores an explicit instruction to stop, which is exactly
        // the moment it must not.
        assertEquals(VoiceControl.Command.STOP, VoiceControl.parse("ok stop"))
        assertEquals(VoiceControl.Command.PAUSE, VoiceControl.parse("please pause"))
    }

    @Test
    fun `pause the video is a task and not a command`() {
        // The failure that matters most. Swallowing this would make the agent unable to
        // ever pause media, and the user would have no idea why.
        assertNull(VoiceControl.parse("pause the video"))
    }

    @Test
    fun `stop the timer is a task and not a command`() {
        assertNull(VoiceControl.parse("stop the timer"))
    }

    @Test
    fun `ordinary requests are untouched`() {
        assertNull(VoiceControl.parse("open whatsapp and message mum"))
        assertNull(VoiceControl.parse(""))
    }

    @Test
    fun `drop it abandons the paused task`() {
        // Not a convenience. Without it the user is trapped between a stale task they no
        // longer want and a new one the pause refuses to start — a state whose only other
        // exit is force-quitting the app.
        assertEquals(VoiceControl.Command.DROP, VoiceControl.parse("drop it"))
        assertEquals(VoiceControl.Command.DROP, VoiceControl.parse("Never mind."))
    }

    @Test
    fun `drop the call is a task and not a command`() {
        assertNull(VoiceControl.parse("drop the call"))
    }
}
