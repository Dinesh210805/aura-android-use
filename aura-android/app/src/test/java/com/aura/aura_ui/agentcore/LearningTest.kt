package com.aura.aura_ui.agentcore

import androidx.room.Room
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The idle review with a scripted model reply, on a real in-memory `agentcore.db`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LearningTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AgentCoreDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.dao()
    private val memory = MemoryStore(dao)
    private val root: File = Files.createTempDirectory("learn").toFile()
    private val skills = SkillStore(File(root, "skills"))
    private val drafts = SkillStore(File(root, "drafts"))
    private var reply = ""
    private val inputs = mutableListOf<String>()
    private val learning = Learning(dao, memory, skills, drafts, ask = { _, input -> inputs += input; reply }, now = { 1_000L })

    @After fun close() = db.close()

    private val trace = Step.encode(
        listOf(
            Step("launch_app", args = mapOf("app" to "Ola")),
            Step("tap", "Where to?"),
            Step("type_text", "Search destination", text = "12 MG Road"),
            Step("tap", "MG Road"),
            Step("tap", "Mini"),
            Step("tap", "Book Mini"),
        ),
    )

    private suspend fun digest(tainted: Boolean, actions: Int = 6, outcome: String = "ANSWERED: Booked.") =
        dao.insertDigest(DigestRow(threadId = "t", atMs = 1, request = "Book an Ola to MG Road", ledger = "", outcome = outcome, tainted = tainted, actions = actions, skill = null, trace = trace))

    private val answer = """```json
        {"memory":[{"action":"add","kind":"notes","text":"In Ola the destination field is labelled Where to?","app":""}],
         "skill":{"name":"book-ola-ride","description":"Book an Ola ride to a place the user names","body":"Open Ola, tap Where to?, type the place, pick it, choose Mini, book."}}
        ```"""

    @Test
    fun `an untainted run writes learned memory and drafts a skill with sanitized steps`() = runBlocking {
        reply = answer
        digest(tainted = false)
        assertEquals(1, learning.review())
        val m = memory.all().single()
        assertFalse(m.staged)
        assertEquals(MemorySource.LEARNED.name, m.source)
        val draft = drafts.list().single()
        assertEquals(6, draft.steps.size)
        assertEquals(Step.TEXT_SLOT, draft.steps[2].text)
        assertTrue(skills.list().isEmpty())
        assertFalse("12 MG Road" in inputs.single())
        assertEquals(null, learning.keepDraft("book-ola-ride"))
        assertEquals(listOf("book-ola-ride"), skills.list().map { it.name })
        assertEquals(0, learning.review())
    }

    @Test
    fun `a tainted run only stages memory adds and drops removes`() = runBlocking {
        memory.save(MemoryKind.USER, "Lives in Chennai")
        reply = """{"memory":[{"action":"add","kind":"user","text":"Prefers Uber"},{"action":"remove","old":"Chennai"}],"skill":null}"""
        digest(tainted = true)
        learning.review()
        val rows = memory.all()
        assertEquals(2, rows.size)
        assertTrue(rows.first { it.text == "Prefers Uber" }.staged)
    }

    @Test
    fun `short, failed or cancelled runs are not sent to the model and draft no skill`() = runBlocking {
        reply = answer
        digest(tainted = false, actions = 2)
        digest(tainted = false, outcome = "CANCELLED: ")
        digest(tainted = false, outcome = "FAILED: no network")
        learning.review()
        assertEquals(1, inputs.size)
        assertTrue(drafts.list().isEmpty())
    }
}
