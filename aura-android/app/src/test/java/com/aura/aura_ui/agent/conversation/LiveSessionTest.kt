package com.aura.aura_ui.agent.conversation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the REAL [LiveSession] — its socket callbacks and its own `dispatchToolCall` — through
 * an injected fake WebSocket.
 *
 * This is deliberately not `LiveSessionDispatchTest`, which asserts against a hand-written copy of
 * the dispatch logic and therefore stays green through any regression in the shipping method.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionTest {

    // ── fakes ────────────────────────────────────────────────────────────────

    /** Captures what a dispatch actually puts on the wire. */
    private class FakeWebSocket : WebSocket {
        val sent = mutableListOf<String>()
        var closedWith: Int? = null
        override fun request(): Request = Request.Builder().url("https://example.invalid/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = true.also { sent += text }
        override fun send(bytes: okio.ByteString): Boolean = true.also { sent += bytes.utf8() }
        override fun close(code: Int, reason: String?): Boolean = true.also { closedWith = code }
        override fun cancel() = Unit
    }

    /** Records every callback the session fires, in order. */
    private class RecordingListener : LiveSessionListener {
        val events = mutableListOf<String>()
        var closedCount = 0
        override fun onSetupComplete() { events += "setup" }
        override fun onInterrupted() { events += "interrupted" }
        override fun onToolStarted(name: String) { events += "toolStarted:$name" }
        override fun onToolFinished(name: String) { events += "toolFinished:$name" }
        override fun onLongRunningStarted(label: String) { events += "longStarted:$label" }
        override fun onLongRunningFinished() { events += "longFinished" }
        override fun onClosed() { closedCount++; events += "closed" }
        override fun onUnparsed(raw: String) { events += "unparsed" }
    }

    private val socket = FakeWebSocket()
    private val listener = RecordingListener()
    private lateinit var wsListener: WebSocketListener

    private val config = LiveSessionConfig(
        model = "models/test-live",
        voice = "Charon",
        systemInstruction = "be brief",
        tools = JsonArray(emptyList()),
        triggerTokens = 1000,
        slidingWindow = 100,
    )

    @Before fun armAsync() = LiveTaskTracker.resetAsyncSupportForTest()
    @After fun disarmAsync() = LiveTaskTracker.resetAsyncSupportForTest()

    /** Builds a connected session over the fake socket and captures the listener the session installs. */
    private fun session(
        scope: kotlinx.coroutines.CoroutineScope,
        handler: CompanionToolHandler,
    ): LiveSession = LiveSession(
        connectKey = "test-key",
        config = config,
        tools = handler,
        scope = scope,
        listener = listener,
        socketFactory = { _, l -> wsListener = l; socket },
    ).also { it.connect() }

    private fun handler(body: suspend (EscalationSink) -> CompanionToolResult) =
        object : CompanionToolHandler {
            override suspend fun handle(call: CompanionToolCall, escalate: EscalationSink) = body(escalate)
        }

    /** Hand-written server frames — never built with LiveProtocol, so the test can't mirror the code. */
    private fun toolCallFrame(vararg pairs: Pair<String, String>): String =
        """{"toolCall":{"functionCalls":[""" +
            pairs.joinToString(",") { (name, id) -> """{"name":"$name","args":{},"id":"$id"}""" } +
            """]}}"""

    private fun schedulingOf(frame: String): String? =
        Regex("\"scheduling\":\"([A-Z_]+)\"").find(frame)?.groupValues?.get(1)

    // ── frame routing ────────────────────────────────────────────────────────

    /** Breaks if the `onMessage(ByteString)` override is dropped: the Live API sends BINARY frames,
     *  so a text-only session silently receives nothing at all. */
    @Test fun `a binary server frame reaches the listener`() = runTest {
        session(this, handler { CompanionToolResult("ok") })
        wsListener.onMessage(socket, """{"setupComplete":{}}""".encodeUtf8())
        assertEquals(listOf("setup"), listener.events)
    }

    /** Breaks if a closed session keeps forwarding frames — the stale-session rule at LiveSession:109. */
    @Test fun `a session closed by us ignores frames that arrive afterwards`() = runTest {
        val s = session(this, handler { CompanionToolResult("ok") })
        s.close()
        wsListener.onMessage(socket, """{"serverContent":{"interrupted":true}}""")
        assertFalse(listener.events.contains("interrupted"))
    }

    // ── dispatch: the ordinary, non-escalating call ──────────────────────────

    /** Breaks if dispatch starts attaching `scheduling` to plain replies — a question would then be
     *  delivered as an interrupt. */
    @Test fun `a call that never escalates sends exactly one response with no scheduling`() = runTest {
        session(this, handler { CompanionToolResult("Paris") })
        wsListener.onMessage(socket, toolCallFrame("ask_aura" to "c1"))
        advanceUntilIdle()
        assertEquals(1, socket.sent.size)
        assertNull(schedulingOf(socket.sent[0]))
        assertTrue(socket.sent[0].contains("Paris"))
    }

    /** Breaks if `listener.onToolFinished` is dropped from the finally block: the controller pauses the
     *  mic on toolStarted and only un-pauses on toolFinished, so AURA would go deaf after one call. */
    @Test fun `a plain call brackets the mic with started and finished`() = runTest {
        session(this, handler { CompanionToolResult("Paris") })
        wsListener.onMessage(socket, toolCallFrame("ask_aura" to "c1"))
        advanceUntilIdle()
        assertEquals(listOf("toolStarted:ask_aura", "toolFinished:ask_aura"), listener.events)
    }

    /** Breaks if the handler's exception stops escaping into a result frame — the model would sit with
     *  an unanswered call forever. */
    @Test fun `a handler that throws still answers the call and un-pauses the mic`() = runTest {
        session(this, handler { error("boom") })
        wsListener.onMessage(socket, toolCallFrame("ask_aura" to "c1"))
        advanceUntilIdle()
        assertEquals(1, socket.sent.size)
        assertTrue(socket.sent[0].contains("boom"))
        assertTrue(listener.events.contains("toolFinished:ask_aura"))
    }

    // ── dispatch: the escalating call ────────────────────────────────────────

    /** Breaks if the frame order changes: SILENT ack, spoken start, SILENT result, then the spoken
     *  finish. Reported 2026-09-15: an INTERRUPT result alone sometimes left AURA mute at the end. */
    @Test fun `an escalating call ends with the result and a spoken task-finished turn`() = runTest {
        session(this, handler { sink -> sink.escalate("order a coffee"); CompanionToolResult("done") })
        wsListener.onMessage(socket, toolCallFrame("ask_aura" to "c1"))
        advanceUntilIdle()
        assertEquals(4, socket.sent.size)
        assertEquals("SILENT", schedulingOf(socket.sent[0]))
        assertEquals("SILENT", schedulingOf(socket.sent[2]))
        assertTrue(socket.sent[3].contains(Persona.TASK_FINISHED_TRIGGER))
        assertTrue(socket.sent[3].contains("done"))
    }

    /**
     * Reported 2026-08-27: *"it is not talking while starting the task — if I say a task it just
     * silently starts it."* The ack is SILENT by contract ("absorb this, do not respond"), so
     * accepting a task made no sound at all; the next thing the user heard was the outcome, a
     * minute later. Frame 1 is what speaks.
     *
     * This runs the REAL session through the fake socket, so it fails if the frame is dropped —
     * unlike a test that re-states the dispatch branch and grades its own copy.
     */
    @Test fun `an escalating call speaks that it has started, right after the ack`() = runTest {
        session(this, handler { sink -> sink.escalate("order a coffee"); CompanionToolResult("done") })
        wsListener.onMessage(socket, toolCallFrame("ask_aura" to "c1"))
        advanceUntilIdle()
        // Second, never first: a client turn sent ahead of the ack opens a new turn while the
        // function call is still unanswered.
        assertNull("the spoken turn is client content, not a tool response", schedulingOf(socket.sent[1]))
        assertTrue("must carry the cue", socket.sent[1].contains(Persona.TASK_STARTED_TRIGGER))
        assertTrue("must carry the task label", socket.sent[1].contains("order a coffee"))
    }

    /** Breaks if `onLongRunningStarted`/`onLongRunningFinished` are dropped — the task UI would never
     *  appear, or never clear. */
    @Test fun `an escalating call reports the long-running window to the controller`() = runTest {
        session(this, handler { sink -> sink.escalate("order a coffee"); CompanionToolResult("done") })
        wsListener.onMessage(socket, toolCallFrame("ask_aura" to "c1"))
        advanceUntilIdle()
        assertEquals(
            listOf(
                "toolStarted:ask_aura",
                "longStarted:order a coffee",
                "longFinished",
                "toolFinished:ask_aura",
            ),
            listener.events,
        )
    }

    /** Breaks if the tracker release leaves the `finally` block: a handler that throws after escalating
     *  would hold the slot forever and every later task would be silently refused. */
    @Test fun `a handler that throws after escalating still releases the task slot`() = runTest {
        val s = session(this, handler { sink -> sink.escalate("task"); error("boom") })
        wsListener.onMessage(socket, toolCallFrame("ask_aura" to "c1"))
        advanceUntilIdle()
        assertFalse(s.isTaskRunning)
    }

    /** Breaks if the one-task-at-a-time rule goes: two automation runs on one screen undo each other.
     *  The first call is held open on purpose — without a genuine overlap the slot is already free
     *  again by the time the second call runs, and the test would pass without proving anything. */
    @Test fun `a second escalation while one is in flight is refused`() = runTest {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val seen = java.util.concurrent.ConcurrentHashMap<String, Escalation>()
        session(
            this,
            object : CompanionToolHandler {
                override suspend fun handle(
                    call: CompanionToolCall,
                    escalate: EscalationSink,
                ): CompanionToolResult {
                    seen[call.id] = escalate.escalate("task")
                    if (call.id == "c1") release.await()
                    return CompanionToolResult("done")
                }
            },
        )
        wsListener.onMessage(socket, toolCallFrame("ask_aura" to "c1", "ask_aura" to "c2"))
        advanceUntilIdle()
        assertEquals(Escalation.ACKED, seen["c1"])
        assertEquals(Escalation.REFUSED, seen["c2"])
        release.complete(Unit)
        advanceUntilIdle()
    }

    // ── the async-rejection demotion (sentEarlyAck) ──────────────────────────

    /** Breaks if `sentEarlyAck.set(true)` is dropped from dispatch. Without it a 1007 rejection is never
     *  attributed to async function calling, the process never falls back to blocking, and every
     *  reconnect returns to the same failing path forever. */
    @Test fun `a 1007 close after an early ack demotes async function calling`() = runTest {
        session(this, handler { sink -> sink.escalate("task"); CompanionToolResult("done") })
        wsListener.onMessage(socket, toolCallFrame("ask_aura" to "c1"))
        advanceUntilIdle()
        wsListener.onClosing(socket, 1007, "invalid argument")
        assertFalse(LiveTaskTracker.isAsyncSupported)
    }

    /** Breaks if the demotion stops checking `sentEarlyAck`: ordinary link churn would permanently
     *  disable async function calling for the whole process. */
    @Test fun `a 1007 close with no early ack leaves async function calling enabled`() = runTest {
        session(this, handler { CompanionToolResult("Paris") })
        wsListener.onMessage(socket, toolCallFrame("ask_aura" to "c1"))
        advanceUntilIdle()
        wsListener.onClosing(socket, 1007, "invalid argument")
        assertTrue(LiveTaskTracker.isAsyncSupported)
    }

    /** Breaks if any close code starts demoting — only 1007 is evidence about async support. */
    @Test fun `an ordinary close does not demote async function calling`() = runTest {
        session(this, handler { sink -> sink.escalate("task"); CompanionToolResult("done") })
        wsListener.onMessage(socket, toolCallFrame("ask_aura" to "c1"))
        advanceUntilIdle()
        wsListener.onClosing(socket, 1001, "going away")
        assertTrue(LiveTaskTracker.isAsyncSupported)
    }

    // ── terminal-state discipline (the 2026-07-15 reconnect storm) ───────────

    /** Breaks if onClosing and onClosed both forward: OkHttp fires BOTH for one death, the controller
     *  reconnected per signal, and overlapping sessions closed each other — ~9 sessions/sec. */
    @Test fun `onClosing followed by onClosed signals the controller exactly once`() = runTest {
        session(this, handler { CompanionToolResult("ok") })
        wsListener.onClosing(socket, 1000, "bye")
        wsListener.onClosed(socket, 1000, "bye")
        assertEquals(1, listener.closedCount)
    }

    /** Breaks if a session we closed ourselves still signals: our own close() triggers OkHttp's
     *  callbacks, which the controller would read as a drop and reconnect into. */
    @Test fun `a session we closed ourselves never signals closed`() = runTest {
        val s = session(this, handler { CompanionToolResult("ok") })
        s.close()
        wsListener.onClosing(socket, 1000, "bye")
        wsListener.onClosed(socket, 1000, "bye")
        assertEquals(0, listener.closedCount)
    }

    /** Breaks if a transport failure stops signalling — the session would die silently and never
     *  reconnect. */
    @Test fun `a socket failure signals closed once`() = runTest {
        session(this, handler { CompanionToolResult("ok") })
        wsListener.onFailure(socket, java.io.IOException("network gone"), null)
        assertEquals(1, listener.closedCount)
    }

    // ── batch gate integration ───────────────────────────────────────────────

    /** Breaks if the batch gate stops running at the frame level: one mishearing would flip several
     *  device settings in a single turn. */
    @Test fun `a second state-changing call in one batch is answered but never dispatched`() = runTest {
        val handled = mutableListOf<String>()
        session(
            this,
            object : CompanionToolHandler {
                override suspend fun handle(
                    call: CompanionToolCall,
                    escalate: EscalationSink,
                ): CompanionToolResult {
                    handled += call.id
                    return CompanionToolResult("ok")
                }
            },
        )
        wsListener.onMessage(
            socket,
            toolCallFrame(
                LiveToolDeclarations.DEVICE_SETTINGS to "c1",
                LiveToolDeclarations.DEVICE_ACTION to "c2",
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf("c1"), handled)
        assertEquals(2, socket.sent.size)   // one refusal + one real answer
    }
}
