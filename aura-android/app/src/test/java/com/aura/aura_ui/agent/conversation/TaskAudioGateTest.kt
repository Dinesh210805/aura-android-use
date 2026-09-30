package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.agent.voice.vad.SpeechEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate is what makes a live mic safe while the agent is driving the phone. These pin the three
 * behaviours that decide whether mid-task speech arrives intact: silence is dropped, word onsets
 * survive (pre-roll), and sentences are not chopped at a pause (hold-over).
 */
class TaskAudioGateTest {

    private var now = 0L
    private val gate = TaskAudioGate(chunkMs = CHUNK_MS, clock = { now })

    /** Distinct payloads so assertions can prove WHICH chunks came through, not just how many. */
    private var seq = 0
    private fun chunk() = byteArrayOf((++seq).toByte())

    private fun tick() { now += CHUNK_MS }

    // ── the default: nothing gets through ────────────────────────────────────

    @Test fun `silence is dropped`() {
        // Taps, chimes and app audio during automation all land here. Forwarding them would let
        // Google's server VAD read noise as the user taking a turn.
        repeat(10) { assertTrue(gate.accept(chunk(), emptyList()).isEmpty()); tick() }
        assertFalse(gate.isOpen)
    }

    // ── pre-roll: the word must arrive whole ────────────────────────────────

    @Test fun `speech start flushes the buffered pre-roll ahead of the current chunk`() {
        // SpeechStart only fires after ~96ms of sustained speech. Without the pre-roll the server
        // would receive the sentence with its first syllable already gone.
        val buffered = (1..3).map { chunk().also { _ -> tick() } }
        buffered.forEach { gate.accept(it, emptyList()) }
        val opening = chunk()
        val sent = gate.accept(opening, listOf(SpeechEvent.SpeechStart))
        assertEquals(buffered + opening, sent)
        assertTrue(gate.isOpen)
    }

    @Test fun `the pre-roll is bounded and keeps the most recent audio`() {
        val capacity = PRE_ROLL_MS / CHUNK_MS
        val all = (1..capacity + 5).map { chunk().also { _ -> tick() } }
        all.forEach { gate.accept(it, emptyList()) }
        val opening = chunk()
        val sent = gate.accept(opening, listOf(SpeechEvent.SpeechStart))
        // Oldest chunks are evicted: an unbounded buffer during a multi-minute task would grow
        // without limit and then dump minutes of silence at the first word.
        assertEquals(capacity + 1, sent.size)
        assertEquals(all.takeLast(capacity) + opening, sent)
    }

    // ── while open ──────────────────────────────────────────────────────────

    @Test fun `chunks pass one at a time while speech continues`() {
        gate.accept(chunk(), listOf(SpeechEvent.SpeechStart)); tick()
        val mid = chunk()
        assertEquals(listOf(mid), gate.accept(mid, emptyList()))
    }

    // ── hold-over: do not chop a sentence at a breath ───────────────────────

    @Test fun `the gate stays open through the hold-over after speech ends`() {
        gate.accept(chunk(), listOf(SpeechEvent.SpeechStart)); tick()
        gate.accept(chunk(), listOf(SpeechEvent.SpeechEnd))
        // Trailing consonants and a mid-thought pause both live inside this window.
        now += TaskAudioGate.DEFAULT_HOLD_OVER_MS - CHUNK_MS
        val tail = chunk()
        assertEquals(listOf(tail), gate.accept(tail, emptyList()))
        assertTrue(gate.isOpen)
    }

    @Test fun `the gate closes once the hold-over expires`() {
        gate.accept(chunk(), listOf(SpeechEvent.SpeechStart)); tick()
        gate.accept(chunk(), listOf(SpeechEvent.SpeechEnd))
        now += TaskAudioGate.DEFAULT_HOLD_OVER_MS
        assertTrue(gate.accept(chunk(), emptyList()).isEmpty())
        assertFalse(gate.isOpen)
    }

    @Test fun `speech resuming inside the hold-over cancels the close`() {
        gate.accept(chunk(), listOf(SpeechEvent.SpeechStart)); tick()
        gate.accept(chunk(), listOf(SpeechEvent.SpeechEnd)); tick()
        // A pause mid-sentence must not split one utterance into two turns.
        gate.accept(chunk(), listOf(SpeechEvent.SpeechStart))
        now += TaskAudioGate.DEFAULT_HOLD_OVER_MS * 2
        val stillTalking = chunk()
        assertEquals(listOf(stillTalking), gate.accept(stillTalking, emptyList()))
    }

    @Test fun `a second utterance re-flushes a fresh pre-roll`() {
        gate.accept(chunk(), listOf(SpeechEvent.SpeechStart)); tick()
        gate.accept(chunk(), listOf(SpeechEvent.SpeechEnd))
        now += TaskAudioGate.DEFAULT_HOLD_OVER_MS
        gate.accept(chunk(), emptyList()); tick()   // closed → buffered
        val buffered = chunk(); tick()
        gate.accept(buffered, emptyList())
        val opening = chunk()
        val sent = gate.accept(opening, listOf(SpeechEvent.SpeechStart))
        assertTrue("the second sentence gets its onset too", sent.size > 1)
        assertEquals(opening, sent.last())
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    @Test fun `reset shuts the gate and discards buffered audio`() {
        gate.accept(chunk(), emptyList())
        gate.accept(chunk(), listOf(SpeechEvent.SpeechStart))
        gate.reset()
        assertFalse(gate.isOpen)
        // Stale audio from before a task must never lead the first utterance of the next one.
        val opening = chunk()
        assertEquals(listOf(opening), gate.accept(opening, listOf(SpeechEvent.SpeechStart)))
    }

    private companion object {
        const val CHUNK_MS = 40
        const val PRE_ROLL_MS = TaskAudioGate.DEFAULT_PRE_ROLL_MS
    }
}
