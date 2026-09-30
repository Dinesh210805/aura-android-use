package com.aura.aura_ui.agent.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainLanePromptTest {

    private fun build(
        request: String = "what's the capital of France",
        recentTranscript: String? = null,
        memoryContext: String? = null,
        recalled: List<String> = emptyList(),
        runningTask: String? = null,
        pendingResume: String? = null,
        phoneState: String? = null,
        nowEpochMs: Long = 1_765_000_000_000L,
    ) = BrainLanePrompt.build(
        request = request,
        recentTranscript = recentTranscript,
        memoryContext = memoryContext,
        recalled = recalled,
        runningTask = runningTask,
        pendingResume = pendingResume,
        phoneState = phoneState,
        nowEpochMs = nowEpochMs,
    )

    @Test fun `the request is always present`() {
        assertTrue(build(request = "order me a coffee").contains("order me a coffee"))
    }

    // ── phone state (the grounding the brain lane never used to have) ────────

    /** Breaks if the snapshot stops reaching the prompt. Without it the brain lane reasons blind:
     *  it cannot tell which app the user is looking at, so "send her a message" has no referent. */
    @Test fun `the phone state block reaches the prompt`() {
        val out = build(phoneState = "They are in WhatsApp, on \"Chat with Sarah\".")
        assertTrue(out.contains("They are in WhatsApp, on \"Chat with Sarah\"."))
    }

    /** Breaks if the block loses its heading — an unlabelled sentence dropped into the prompt reads
     *  as part of the user's request rather than as context about the device. */
    @Test fun `the phone state block is labelled so it cannot be read as user speech`() {
        val out = build(phoneState = "They are in Spotify.")
        val heading = out.lines().indexOfFirst { it.startsWith("# What is on their phone") }
        val body = out.lines().indexOfFirst { it == "They are in Spotify." }
        assertTrue(heading >= 0)
        assertTrue(body > heading)
    }

    /** Breaks if an unknown phone state starts emitting an empty heading, which tells the model
     *  "nothing is happening" when the truth is "we don't know". */
    @Test fun `no phone state means no heading at all`() {
        assertFalse(build(phoneState = null).contains("# What is on their phone"))
        assertFalse(build(phoneState = "   ").contains("# What is on their phone"))
    }

    /** Breaks if phone state stops outranking the transcript. What is on screen NOW must be read
     *  before what was said several turns ago, or the model answers about a stale screen. */
    @Test fun `phone state is placed before the recent conversation`() {
        val out = build(phoneState = "They are in Spotify.", recentTranscript = "user: hey")
        assertTrue(out.indexOf("# What is on their phone") < out.indexOf("# Recent conversation"))
    }

    @Test fun `the current time is included so reminders can be made absolute`() {
        assertTrue(build().contains("1765000000000"))
    }

    /**
     * The lane returns content; [Persona] owns character. If this prompt asked for personality
     * too we would have two persona definitions to keep in sync, and a re-voicing layer with
     * licence to embellish facts.
     */
    @Test fun `the prompt forbids personality so the voice layer owns character`() {
        val p = build()
        assertTrue(p.contains("No greetings, no personality"))
        assertTrue(p.contains("voice layer"))
    }

    @Test fun `every output prefix is documented`() {
        val p = build()
        listOf("ANSWER:", "PHONE:", "STEER:", "GOAL:", "ABORT:", "PAUSE:", "RESUME:", "MEMORY:", "REMIND:")
            .forEach { assertTrue("prefix $it must be documented", p.contains(it)) }
    }

    // ── Optional sections appear only when they have content ──

    @Test fun `no memory section when there is no memory`() {
        assertFalse(build().contains("What you already know"))
    }

    @Test fun `memory context appears when present`() {
        assertTrue(build(memoryContext = "• likes filter coffee").contains("likes filter coffee"))
    }

    @Test fun `recalled memories appear when present`() {
        assertTrue(build(recalled = listOf("has a sister in Coimbatore")).contains("Coimbatore"))
    }

    @Test fun `transcript section appears only when there is one`() {
        assertFalse(build().contains("Recent conversation"))
        assertTrue(build(recentTranscript = "User: hi").contains("Recent conversation"))
    }

    // ── The rules swap with the situation ──

    @Test fun `with nothing running the model is told not to steer`() {
        val p = build()
        assertTrue(p.contains("nothing is running"))
        assertFalse(p.contains("A phone task is running right now"))
    }

    @Test fun `with a task running the rules change and the status is included`() {
        val p = build(runningTask = "Goal: order coffee\nPlan v1 (1/3 done)")
        assertTrue(p.contains("A phone task is running right now"))
        assertTrue(p.contains("Plan v1 (1/3 done)"))
        assertFalse("idle rules must not also be present", p.contains("Do not use STEER, ABORT"))
    }

    /**
     * The escalated task is handed to an agent that has never seen this conversation and has no
     * access to the user's durable memory — so the prompt must insist the task stands alone.
     */
    @Test fun `the model is told the phone task must be self-contained`() {
        assertTrue(build().contains("has not heard this conversation"))
    }

    @Test fun `the model is told PHONE is how it sees the screen`() {
        assertTrue(build().contains("PHONE is how you look"))
    }

    /**
     * Resuming keeps the interrupted run's plan, facts and dead ends; starting the same goal
     * again throws all of that away. The prompt has to make that difference visible or the model
     * will reach for the familiar verb.
     */
    @Test fun `an unfinished task is offered as CONTINUE, not as a fresh PHONE task`() {
        val p = build(pendingResume = "you were ordering food from Swiggy")
        assertTrue(p.contains("left unfinished"))
        assertTrue(p.contains("CONTINUE:"))
        assertTrue(p.contains("Starting it again with PHONE would throw those away"))
    }

    @Test fun `a resumable task is not offered while another task is already running`() {
        val p = build(runningTask = "Goal: something", pendingResume = "an older unfinished task")
        assertFalse(p.contains("left unfinished"))
    }
}
