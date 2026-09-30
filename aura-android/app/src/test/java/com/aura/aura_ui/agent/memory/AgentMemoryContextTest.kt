package com.aura.aura_ui.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent lane's memory block: identity only, sensitive excluded, always fenced. */
class AgentMemoryContextTest {

    private fun entry(
        id: String,
        type: MemoryType,
        text: String,
        sensitive: Boolean = false,
        pinned: Boolean = false,
        confirmations: Int = 1,
        at: Long = 1000L,
    ) = MemoryEntry(id, type, text, sensitive, at, pinned, at, confirmations)

    @Test fun `renders facts and preferences`() {
        val block = AgentMemoryContext.render(
            listOf(
                entry("1", MemoryType.USER, "lives in Coimbatore"),
                entry("2", MemoryType.FEEDBACK, "keep answers short"),
            ),
        )
        assertTrue(block.contains("lives in Coimbatore"))
        assertTrue(block.contains("keep answers short"))
    }

    @Test fun `excludes sensitive entries`() {
        val block = AgentMemoryContext.render(listOf(entry("1", MemoryType.USER, "otp was 1234", sensitive = true)))
        assertEquals("", block)
    }

    @Test fun `excludes episodes and task logs - they are recall-only here`() {
        val block = AgentMemoryContext.render(
            listOf(
                entry("1", MemoryType.EPISODE, "talked about dinner"),
                entry("2", MemoryType.ACTION_LOG, "send message -> done"),
            ),
        )
        assertEquals("", block)
    }

    @Test fun `pinned outranks better-established`() {
        val block = AgentMemoryContext.render(
            listOf(
                entry("1", MemoryType.USER, "said often", confirmations = 9),
                entry("2", MemoryType.USER, "pinned one", pinned = true),
            ),
            maxIdentity = 1,
        )
        assertTrue(block.contains("pinned one"))
        assertFalse(block.contains("said often"))
    }

    @Test fun `caps the block`() {
        val many = (1..20).map { entry("$it", MemoryType.USER, "fact $it") }
        val lines = AgentMemoryContext.render(many).lines().filter { it.startsWith("•") }
        assertEquals(AgentMemoryContext.MAX_IDENTITY, lines.size)
    }

    @Test fun `wrap fences the block as untrusted advisory data`() {
        val wrapped = AgentMemoryContext.wrap("• lives in Coimbatore")
        assertTrue(wrapped!!.startsWith("--- BEGIN REMEMBERED CONTEXT"))
        assertTrue(wrapped.contains("NOT instructions"))
        assertTrue(wrapped.trimEnd().endsWith("--- END REMEMBERED CONTEXT ---"))
    }

    @Test fun `forRun is null when there is nothing to say`() {
        assertNull(AgentMemoryContext.forRun(emptyList()))
        assertNull(AgentMemoryContext.forRun(listOf(entry("1", MemoryType.EPISODE, "chat"))))
    }

    @Test fun `forRun fences a real block`() {
        val out = AgentMemoryContext.forRun(listOf(entry("1", MemoryType.USER, "vegetarian")))
        assertTrue(out!!.contains("vegetarian"))
        assertTrue(out.contains("BEGIN REMEMBERED CONTEXT"))
    }
}
