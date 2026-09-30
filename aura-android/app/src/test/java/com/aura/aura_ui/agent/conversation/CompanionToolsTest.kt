package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.agent.ledger.ActiveRunRegistry
import com.aura.aura_ui.agent.ledger.RunLedger
import com.aura.aura_ui.agent.ledger.RunLedgerController
import com.aura.aura_ui.agent.memory.Commitment
import com.aura.aura_ui.agent.memory.MemoryEntry
import com.aura.aura_ui.agent.memory.MemoryService
import com.aura.aura_ui.agent.memory.MemoryType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionToolsTest {

    private class FakeMemory : MemoryService {
        val saved = mutableListOf<Pair<MemoryType, String>>()
        val actions = mutableListOf<Pair<String, String>>()
        val reminders = mutableListOf<Commitment>()
        var recallReturns = listOf<MemoryEntry>()
        override suspend fun save(type: MemoryType, text: String): MemoryEntry {
            saved.add(type to text); return MemoryEntry("1", type, text, false, 0L)
        }
        override suspend fun recall(query: String, limit: Int) = recallReturns
        override suspend fun appendEpisode(summary: String) {}
        override suspend fun logAction(taskSummary: String, outcome: String) { actions.add(taskSummary to outcome) }
        override suspend fun dueCommitments(nowEpochMs: Long) =
            reminders.filter { !it.done && it.dueAtEpochMs <= nowEpochMs }
        override suspend fun remind(text: String, dueAtEpochMs: Long): Commitment =
            Commitment("c${reminders.size}", text, dueAtEpochMs).also { reminders.add(it) }
        override suspend fun completeCommitments(ids: Collection<String>) {
            val idSet = ids.toSet()
            reminders.replaceAll { if (it.id in idSet) it.copy(done = true) else it }
        }
        override suspend fun buildSessionContext(nowEpochMs: Long) = ""
        override suspend fun styleNotes(limit: Int) = emptyList<String>()
    }

    private class FakePhone(val result: String = "done", val resumeResult: String? = null) : PhoneTaskRunner {
        var ranTask: String? = null
        var resumed = false
        override suspend fun run(task: String, onProgress: (String) -> Unit): String {
            ranTask = task; onProgress("running"); onProgress("done"); return result
        }
        override suspend fun resumeLatest(onProgress: (String) -> Unit): String? {
            resumed = true; onProgress("resuming"); return resumeResult
        }
    }

    /** A phone task that blocks until the test releases it, so "a task is running" is observable. */
    private class BlockingPhone : PhoneTaskRunner {
        val started = CompletableDeferred<Unit>()
        private val finish = CompletableDeferred<String>()
        override suspend fun run(task: String, onProgress: (String) -> Unit): String {
            started.complete(Unit)
            return finish.await()
        }
        override suspend fun resumeLatest(onProgress: (String) -> Unit): String? = null
        fun release() = finish.complete("done")
    }

    /** Records what the lane was asked, and replies with a decision scripted per request. */
    private class FakeBrain(private val replyFor: (String) -> BrainLaneReply?) : BrainLane {
        constructor(reply: BrainLaneReply?) : this({ reply })

        var lastRequest: String? = null
        var lastRunningTask: String? = null
        var lastTranscript: String? = null
        var lastPendingResume: String? = null
        override suspend fun ask(
            request: String,
            recentTranscript: String?,
            runningTask: String?,
            pendingResume: String?,
        ): BrainLaneReply? {
            lastRequest = request
            lastTranscript = recentTranscript
            lastRunningTask = runningTask
            lastPendingResume = pendingResume
            return replyFor(request)
        }
    }

    private fun ask(request: String = "do the thing") =
        CompanionToolCall(LiveToolDeclarations.ASK_AURA, buildJsonObject { put("request", request) })

    private fun call(name: String, build: JsonObjectBuilder.() -> Unit = {}) =
        CompanionToolCall(name, buildJsonObject(build))

    private fun controller() = RunLedgerController(
        RunLedger("run-1", "order food A", "GROQ", "llama", 1_000L),
    )

    private fun tools(
        memory: MemoryService = FakeMemory(),
        phone: PhoneTaskRunner = FakePhone(),
        brain: BrainLane,
        registry: ActiveRunRegistry = ActiveRunRegistry(),
        onEndConversation: () -> Unit = {},
        onPause: () -> Unit = {},
        onResume: () -> Unit = {},
        transcript: () -> String? = { null },
        pendingResume: suspend () -> String? = { null },
    ) = CompanionTools(
        memory = memory,
        phone = phone,
        brain = brain,
        onEndConversation = onEndConversation,
        recentTranscript = transcript,
        pendingResume = pendingResume,
        runRegistry = registry,
        pauseRun = onPause,
        resumeRun = onResume,
    )

    // ── sensitive-request screening (2026-08-12) ──────────────────────────────
    //
    // Until now the conversation plane was protected only INDIRECTLY: a request reached the brain
    // lane, the lane decided to escalate, and SensitivePolicy stopped the resulting gesture inside
    // the action plane. Everything upstream of that gesture still happened — the reasoning model
    // read and planned a banking request, and burned tokens doing it.
    //
    // These tests move the same policy — the SAME object, not a reimplementation — to the front
    // door, so a blocked request never reaches the lane at all.

    @Test fun `a payment-app request is refused before the brain lane sees it`() = runTest {
        val brain = FakeBrain(BrainLaneReply(spoken = "should never be reached"))
        val res = tools(brain = brain).handle(ask("open Google Pay and send 500 rupees to Amma"))

        assertNull("the brain lane must not reason about a blocked request", brain.lastRequest)
        assertTrue("the refusal must be spoken, not swallowed", res.text.isNotBlank())
    }

    @Test fun `a spoken card number is refused before the brain lane sees it`() = runTest {
        val brain = FakeBrain(BrainLaneReply(spoken = "should never be reached"))
        val res = tools(brain = brain).handle(ask("type my card 4111 1111 1111 1111 into the form"))

        assertNull(brain.lastRequest)
        assertTrue(res.text.isNotBlank())
    }

    @Test fun `an ordinary request still reaches the brain lane`() = runTest {
        val brain = FakeBrain(BrainLaneReply(spoken = "Sunny."))
        val res = tools(brain = brain).handle(ask("what's the weather today"))

        assertEquals("what's the weather today", brain.lastRequest)
        assertEquals("Sunny.", res.text)
    }

    /**
     * Word-boundary matching earns its keep here, and this test states exactly how far it goes.
     *
     * The blocklist contains "invest" and "crypto" as PACKAGE substrings, which is correct for
     * `com.investing.app` and catastrophic for prose: a plain `contains` check refuses
     * "investigate" and "cryptography". Splitting into words fixes that class of false positive.
     *
     * What it does NOT do — and must not be claimed to do — is save a sentence that genuinely
     * contains the whole word. "Check my bank balance" IS refused, deliberately: that request is
     * exactly what the policy exists to stop.
     */
    @Test fun `blocklist words do not fire inside longer innocent words`() = runTest {
        val brain = FakeBrain(BrainLaneReply(spoken = "On it."))
        val res = tools(brain = brain).handle(ask("investigate the cryptography error in my notes"))

        assertEquals(
            "substring matching would have refused this for 'invest' and 'crypto'",
            "investigate the cryptography error in my notes",
            brain.lastRequest,
        )
        assertEquals("On it.", res.text)
    }

    // ── answering ─────────────────────────────────────────────────────────────

    @Test fun `an ANSWER is spoken and nothing escalates`() = runTest {
        var escalated = false
        val res = tools(brain = FakeBrain(BrainLaneReply(spoken = "Paris.")))
            .handle(ask("capital of France"), EscalationSink { escalated = true; Escalation.ACKED })
        assertFalse(res.isError)
        assertEquals("Paris.", res.text)
        assertFalse("a question must not claim the long-running slot", escalated)
    }

    @Test fun `ask_aura without a request is a model-readable error`() = runTest {
        val res = tools(brain = FakeBrain(BrainLaneReply("x"))).handle(call(LiveToolDeclarations.ASK_AURA))
        assertTrue(res.isError)
    }

    /** An unreachable provider must degrade to a spoken sentence, never an exception. */
    @Test fun `an unreachable brain returns a speakable error`() = runTest {
        val res = tools(brain = FakeBrain(null)).handle(ask())
        assertTrue(res.isError)
        assertTrue(res.text.contains("Settings"))
    }

    @Test fun `conversation context and pending resume are handed to the lane`() = runTest {
        val brain = FakeBrain(BrainLaneReply("ok"))
        tools(brain = brain, transcript = { "User: hi" }, pendingResume = { "you were ordering food" })
            .handle(ask("what were we doing"))
        assertEquals("User: hi", brain.lastTranscript)
        assertEquals("you were ordering food", brain.lastPendingResume)
        assertNull("nothing is running", brain.lastRunningTask)
    }

    // ── escalation ────────────────────────────────────────────────────────────

    @Test fun `a PHONE decision escalates, runs the task and logs it`() = runTest {
        val phone = FakePhone(result = "ordered")
        val mem = FakeMemory()
        var label: String? = null
        val res = tools(
            memory = mem,
            phone = phone,
            brain = FakeBrain(BrainLaneReply("On it.", BrainLaneAction.Phone("order a filter coffee"))),
        ).handle(ask(), EscalationSink { label = it; Escalation.ACKED })

        assertFalse(res.isError)
        assertEquals("ordered", res.text)
        assertEquals("order a filter coffee", phone.ranTask)
        assertEquals("the ack must quote what AURA said it was doing", "On it.", label)
        assertEquals(1, mem.actions.size)
    }

    /**
     * BLOCKING means the endpoint rejected async function calling. The work must still happen —
     * only the delivery of its result changes.
     */
    @Test fun `a BLOCKING escalation still runs the task`() = runTest {
        val phone = FakePhone()
        val res = tools(phone = phone, brain = FakeBrain(BrainLaneReply("ok", BrainLaneAction.Phone("t"))))
            .handle(ask(), EscalationSink { Escalation.BLOCKING })
        assertFalse(res.isError)
        assertEquals("t", phone.ranTask)
    }

    @Test fun `a REFUSED escalation does not start the task`() = runTest {
        val phone = FakePhone()
        val res = tools(phone = phone, brain = FakeBrain(BrainLaneReply("ok", BrainLaneAction.Phone("t"))))
            .handle(ask(), EscalationSink { Escalation.REFUSED })
        assertTrue(res.isError)
        assertTrue(res.text.contains("already running"))
        assertNull("the second run must never start", phone.ranTask)
    }

    @Test fun `a second task while one is running is refused with words the model can use`() = runTest {
        val phone = BlockingPhone()
        val t = tools(phone = phone, brain = FakeBrain(BrainLaneReply("ok", BrainLaneAction.Phone("first"))))
        val first = launch { t.handle(ask(), EscalationSink { Escalation.ACKED }) }
        phone.started.await()

        val second = t.handle(ask(), EscalationSink { Escalation.ACKED })
        assertTrue(second.isError)
        assertTrue(second.text.contains("ask whether"))

        phone.release(); first.join()
    }

    /**
     * Conversation must survive a task — this is what replaces the old `task_status` tool.
     *
     * The one-at-a-time guard applies to ESCALATION, not to the lane. A second PHONE decision is
     * rightly refused; a progress question is an ANSWER and must go straight through, with the
     * run's real ledger status handed to the lane so the reply is fact rather than guesswork.
     */
    @Test fun `an ANSWER still works while a phone task is running, with the task status in context`() = runTest {
        val phone = BlockingPhone()
        val brain = FakeBrain { request ->
            if (request == "how's it going") BrainLaneReply("It's about a third done.")
            else BrainLaneReply("ok", BrainLaneAction.Phone("first"))
        }
        val t = CompanionTools(
            memory = FakeMemory(),
            phone = phone,
            brain = brain,
            taskStatus = { "Goal: order food\nPlan v1 (1/3 done)" },
            runRegistry = ActiveRunRegistry(),
        )
        val first = launch { t.handle(ask(), EscalationSink { Escalation.ACKED }) }
        phone.started.await()

        val status = t.handle(ask("how's it going"), EscalationSink { Escalation.REFUSED })

        assertFalse("a progress question must not be refused as a second task", status.isError)
        assertEquals("It's about a third done.", status.text)
        assertTrue("the lane must be given the run's real status", brain.lastRunningTask!!.contains("1/3 done"))

        phone.release(); first.join()
    }

    // ── continuing an interrupted run ─────────────────────────────────────────

    @Test fun `CONTINUE resumes the interrupted run rather than starting a fresh one`() = runTest {
        val phone = FakePhone(resumeResult = "picked up and finished")
        val res = tools(phone = phone, brain = FakeBrain(BrainLaneReply("Picking it up.", BrainLaneAction.Continue)))
            .handle(ask(), EscalationSink { Escalation.ACKED })
        assertFalse(res.isError)
        assertTrue(phone.resumed)
        assertNull("a resume must not start a new task", phone.ranTask)
    }

    @Test fun `CONTINUE with nothing to resume says so instead of pretending`() = runTest {
        val res = tools(
            phone = FakePhone(resumeResult = null),
            brain = FakeBrain(BrainLaneReply("ok", BrainLaneAction.Continue)),
        ).handle(ask(), EscalationSink { Escalation.ACKED })
        assertTrue(res.isError)
        assertTrue(res.text.contains("no unfinished task"))
    }

    // ── steering ──────────────────────────────────────────────────────────────

    @Test fun `STEER writes the directive into the running run's ledger`() = runTest {
        val registry = ActiveRunRegistry()
        val c = controller()
        registry.register("run-1", c)

        val res = tools(
            brain = FakeBrain(
                BrainLaneReply("Switching.", BrainLaneAction.Steer("search food B; re-check the screen")),
            ),
            registry = registry,
        ).handle(ask("no, food B"))

        assertFalse(res.isError)
        assertEquals("Switching.", res.text)
        assertTrue(c.snapshot().directive!!.contains("food B"))
    }

    @Test fun `STEER with a GOAL retargets the run and drops the stale plan`() = runTest {
        val registry = ActiveRunRegistry()
        val c = controller()
        c.setPlan(listOf("open app", "search food A"))
        registry.register("run-1", c)

        tools(
            brain = FakeBrain(BrainLaneReply("ok", BrainLaneAction.Steer("switch", newGoal = "order food B"))),
            registry = registry,
        ).handle(ask())

        assertEquals("order food B", c.snapshot().goal)
        assertTrue("the checklist for the old goal must go", c.snapshot().planSteps.isEmpty())
    }

    @Test fun `STEER with nothing running is an honest error`() = runTest {
        val res = tools(brain = FakeBrain(BrainLaneReply("ok", BrainLaneAction.Steer("x"))))
            .handle(ask())
        assertTrue(res.isError)
        assertTrue(res.text.contains("Nothing is running"))
    }

    // ── stopping and holding ──────────────────────────────────────────────────

    @Test fun `ABORT cancels the running task`() = runTest {
        val phone = BlockingPhone()
        val t = tools(phone = phone, brain = FakeBrain(BrainLaneReply("ok", BrainLaneAction.Phone("first"))))
        val first = launch { t.handle(ask(), EscalationSink { Escalation.ACKED }) }
        phone.started.await()

        val stop = CompanionTools(
            memory = FakeMemory(), phone = phone,
            brain = FakeBrain(BrainLaneReply("Stopped.", BrainLaneAction.Abort)),
        )
        // The cancel handle lives on the instance that started the task.
        assertTrue(t.cancelCurrentTask())
        first.join()
        assertFalse(stop.cancelCurrentTask())
    }

    @Test fun `ABORT with nothing running is an honest error`() = runTest {
        val res = tools(brain = FakeBrain(BrainLaneReply("ok", BrainLaneAction.Abort))).handle(ask())
        assertTrue(res.isError)
        assertTrue(res.text.contains("nothing to stop"))
    }

    @Test fun `PAUSE drives the control lock while a task runs`() = runTest {
        val phone = BlockingPhone()
        var paused = false
        val t = tools(
            phone = phone,
            brain = FakeBrain(BrainLaneReply("ok", BrainLaneAction.Phone("first"))),
            onPause = { paused = true },
        )
        val first = launch { t.handle(ask(), EscalationSink { Escalation.ACKED }) }
        phone.started.await()

        val held = CompanionTools(
            memory = FakeMemory(), phone = phone,
            brain = FakeBrain(BrainLaneReply("Holding.", BrainLaneAction.Pause)),
            pauseRun = { paused = true },
        )
        // Nothing running on THIS instance, so it refuses — the guard is per-run, as intended.
        assertTrue(held.handle(ask()).isError)
        assertFalse(paused)

        phone.release(); first.join()
    }

    @Test fun `PAUSE with nothing running refuses rather than freezing the phone`() = runTest {
        var paused = false
        val res = tools(brain = FakeBrain(BrainLaneReply("ok", BrainLaneAction.Pause)), onPause = { paused = true })
            .handle(ask())
        assertTrue(res.isError)
        assertFalse(paused)
    }

    @Test fun `RESUME releases the lock even with nothing tracked locally`() = runTest {
        var resumed = false
        val res = tools(brain = FakeBrain(BrainLaneReply("ok", BrainLaneAction.Resume)), onResume = { resumed = true })
            .handle(ask())
        assertFalse(res.isError)
        assertTrue(resumed)
    }

    // ── side effects ──────────────────────────────────────────────────────────

    @Test fun `MEMORY side effects are saved with their type`() = runTest {
        val mem = FakeMemory()
        tools(
            memory = mem,
            brain = FakeBrain(
                BrainLaneReply("Noted.", memories = listOf(MemoryWrite("STYLE", "mixes Tamil and English"))),
            ),
        ).handle(ask())
        assertEquals(MemoryType.STYLE to "mixes Tamil and English", mem.saved.single())
    }

    /**
     * Episodes and action logs are written by the system from real events. Letting the lane file
     * into them would let a model invent history.
     */
    @Test fun `a memory type the lane may not write falls back to USER`() = runTest {
        val mem = FakeMemory()
        tools(
            memory = mem,
            brain = FakeBrain(BrainLaneReply("ok", memories = listOf(MemoryWrite("ACTION_LOG", "did a thing")))),
        ).handle(ask())
        assertEquals(MemoryType.USER, mem.saved.single().first)
    }

    @Test fun `an unrecognised memory type falls back to USER`() = runTest {
        val mem = FakeMemory()
        tools(memory = mem, brain = FakeBrain(BrainLaneReply("ok", memories = listOf(MemoryWrite("banana", "x")))))
            .handle(ask())
        assertEquals(MemoryType.USER, mem.saved.single().first)
    }

    @Test fun `REMIND side effects become commitments`() = runTest {
        val mem = FakeMemory()
        tools(memory = mem, brain = FakeBrain(BrainLaneReply("Set.", reminders = listOf(ReminderWrite(99L, "dentist")))))
            .handle(ask())
        assertEquals("dentist", mem.reminders.single().text)
        assertEquals(99L, mem.reminders.single().dueAtEpochMs)
    }

    // ── the rest of the surface ───────────────────────────────────────────────

    @Test fun `end_conversation fires the teardown callback`() = runTest {
        var ended = false
        val res = tools(brain = FakeBrain(BrainLaneReply("bye")), onEndConversation = { ended = true })
            .handle(call(LiveToolDeclarations.END_CONVERSATION) { put("farewell", "later") })
        assertFalse(res.isError)
        assertTrue(ended)
    }

    @Test fun `an unknown tool is a model-readable error, not a crash`() = runTest {
        val res = tools(brain = FakeBrain(BrainLaneReply("x"))).handle(call("drive_phone"))
        assertTrue(res.isError)
        assertTrue(res.text.contains("Unknown tool"))
    }
}
