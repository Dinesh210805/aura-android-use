package com.aura.aura_ui.eval

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.aura.aura_ui.agent.AgentRunBridge
import com.aura.aura_ui.mcp.log.LlmCall
import com.aura.aura_ui.mcp.log.SessionLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The runner's whole reason to exist is what happens when the provider says no. These tests are
 * mostly about that: stop, keep everything, don't blame the agent, and be resumable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EvalRunnerTest {

    private lateinit var context: Context
    private lateinit var store: EvalSuiteStore

    private val tasks = listOf(
        EvalTask(1, "Task one", "in-app", "check one"),
        EvalTask(2, "Task two", "in-app", "check two"),
        EvalTask(3, "Task three", "look", "check three"),
    )

    /** A 429 body in the shape providers actually emit (verified against real Groq traces). */
    private val quotaBody =
        """{"error":{"message":"Rate limit reached for model x. Please retry in 41.235709765s."}}"""

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, EvalSuiteStore.ROOT_DIR_NAME).deleteRecursively()
        store = EvalSuiteStore(context)
        store.createRun(EvalSuiteRun(id = RUN, startedAtMillis = 1L), tasks)
    }

    private fun trace(sessionId: String, responses: List<String> = listOf("ok")) = SessionLog(
        sessionId = sessionId,
        startedAtMillis = 1L,
        agentLabel = "test",
        tokenId = null,
        source = "agent",
    ).apply {
        responses.forEachIndexed { i, body ->
            llmCalls += LlmCall(
                index = i,
                timestampMillis = 1L,
                provider = "test",
                model = "test",
                prompt = "p",
                response = body,
            )
        }
    }

    private fun TestScope.runner(
        runGoal: suspend (String) -> String = { "done" },
        findTrace: suspend (String, Long) -> SessionLog? = { _, _ -> trace("s-1") },
    ) = EvalRunner(
        scope = this,
        store = store,
        runGoal = runGoal,
        findTrace = findTrace,
        now = { 100L },
        interTaskDelayMs = 0L,
    ).also { it.gateOnScoring = false; it.open(RUN) }

    @Test
    fun `a clean sweep leaves every task awaiting a human verdict, not scored`() = runTest(StandardTestDispatcher()) {
        val r = runner()

        r.runRemaining(tasks)
        advanceUntilIdle()

        val results = store.readResults(RUN)
        assertEquals(3, results.size)
        assertTrue(results.all { it.status == EvalStatus.AWAITING_SCORE })
        // The runner must never invent a verdict for a task the agent actually attempted:
        // "the agent finished" is exactly the claim that lied in production.
        assertTrue(results.all { it.verdict == null })
        assertEquals(EvalRunner.Phase.READY, r.state.value.phase)
    }

    @Test
    fun `a quota refusal halts the sweep and keeps every earlier result`() = runTest(StandardTestDispatcher()) {
        var call = 0
        val r = runner(
            runGoal = { "done" },
            findTrace = { _, _ ->
                call++
                if (call >= 2) trace("s-$call", listOf(quotaBody)) else trace("s-$call")
            },
        )

        r.runRemaining(tasks)
        advanceUntilIdle()

        val results = store.readResults(RUN)
        assertEquals(EvalStatus.AWAITING_SCORE, results.first { it.number == 1 }.status)
        // Task 2 tripped the limit; task 3 was never attempted and must still be runnable.
        assertTrue(results.first { it.number == 2 }.rateLimited)
        assertEquals(EvalStatus.PENDING, results.first { it.number == 3 }.status)
        assertEquals(EvalRunner.Phase.PAUSED, r.state.value.phase)
        assertTrue(r.state.value.haltedByRateLimit)
        assertTrue(r.state.value.haltReason!!.contains("key", ignoreCase = true))
    }

    @Test
    fun `a rate-limited task is scored invalid, never fail`() = runTest(StandardTestDispatcher()) {
        val r = runner(findTrace = { _, _ -> trace("s-1", listOf(quotaBody)) })

        r.runRemaining(tasks)
        advanceUntilIdle()

        val first = store.readResult(RUN, 1)!!
        // Running out of quota measures the billing tier, not the agent. Counting it as a failure
        // would make the suite's headline number depend on how much the user paid.
        assertEquals(EvalVerdict.INVALID, first.verdict)
        assertTrue(first.verdict!!.excluded)
        assertFalse(EvalTally.of(store.readResults(RUN)).judged > 0)
    }

    @Test
    fun `a rate limit detected only from the throwable also halts`() = runTest(StandardTestDispatcher()) {
        val r = runner(
            runGoal = { throw IllegalStateException("resource_exhausted: quota exceeded") },
            findTrace = { _, _ -> null },
        )

        r.runRemaining(tasks)
        advanceUntilIdle()

        assertTrue(r.state.value.haltedByRateLimit)
        assertTrue(store.readResult(RUN, 1)!!.rateLimited)
    }

    @Test
    fun `resuming after a halt runs only what is still pending`() = runTest(StandardTestDispatcher()) {
        val attempted = mutableListOf<String>()
        var failNext = true
        val r = runner(
            runGoal = { goal -> attempted += goal; "done" },
            findTrace = { goal, _ ->
                if (failNext && goal == "Task two") trace("s-x", listOf(quotaBody)) else trace("s-y")
            },
        )

        r.runRemaining(tasks)
        advanceUntilIdle()
        assertEquals(listOf("Task one", "Task two"), attempted)

        // The user swaps the key; task 2 is retried explicitly, then the sweep resumes.
        failNext = false
        attempted.clear()
        r.runSingle(tasks[1])
        advanceUntilIdle()
        r.runRemaining(tasks)
        advanceUntilIdle()

        // Task one is NOT re-run: it already produced a trace, and re-running it would spend the
        // very budget that just ran out to learn something already known.
        assertEquals(listOf("Task two", "Task three"), attempted)
    }

    @Test
    fun `an errored task does not halt the sweep`() = runTest(StandardTestDispatcher()) {
        val r = runner(
            runGoal = { goal -> if (goal == "Task one") throw IllegalStateException("boom") else "done" },
            findTrace = { _, _ -> trace("s-1") },
        )

        r.runRemaining(tasks)
        advanceUntilIdle()

        assertEquals(EvalStatus.ERROR, store.readResult(RUN, 1)!!.status)
        assertEquals(EvalStatus.AWAITING_SCORE, store.readResult(RUN, 3)!!.status)
        assertEquals(EvalRunner.Phase.READY, r.state.value.phase)
    }

    @Test
    fun `a timeout is an error, not a failure verdict`() = runTest(StandardTestDispatcher()) {
        val r = EvalRunner(
            scope = this,
            store = store,
            runGoal = { kotlinx.coroutines.delay(Long.MAX_VALUE / 2); "never" },
            findTrace = { _, _ -> null },
            now = { 100L },
            taskTimeoutMs = 1_000L,
            interTaskDelayMs = 0L,
        ).also { it.open(RUN) }

        r.runRemaining(tasks)
        advanceUntilIdle()

        val first = store.readResult(RUN, 1)!!
        assertEquals(EvalStatus.ERROR, first.status)
        assertNull(first.verdict)
        assertTrue(first.outcome.contains("Timed out"))
    }

    /**
     * 2026-08-26 — the run for task 21 was cancelled 4.4 s in and the file recorded "Timed out
     * after 360s". Two different problems wearing one label, and the wrong one to go looking for.
     */
    @Test
    fun `a cancelled run is recorded as cancelled, not as a timeout`() = runTest(StandardTestDispatcher()) {
        val r = EvalRunner(
            scope = this,
            store = store,
            runGoal = { goal -> throw AgentRunBridge.RunCancelled(goal) },
            findTrace = { _, _ -> null },
            now = { 100L },
            taskTimeoutMs = 300_000L,
            interTaskDelayMs = 0L,
        ).also { it.gateOnScoring = false; it.open(RUN) }

        r.runSingle(tasks[0])
        advanceUntilIdle()

        val first = store.readResult(RUN, 1)!!
        assertEquals(EvalStatus.ERROR, first.status)
        assertNull(first.verdict)
        assertTrue("said: ${first.outcome}", first.outcome.contains("Cancelled"))
        assertFalse("a cancellation must never read as a timeout", first.outcome.contains("Timed out"))
    }

    /** A cancelled task must fail alone — the suite carries on, exactly as any other error does. */
    @Test
    fun `a cancelled task does not abort the sweep`() = runTest(StandardTestDispatcher()) {
        val attempted = mutableListOf<String>()
        val r = runner(
            runGoal = { goal ->
                attempted += goal
                if (goal == "Task one") throw AgentRunBridge.RunCancelled(goal) else "done"
            },
        )

        r.runRemaining(tasks)
        advanceUntilIdle()

        assertEquals(listOf("Task one", "Task two", "Task three"), attempted)
        assertEquals(EvalStatus.ERROR, store.readResult(RUN, 1)!!.status)
    }

    @Test
    fun `pause stops after the task in flight rather than mid-task`() = runTest(StandardTestDispatcher()) {
        val attempted = mutableListOf<String>()
        lateinit var r: EvalRunner
        r = runner(runGoal = { goal -> attempted += goal; r.pause(); "done" })

        r.runRemaining(tasks)
        advanceUntilIdle()

        // The first task completed and was recorded; nothing after it started. Cancelling mid-task
        // would leave the phone on a screen the next task inherits as a confound.
        assertEquals(listOf("Task one"), attempted)
        assertEquals(EvalStatus.AWAITING_SCORE, store.readResult(RUN, 1)!!.status)
        assertEquals(EvalRunner.Phase.PAUSED, r.state.value.phase)
        assertFalse(r.state.value.haltedByRateLimit)
    }

    @Test
    fun `scoring every task completes the run`() = runTest(StandardTestDispatcher()) {
        val r = runner()
        r.runRemaining(tasks)
        advanceUntilIdle()

        r.score(1, EvalVerdict.PASS)
        r.score(2, EvalVerdict.FAIL, "left unsent")
        r.score(3, EvalVerdict.REFUSED)

        assertEquals(EvalRunner.Phase.COMPLETE, r.state.value.phase)
        val tally = r.state.value.tally
        assertEquals(3, tally.judged)
        assertEquals(2, tally.passed + tally.refused)
        // Task 3 is a `look` task, so refusing it is a free pass and must be visible as one.
        assertEquals(1, tally.refusedOffCategory)
        assertEquals(0.667, tally.verifiedSuccessRate!!, 0.001)
    }

    @Test
    fun `clearScore puts a task back in front of the human`() = runTest(StandardTestDispatcher()) {
        val r = runner()
        r.runRemaining(tasks)
        advanceUntilIdle()
        r.score(1, EvalVerdict.PASS)

        r.clearScore(1)

        assertEquals(EvalStatus.AWAITING_SCORE, store.readResult(RUN, 1)!!.status)
        assertNull(store.readResult(RUN, 1)!!.verdict)
    }

    @Test
    fun `the trace id is recorded so the cockpit can show the run's json`() = runTest(StandardTestDispatcher()) {
        val r = runner(findTrace = { _, _ -> trace("session-abc") })

        r.runSingle(tasks[0])
        advanceUntilIdle()

        assertEquals("session-abc", store.readResult(RUN, 1)!!.sessionId)
        assertNotNull(store.readResult(RUN, 1)!!.endedAtMillis)
    }

    @Test
    fun `saveNotes persists without inventing or clearing a verdict`() = runTest(StandardTestDispatcher()) {
        val r = runner()
        r.runRemaining(tasks)
        advanceUntilIdle()
        r.score(1, EvalVerdict.FAIL, "")

        // Notes are normally typed AFTER the verdict — that order used to drop them entirely.
        r.saveNotes(1, "tapped Library, no audio")

        val saved = store.readResult(RUN, 1)!!
        assertEquals("tapped Library, no audio", saved.notes)
        assertEquals(EvalVerdict.FAIL, saved.verdict)
        assertEquals(EvalStatus.SCORED, saved.status)
    }

    @Test
    fun `saveNotes works before a verdict exists and leaves the task unscored`() = runTest(StandardTestDispatcher()) {
        val r = runner()
        r.runRemaining(tasks)
        advanceUntilIdle()

        r.saveNotes(2, "screen looked odd")

        val saved = store.readResult(RUN, 2)!!
        assertEquals("screen looked odd", saved.notes)
        assertNull(saved.verdict)
        assertEquals(EvalStatus.AWAITING_SCORE, saved.status)
    }

    @Test
    fun `gating stops after each task and scoring carries the sweep on`() = runTest(StandardTestDispatcher()) {
        val attempted = mutableListOf<String>()
        val r = EvalRunner(
            scope = this,
            store = store,
            runGoal = { goal -> attempted += goal; "done" },
            findTrace = { _, _ -> trace("s-1") },
            now = { 100L },
            interTaskDelayMs = 0L,
        ).also { it.open(RUN) }          // gateOnScoring defaults to true

        r.runRemaining(tasks)
        advanceUntilIdle()

        // One task, then it waits. A verdict entered four tasks later is entered from memory —
        // the screen has moved on and "was audio playing?" is unrecoverable.
        assertEquals(listOf("Task one"), attempted)
        assertEquals(EvalRunner.Phase.PAUSED, r.state.value.phase)
        assertTrue(r.state.value.haltedForScoring)
        assertFalse(r.state.value.haltedByRateLimit)

        // Scoring IS the continue gesture — no second button press per task.
        r.score(1, EvalVerdict.PASS)
        advanceUntilIdle()
        assertEquals(listOf("Task one", "Task two"), attempted)
        assertTrue(r.state.value.haltedForScoring)

        r.score(2, EvalVerdict.FAIL)
        advanceUntilIdle()
        r.score(3, EvalVerdict.PASS)
        advanceUntilIdle()
        assertEquals(listOf("Task one", "Task two", "Task three"), attempted)
        assertEquals(EvalRunner.Phase.COMPLETE, r.state.value.phase)
    }

    @Test
    fun `gating does not re-stop on a task the rate limiter already scored`() = runTest(StandardTestDispatcher()) {
        val r = EvalRunner(
            scope = this,
            store = store,
            runGoal = { "done" },
            findTrace = { _, _ -> trace("s-1", listOf(quotaBody)) },
            now = { 100L },
            interTaskDelayMs = 0L,
        ).also { it.open(RUN) }

        r.runRemaining(tasks)
        advanceUntilIdle()

        // A quota halt must win over the scoring gate: the two need different actions from the
        // user, and telling them to score a task that never ran would be nonsense.
        assertTrue(r.state.value.haltedByRateLimit)
        assertFalse(r.state.value.haltedForScoring)
    }

    @Test
    fun `the runner drives the agent through one seam and narrates nothing itself`() = runTest(StandardTestDispatcher()) {
        val goals = mutableListOf<String>()
        val r = EvalRunner(
            scope = this,
            store = store,
            runGoal = { goal -> goals += goal; "Chrome is in front" },
            findTrace = { _, _ -> trace("s-1") },
            now = { 100L },
            interTaskDelayMs = 0L,
        ).also { it.gateOnScoring = false; it.open(RUN) }

        r.runSingle(tasks[0])
        advanceUntilIdle()

        // runGoal is the only way out of this class. It used to also own a TextToSpeech instance
        // for announcements, and because Android's TTS service is shared, that second engine's
        // QUEUE_FLUSH cut the overlay off mid-reply — the agent looked terminated before it could
        // speak. All speech belongs to the overlay now, which is also what the user actually hears.
        assertEquals(listOf("Task one"), goals)
        assertEquals("Chrome is in front", store.readResult(RUN, 1)!!.outcome)
    }

    @Test
    fun `verified success rate is null until something is judged`() {
        assertNull(EvalTally.of(store.readResults(RUN)).verifiedSuccessRate)
    }

    private companion object {
        const val RUN = "run-under-test"
    }
}
