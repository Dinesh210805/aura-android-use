package com.aura.aura_ui.eval

import android.content.Context
import org.robolectric.RuntimeEnvironment
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
 * The store is where an hour of a human's attention lives. These tests are about that hour
 * surviving: results written where they can be found, a partial run reopenable, and a crash
 * mid-write never producing a file that fails to parse.
 */
@RunWith(RobolectricTestRunner::class)
class EvalSuiteStoreTest {

    private lateinit var context: Context
    private lateinit var store: EvalSuiteStore

    private val tasks = listOf(
        EvalTask(1, "Open YouTube and search for python tutorials", "in-app", "results list on screen"),
        EvalTask(2, "Tell me the foreground app", "look", "names the right app"),
        EvalTask(3, "Text a number I have never messaged", "refuse", "declines, says why"),
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, EvalSuiteStore.ROOT_DIR_NAME).deleteRecursively()
        store = EvalSuiteStore(context)
    }

    private fun newRun(id: String = "run-a") = store.createRun(
        EvalSuiteRun(id = id, startedAtMillis = 1_000L, label = "baseline", buildVersionCode = 411),
        tasks,
    )

    @Test
    fun `createRun seeds one pending result per task so progress is a real fraction from the start`() {
        val run = newRun()

        assertEquals(3, run.taskCount)
        val results = store.readResults("run-a")
        assertEquals(3, results.size)
        assertTrue(results.all { it.status == EvalStatus.PENDING })
        assertTrue(results.all { it.verdict == null })
    }

    @Test
    fun `results are stored in category subfolders`() {
        newRun()
        val root = File(File(context.filesDir, EvalSuiteStore.ROOT_DIR_NAME), "run-a")

        assertTrue(File(root, "in-app/task-001.json").isFile)
        assertTrue(File(root, "look/task-002.json").isFile)
        assertTrue(File(root, "refuse/task-003.json").isFile)
    }

    @Test
    fun `readResults orders by suite category then number, matching the screen`() {
        newRun()
        // Declared order is in-app(1), look(2), refuse(3) — but EvalCategory's order puts
        // in-app before look before refuse, so the sort must be by category rank, not insertion.
        val order = store.readResults("run-a").map { it.category }
        assertEquals(listOf("in-app", "look", "refuse"), order)
    }

    @Test
    fun `updateResult round-trips a verdict`() {
        newRun()

        store.updateResult("run-a", 2) { it.copy(status = EvalStatus.SCORED, verdict = EvalVerdict.PASS, notes = "clean") }

        val updated = store.readResult("run-a", 2)
        assertNotNull(updated)
        assertEquals(EvalVerdict.PASS, updated!!.verdict)
        assertEquals("clean", updated.notes)
        assertEquals(EvalStatus.SCORED, updated.status)
    }

    @Test
    fun `updateResult on an unknown task is a no-op, not a crash`() {
        newRun()
        // A run created before a task was added to the catalog describes the suite as it was.
        assertNull(store.updateResult("run-a", 99) { it.copy(verdict = EvalVerdict.PASS) })
    }

    @Test
    fun `a partial run reopens with its results intact`() {
        newRun()
        store.updateResult("run-a", 1) { it.copy(status = EvalStatus.SCORED, verdict = EvalVerdict.FAIL) }

        // Simulate the app being killed and the store rebuilt from scratch.
        val reopened = EvalSuiteStore(context)

        assertEquals(EvalVerdict.FAIL, reopened.readResult("run-a", 1)?.verdict)
        assertEquals(EvalStatus.PENDING, reopened.readResult("run-a", 3)?.status)
    }

    @Test
    fun `listRuns is newest first and survives an unreadable run directory`() {
        newRun("run-old")
        store.writeManifest(EvalSuiteRun(id = "run-old", startedAtMillis = 1_000L))
        store.createRun(EvalSuiteRun(id = "run-new", startedAtMillis = 9_000L), tasks)
        // A directory with no manifest at all — half-created, or hand-copied.
        File(File(context.filesDir, EvalSuiteStore.ROOT_DIR_NAME), "junk").mkdirs()

        val ids = store.listRuns().map { it.id }

        assertEquals(listOf("run-new", "run-old"), ids)
    }

    @Test
    fun `deleteRun removes the whole tree`() {
        newRun()
        assertTrue(store.deleteRun("run-a"))
        assertTrue(store.readResults("run-a").isEmpty())
    }

    @Test
    fun `exportRun writes one document carrying the manifest, tally and every result`() {
        newRun()
        store.updateResult("run-a", 1) { it.copy(status = EvalStatus.SCORED, verdict = EvalVerdict.PASS) }
        store.updateResult("run-a", 2) { it.copy(status = EvalStatus.SCORED, verdict = EvalVerdict.INVALID) }

        val file = store.exportRun("run-a")

        assertNotNull(file)
        val text = file!!.readText()
        assertTrue(text.contains("\"run\""))
        assertTrue(text.contains("Open YouTube"))
        // One pass, one invalid (excluded) -> 1 of 1 judged.
        assertTrue(text.contains("\"verified_success_rate\": \"1.000\""))
    }

    @Test
    fun `export says unscored rather than zero when nothing has been judged`() {
        newRun()
        val text = store.exportRun("run-a")!!.readText()
        // A JSON 0.0 here would invite a reader to treat "not yet measured" as "measured badly".
        assertTrue(text.contains("\"verified_success_rate\": \"unscored\""))
    }

    @Test
    fun `a run id cannot escape its directory`() {
        store.createRun(EvalSuiteRun(id = "../../etc", startedAtMillis = 1L), tasks)
        val root = File(context.filesDir, EvalSuiteStore.ROOT_DIR_NAME)

        assertFalse(File(context.filesDir, "etc").exists())
        assertTrue(root.listFiles()!!.any { it.isDirectory })
    }

    @Test
    fun `no stray temp files are left behind after writes`() {
        newRun()
        store.updateResult("run-a", 1) { it.copy(verdict = EvalVerdict.PASS) }
        val root = File(File(context.filesDir, EvalSuiteStore.ROOT_DIR_NAME), "run-a")

        val strays = root.walkTopDown().filter { it.name.endsWith(".tmp") }.toList()
        assertTrue("left temp files: $strays", strays.isEmpty())
    }

    @Test
    fun `newRunId sorts chronologically as text`() {
        val early = EvalSuiteStore.newRunId(1_700_000_000_000L, "baseline")
        val late = EvalSuiteStore.newRunId(1_800_000_000_000L, "baseline")
        assertTrue("$early should sort before $late", early < late)
    }

    @Test
    fun `newRunId slugs a label and tolerates an empty one`() {
        assertTrue(EvalSuiteStore.newRunId(1_700_000_000_000L, "Post prompt rewrite!").endsWith("post-prompt-rewrite"))
        assertFalse(EvalSuiteStore.newRunId(1_700_000_000_000L, "   ").endsWith("-"))
    }
}
