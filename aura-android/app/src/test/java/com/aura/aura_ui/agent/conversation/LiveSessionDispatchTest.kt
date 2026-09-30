package com.aura.aura_ui.agent.conversation

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the deferred-ack contract in `LiveSession.dispatchToolCall`, which had NO tests before
 * this change and is the piece most able to break silently: a leaked tracker slot refuses every
 * later task with nothing in the logs to explain it, and a missing ack puts the model back in the
 * state where it invents outcomes.
 *
 * The socket-frame shaping and dispatch decision are exercised through the same pure pieces
 * `LiveSession` uses ([LiveProtocol], [LiveTaskTracker], [EscalationSink]), so the contract can be
 * asserted without standing up OkHttp or a real Live endpoint.
 */
class LiveSessionDispatchTest {

    /** Captures the frames a dispatch would put on the wire. */
    private class FakeSocket {
        val frames = mutableListOf<String>()
        fun send(frame: String) { frames.add(frame) }
        fun schedulingOf(index: Int): String? =
            Regex("\"scheduling\":\"([A-Z_]+)\"").find(frames[index])?.groupValues?.get(1)
        fun textOf(index: Int): String = frames[index]
    }

    private val tracker = LiveTaskTracker()
    private val socket = FakeSocket()
    private val call = CompanionToolCall(LiveToolDeclarations.ASK_AURA, JsonObject(emptyMap()), id = "call-1")

    @Before fun armAsync() = LiveTaskTracker.resetAsyncSupportForTest()

    @After fun disarmAsync() = LiveTaskTracker.resetAsyncSupportForTest()

    /**
     * Mirrors `LiveSession.dispatchToolCall`. Kept in the test rather than reaching into the
     * session so the contract is asserted on behaviour, not on private structure.
     */
    private suspend fun dispatch(handler: CompanionToolHandler): Boolean {
        var escalated = false
        val sink = EscalationSink { label ->
            when {
                !LiveTaskTracker.isAsyncSupported -> Escalation.BLOCKING
                !tracker.onStarted(call.id, call.name, label) -> Escalation.REFUSED
                else -> {
                    escalated = true
                    // The REAL builder, not a copy of it. The rest of dispatch still has to be
                    // mirrored here (it needs OkHttp), so everything worth pinning about the
                    // escalation frames was moved into buildEscalationFrames precisely so this
                    // line can call production code instead of restating it.
                    buildEscalationFrames(call.id, call.name, label).forEach(socket::send)
                    Escalation.ACKED
                }
            }
        }
        try {
            val result = runCatching { handler.handle(call, sink) }
                .getOrElse { CompanionToolResult("Tool failed: ${it.message}", isError = true) }
            if (escalated) {
                buildFinishFrames(call.id, call.name, result.text).forEach(socket::send)
            } else {
                socket.send(LiveProtocol.buildToolResponse(call.id, call.name, result.text))
            }
        } finally {
            if (escalated) tracker.onFinished()
        }
        return escalated
    }

    private fun handler(body: suspend (EscalationSink) -> CompanionToolResult) =
        object : CompanionToolHandler {
            override suspend fun handle(call: CompanionToolCall, escalate: EscalationSink) = body(escalate)
        }

    // ── the ordinary answer: one frame, no ack ────────────────────────────────

    @Test fun `a handler that never escalates sends exactly one plain response`() = runTest {
        dispatch(handler { CompanionToolResult("Paris.") })

        assertEquals("an answer must not be preceded by an ack", 1, socket.frames.size)
        assertNull("a blocking-shaped reply carries no scheduling", socket.schedulingOf(0))
        assertTrue(socket.textOf(0).contains("Paris."))
    }

    // ── escalation: ack now, real result later ────────────────────────────────

    @Test fun `an escalating handler sends a SILENT ack then a SILENT result`() = runTest {
        dispatch(handler { sink -> sink.escalate("order a coffee"); CompanionToolResult("ordered") })

        assertEquals(4, socket.frames.size)
        assertEquals(LiveTaskTracker.SCHEDULING_SILENT, socket.schedulingOf(0))
        assertEquals(LiveTaskTracker.SCHEDULING_SILENT, socket.schedulingOf(2))
        assertTrue(socket.textOf(2).contains("ordered"))
    }

    /**
     * The bug: a task finished and AURA said nothing, because an INTERRUPT-scheduled result does
     * not reliably produce speech. The client turn after the result is what guarantees the report.
     */
    @Test fun `finishing sends a spoken task-finished turn carrying the result, last`() = runTest {
        dispatch(handler { sink -> sink.escalate("order a coffee"); CompanionToolResult("ordered") })

        assertNull("the spoken turn is client content", socket.schedulingOf(3))
        assertTrue(
            "the model must be cued to speak, with the result",
            socket.textOf(3).contains(Persona.TASK_FINISHED_TRIGGER) && socket.textOf(3).contains("ordered"),
        )
    }

    /**
     * The bug: a task accepted by voice started in total silence. The SILENT ack means "absorb
     * this, do not respond", so nothing spoke between "order a coffee" and the outcome a minute
     * later. Frame 1 is the fix, and it must stay SECOND — a client turn sent before the ack opens
     * a new turn while the function call is still unanswered.
     */
    @Test fun `escalating also sends a spoken task-started turn, after the ack`() = runTest {
        dispatch(handler { sink -> sink.escalate("order a coffee"); CompanionToolResult("ordered") })

        assertNull("the spoken turn is client content, not a scheduled tool response", socket.schedulingOf(1))
        assertTrue(
            "the model must be cued to speak, with the task label",
            socket.textOf(1).contains(Persona.TASK_STARTED_TRIGGER) &&
                socket.textOf(1).contains("order a coffee"),
        )
    }

    /**
     * Both frames have to carry the "no outcome yet" fact. The ack states it for the model's own
     * bookkeeping; the persona rule behind the trigger states it for what gets said aloud. If only
     * one did, "starting that" and "that's done" become the same turn.
     */
    @Test fun `the ack tells the model it has no outcome yet`() {
        val ack = ackTextFor("send mom a message")
        assertTrue(ack.contains("send mom a message"))
        assertTrue("must forbid claiming completion", ack.contains("do not say it is done"))
    }

    @Test fun `the escalated call releases the tracker slot when it finishes`() = runTest {
        dispatch(handler { sink -> sink.escalate("t"); CompanionToolResult("done") })
        assertFalse("slot must be free for the next task", tracker.isRunning)
    }

    /**
     * The failure this guards is invisible without it: a throw after escalation leaves the slot
     * claimed forever, so every later task is refused and nothing says why.
     */
    @Test fun `a handler that throws after escalating still releases the slot`() = runTest {
        dispatch(handler { sink -> sink.escalate("t"); error("boom") })

        assertFalse("a leaked slot would refuse every future task", tracker.isRunning)
        assertEquals(4, socket.frames.size)
        assertTrue("the model must still be told what happened", socket.textOf(2).contains("boom"))
        assertTrue("and must say it aloud", socket.textOf(3).contains("boom"))
    }

    // ── the three escalation outcomes are distinguishable ─────────────────────

    @Test fun `a second escalation while one is in flight is REFUSED`() = runTest {
        tracker.onStarted("other-call", LiveToolDeclarations.ASK_AURA, "first task")

        var outcome: Escalation? = null
        dispatch(handler { sink -> outcome = sink.escalate("second"); CompanionToolResult("refused") })

        assertEquals(Escalation.REFUSED, outcome)
        assertEquals("a refused escalation sends no ack", 1, socket.frames.size)
        assertNull(socket.schedulingOf(0))
    }

    /**
     * Demotion means the endpoint rejected asynchronous function calling. The work must still go
     * ahead — only the delivery changes — so BLOCKING must not be confused with REFUSED.
     */
    @Test fun `with async demoted the escalation reports BLOCKING and no ack is sent`() = runTest {
        LiveTaskTracker.demoteToBlocking()

        var outcome: Escalation? = null
        dispatch(handler { sink -> outcome = sink.escalate("t"); CompanionToolResult("done anyway") })

        assertEquals(Escalation.BLOCKING, outcome)
        assertEquals(1, socket.frames.size)
        assertNull(socket.schedulingOf(0))
        assertTrue(socket.textOf(0).contains("done anyway"))
        assertFalse("a blocking fallback never claims the slot", tracker.isRunning)
    }

    // ── progress only flows while something is genuinely in flight ────────────

    @Test fun `progress is only emitted while a call holds the slot`() {
        assertFalse("nothing running — progress must no-op", tracker.shouldEmitProgress("running tap"))

        tracker.onStarted("call-1", LiveToolDeclarations.ASK_AURA, "t")
        assertTrue(tracker.shouldEmitProgress("running tap"))
        assertFalse("a repeat of the same line is dropped", tracker.shouldEmitProgress("running tap"))
    }
}
