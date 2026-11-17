package com.aura.aura_ui.agentcore

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Steps 8d and 8d-2: each app's map, built from reviewed runs. */
class AppMapsTest {

    private val dir = kotlin.io.path.createTempDirectory().toFile()
    private var installed = 10L to "1.0"
    private val maps = AppMaps(dir, version = { installed })
    private val insta = "com.instagram.android"

    private var clock = 1_000L
    private val journal = RunJournal(kotlin.io.path.createTempDirectory().toFile(), now = { clock++ })
    private var reviewedAt = 0L
    private val review = ScreenReview(
        journal = journal,
        ask = { _, _ -> """{"screens":[{"id":"S1","name":"Profile","description":"The profile."},{"id":"S2","name":"Settings","description":"All settings."}]}""" },
        words = UiWords.parse(File("src/main/assets/ui_words.txt").readText()),
        maps = maps,
        isBrowser = { false },
        appNames = { setOf("Instagram") },
        ownPackage = "com.aura.aura_ui",
        enabled = { true },
        reviewedAt = { reviewedAt },
        setReviewedAt = { reviewedAt = it },
    )

    private fun frame(vararg labels: Pair<String, Int>) =
        """{"v":1,"pkg":"$insta","w":1000,"h":2000,"e":[""" +
            labels.joinToString(",") { (l, y) -> """[900,$y,1000,${y + 100},"$l","*","android.widget.Button"]""" } + "]}"

    /** A run: S1 (layout 1) with [s1] controls, a tap on [tap], then S2 (layout 2) with a Back button. */
    private fun run(id: String, s1: Array<Pair<String, Int>>, tap: String? = null) {
        journal.start(id, "switch profile", "model")
        journal.seen(id, "S1", "S1 | profile", "SEEN: S1", frame = frame(*s1), layoutKey = 1)
        if (tap != null) {
            journal.tool(id, "tap", """{"som_id":1}""", "ok", true, 0, StepShot(null, tap))
            journal.seen(id, "S2", "S2 | settings", "SEEN: S2", frame = frame("Back" to 50), layoutKey = 2)
        }
        journal.end(id, TurnEnd.ANSWERED, "done")
    }

    private fun visit(key: Long, vararg labels: String) =
        AppMaps.Visit(setOf(key), labels.map { AppMaps.Control(it, "button", "top-right") }, "Settings", "All settings.", emptyList())

    @Test
    fun `a control seen in one run is stored but not shown, and shown after a second run`() {
        maps.merge(insta, "r1", listOf(visit(1, "Settings")))
        assertEquals(listOf("Settings"), maps.read(insta)?.screens?.single()?.controls?.map { it.label })
        assertNull(maps.render(insta))
        maps.merge(insta, "r2", listOf(visit(1, "Settings")))
        val shown = maps.render(insta).orEmpty()
        assertTrue(shown, shown.startsWith(AppMaps.HINT))
        assertTrue(shown, "button \"Settings\" · top-right" in shown)
    }

    @Test
    fun `a tap between two screens leads from one to the other`() = runBlocking {
        repeat(2) { run("r$it", arrayOf("Settings" to 50), tap = "Settings") }
        review.review()
        val s1 = maps.read(insta)?.screens?.first { it.layoutKey == 1L }
        assertEquals(AppMaps.Lead(2), s1?.leadsTo?.get("Settings"))
        assertTrue(maps.render(insta).orEmpty(), "button \"Settings\" · top-right → \"Settings\"" in maps.render(insta).orEmpty())
    }

    @Test
    fun `a username seen in three runs and a masked label are in neither the file nor the map`() = runBlocking {
        repeat(3) { run("r$it", arrayOf("johndoe" to 50, "+91 98765 43210" to 300, "Settings" to 600)) }
        review.review()
        val file = File(dir, "$insta.json").readText()
        listOf("johndoe", "98765", "+91").forEach { assertFalse("$it in $file", it in file) }
        val shown = maps.render(insta).orEmpty()
        assertFalse(shown, "johndoe" in shown)
        assertTrue(shown, "button · top-right" in shown)
        assertTrue(shown, "button \"Settings\"" in shown)
    }

    @Test
    fun `after an app update the map says which version it was recorded on`() {
        repeat(2) { maps.merge(insta, "r$it", listOf(visit(1, "Settings"))) }
        assertFalse(maps.render(insta).orEmpty().contains("Recorded on version"))
        installed = 11L to "2.0"
        maps.merge(insta, "r3", listOf(visit(1, "Settings")))
        assertTrue(maps.render(insta).orEmpty(), "Recorded on version 1.0; the app is now 2.0." in maps.render(insta).orEmpty())
    }

    @Test
    fun `a control missing from two runs of its screen is gone`() {
        repeat(2) { maps.merge(insta, "r$it", listOf(visit(1, "Settings", "Add account"))) }
        maps.merge(insta, "r3", listOf(visit(1, "Settings")))
        assertEquals(listOf("Settings", "Add account"), maps.read(insta)?.screens?.single()?.controls?.map { it.label })
        maps.merge(insta, "r4", listOf(visit(1, "Settings")))
        assertEquals(listOf("Settings"), maps.read(insta)?.screens?.single()?.controls?.map { it.label })
    }
}
