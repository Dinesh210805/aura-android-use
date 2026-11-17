package com.aura.aura_ui.agentcore

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 8c: what the screen review sends to the model, and what it writes back. */
class ScreenReviewTest {

    private val words = UiWords.parse(File("src/main/assets/ui_words.txt").readText())
    private var clock = 1_000L
    private val journal = RunJournal(kotlin.io.path.createTempDirectory().toFile(), now = { clock++ })
    private val inputs = mutableListOf<String>()
    private var reply: String? = null
    private var reviewedAt = 0L

    private val review = ScreenReview(
        journal = journal,
        ask = { _, input -> inputs += input; reply },
        words = words,
        isBrowser = { it == "com.android.chrome" },
        appNames = { setOf("Instagram", "Google Pay") },
        ownPackage = "com.aura.aura_ui",
        enabled = { true },
        reviewedAt = { reviewedAt },
        setReviewedAt = { reviewedAt = it },
    )

    /** A frame of 1000x2000 with [els] as (label, flags, class, x, y) boxes 100 px wide. */
    private fun frame(pkg: String, vararg els: Triple<String, String, Pair<Int, Int>>): String =
        """{"v":1,"pkg":"$pkg","w":1000,"h":2000,"e":[""" + els.joinToString(",") { (label, flags, at) ->
            """[${at.first},${at.second},${at.first + 100},${at.second + 100},"$label","$flags","android.widget.Button"]"""
        } + "]}"

    private fun run(id: String, vararg screens: Pair<String, String?>, notes: List<String> = emptyList()) {
        journal.start(id, "a task", "model")
        screens.forEachIndexed { i, (sid, frame) ->
            // A caption of its own per screen, so each is its own file in the Screens tab grouping.
            journal.seen(id, sid, "$sid | screen", if (frame == null) null else "SEEN: $sid | screen", frame = frame, layoutKey = 7)
            notes.getOrNull(i)?.let { journal.tool(id, "tap", """{"som_id":1,"seen":"$it"}""", "ok", true, 0, StepShot(null, "Settings")) }
        }
        journal.end(id, TurnEnd.ANSWERED, "done")
    }

    private val insta = "com.instagram.android"

    @Test
    fun `a username seen in three runs and a note naming it reach the model masked`() = runBlocking {
        repeat(3) { run("r$it", "S1" to frame(insta, Triple("johndoe", "*", 50 to 50)), notes = listOf("opening johndoe's profile")) }
        assertEquals(3, review.review())
        assertEquals(3, inputs.size)
        inputs.forEach { input ->
            assertTrue(input, "item · top-left" in input || "button · top-left" in input)
            assertTrue(input, "opening [name]'s profile" in input)
            assertFalse(input, "johndoe" in input.lowercase())
        }
    }

    @Test
    fun `a name only in a note becomes name`() = runBlocking {
        run("r", "S1" to frame(insta, Triple("Settings", "*", 900 to 50)), notes = listOf("messaging Priya"))
        review.review()
        assertTrue(inputs.single(), "messaging [name]" in inputs.single())
    }

    @Test
    fun `a given name is not a UI word, Settings and an app's name are`() = runBlocking {
        run(
            "r",
            "S1" to frame(insta, Triple("Grace", "*", 50 to 1000), Triple("Settings", "*", 900 to 50), Triple("Add Instagram account", "*", 500 to 1900)),
        )
        review.review()
        val input = inputs.single()
        assertFalse(input, "Grace" in input)
        assertTrue(input, "button · middle-left" in input)
        assertTrue(input, "button \"Settings\" · top-right" in input)
        assertTrue(input, "button \"Add Instagram account\" · bottom-centre" in input)
    }

    @Test
    fun `the word list loads, has no duplicates and holds no common given name`() {
        val lines = File("src/main/assets/ui_words.txt").readLines().filter { it.isNotBlank() }
        assertTrue(lines.size > 1_000)
        assertEquals(lines.size, lines.toSet().size)
        val names = """grace will rose mark may june april august joy hope faith bill jack pat sue art rob max ray dawn
            eve iris ivy lily ruby pearl amber jade olive violet frank gene chase drew grant guy harry lance mike nick
            victor ben sam priya john mary tony kevin lucy emma""".split(Regex("\\s+"))
        assertEquals(50, names.size)
        assertEquals(emptyList<String>(), names.filter { it in lines })
    }

    @Test
    fun `no email, phone number or handle from a fixture run reaches the model`() = runBlocking {
        run(
            "r",
            "S1" to frame(
                insta,
                Triple("john.doe@mail.com", "*", 50 to 50), Triple("+91 98765 43210", "*", 50 to 300),
                Triple("@johnd", "*", 50 to 600), Triple("Message", "*", 900 to 900),
            ),
            notes = listOf("texting +91 98765 43210 at john.doe@mail.com"),
        )
        review.review()
        val input = inputs.single()
        listOf("john", "98765", "43210", "@johnd", "mail.com").forEach { assertFalse("$it leaked: $input", it in input.lowercase()) }
        assertTrue(input, "button \"Message\"" in input)
    }

    @Test
    fun `browsers, sensitive apps and unrecorded screens are never sent`() = runBlocking {
        run(
            "r",
            "S1" to frame("com.android.chrome", Triple("Settings", "*", 50 to 50)),
            "S2" to frame("com.google.android.apps.walletnfcrel", Triple("Settings", "*", 50 to 50)),
            "S3" to null,
            "S4" to frame("com.aura.aura_ui", Triple("Settings", "*", 50 to 50)),
        )
        review.review()
        assertEquals(emptyList<String>(), inputs)
    }

    @Test
    fun `a reply writes one screen note per known id and none for an unknown one`() = runBlocking {
        run("r", "S1" to frame(insta, Triple("Settings", "*", 900 to 50)), "S2" to frame(insta, Triple("Add account", "*", 50 to 1900)))
        reply = """{"screens":[{"id":"S1","name":"Home","description":"The feed."},{"id":"S9","name":"Ghost","description":"Not a screen."}]}"""
        review.review()
        val notes = journal.read().filter { it.type == RunJournal.TYPE_SCREEN_NOTE }
        assertEquals(listOf("S1"), notes.map { it.detail })
        assertEquals("Home: The feed.", notes.single().text)
    }
}
