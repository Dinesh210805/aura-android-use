package com.aura.aura_ui.agent.memory

import android.content.Context
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FactReconcilerTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    /** Everything "about food" points one way, so the reconciler's similarity search finds it. */
    private val foodEmbedder = Embedder { texts, _ ->
        texts.map { t -> if (listOf("vegetarian", "chicken", "food", "meat").any { it in t.lowercase() }) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
    }

    private fun svc(name: String) = EncryptedMemoryService(EncryptedJsonStore(ctx, name)).also { it.embedder = foodEmbedder }

    @Test fun `a contradicting fact replaces the old one, and the old one is kept as ended history`() = runTest {
        val memory = svc("reconcile_1")
        memory.save(MemoryType.USER, "is vegetarian")
        val oldId = memory.allEntries().single().id
        val prompts = mutableListOf<String>()
        val reconciler = FactReconciler(memory) { p ->
            prompts += p
            """{"action": "UPDATE", "id": "$oldId", "text": "eats chicken now"}"""
        }

        reconciler.reconcile("eats chicken now")

        assertTrue("the model must be shown the old fact", prompts.single().contains("$oldId: is vegetarian"))
        assertEquals(listOf("eats chicken now"), memory.allEntries().map { it.text })
        val ended = memory.endedEntries().single()
        assertEquals("is vegetarian", ended.text)
        assertTrue(ended.endedAtEpochMs > 0)
    }

    @Test fun `recall shows an ended fact as the past, never as current`() = runTest {
        val memory = svc("reconcile_2")
        memory.save(MemoryType.USER, "is vegetarian")
        val id = memory.allEntries().single().id
        FactReconciler(memory) { """{"action":"DELETE","id":"$id"}""" }.reconcile("stopped being vegetarian")

        assertTrue(memory.allEntries().isEmpty())
        val out = MemoryAgentTools(memory).recall(JsonObject(mapOf("query" to JsonPrimitive("vegetarian"))))
        val text = out.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text, text.contains("NO LONGER TRUE"))
    }

    @Test fun `nothing similar known means a plain add with no model call`() = runTest {
        val memory = svc("reconcile_3")
        memory.save(MemoryType.USER, "sister lives in Coimbatore")
        var called = false
        FactReconciler(memory) { called = true; null }.reconcile("is vegetarian")
        assertEquals(false, called)
        assertEquals(2, memory.allEntries().size)
    }

    @Test fun `a broken or missing model reply never loses the fact`() = runTest {
        val memory = svc("reconcile_4")
        memory.save(MemoryType.USER, "is vegetarian")
        FactReconciler(memory) { "sorry, I can't help with that" }.reconcile("likes spicy food")
        assertTrue(memory.allEntries().any { it.text == "likes spicy food" })
    }

    @Test fun `an UPDATE naming an id that was not offered is stored plainly, touching nothing`() = runTest {
        val memory = svc("reconcile_5")
        memory.save(MemoryType.USER, "is vegetarian")
        FactReconciler(memory) { """{"action":"UPDATE","id":"made-up","text":"eats meat"}""" }.reconcile("eats meat")
        assertEquals(setOf("is vegetarian", "eats meat"), memory.allEntries().map { it.text }.toSet())
        assertTrue(memory.endedEntries().isEmpty())
    }

    @Test fun `a pinned fact cannot be ended by the model`() = runTest {
        val memory = svc("reconcile_6")
        memory.save(MemoryType.USER, "is vegetarian")
        val id = memory.allEntries().single().id
        memory.setPinned(id, true)
        FactReconciler(memory) { """{"action":"DELETE","id":"$id"}""" }.reconcile("eats meat now")
        assertEquals("is vegetarian", memory.allEntries().single().text)
    }

    @Test fun `parse tolerates fences and rejects junk`() {
        assertEquals(FactReconciler.Action.NONE, FactReconciler.parse("```json\n{\"action\":\"none\"}\n```")!!.action)
        assertNull(FactReconciler.parse("{\"action\":\"MERGE\"}"))
        assertNull(FactReconciler.parse("no json"))
    }
}
