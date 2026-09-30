package com.aura.aura_ui.agent.memory

import android.content.Context
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The agent's memory CRUD surface, exercised through the same entry points the Koog glue calls. */
@RunWith(RobolectricTestRunner::class)
class MemoryAgentToolsTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    private fun svc(name: String) = EncryptedMemoryService(EncryptedJsonStore(ctx, name)) { 1000L }
    private fun args(vararg pairs: Pair<String, String>) =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    private val CallToolResult.text: String
        get() = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    @Test fun `save then recall round-trips through the tools`() = runTest {
        val memory = svc("tools_1")
        val tools = MemoryAgentTools(memory)
        tools.save(args("text" to "lives in Coimbatore", "subject" to "home city"))
        val out = tools.recall(args("query" to "Coimbatore"))
        assertFalse(out.isError == true)
        assertTrue(out.text.contains("lives in Coimbatore"))
    }

    @Test fun `recall reports an empty store instead of failing`() = runTest {
        val out = MemoryAgentTools(svc("tools_2")).recall(args("query" to "anything"))
        assertFalse(out.isError == true)
        assertTrue(out.text.contains("Nothing remembered"))
    }

    @Test fun `save result names a replacement so the model knows the correction landed`() = runTest {
        val tools = MemoryAgentTools(svc("tools_3"))
        tools.save(args("text" to "is vegetarian", "subject" to "diet"))
        val out = tools.save(args("text" to "eats chicken now", "subject" to "diet"))
        assertTrue(out.text.startsWith("Updated:"))
        assertTrue(out.text.contains("is vegetarian"))
        assertTrue(out.text.contains("eats chicken now"))
    }

    @Test fun `save refuses a type the model may not write`() = runTest {
        val memory = svc("tools_4")
        val out = MemoryAgentTools(memory).save(args("text" to "did a thing", "type" to "action_log"))
        assertTrue(out.isError == true)
        assertTrue(memory.allEntries().isEmpty())
    }

    @Test fun `unknown type falls back to a fact about the user`() = runTest {
        val memory = svc("tools_5")
        MemoryAgentTools(memory).save(args("text" to "likes filter coffee", "type" to "nonsense"))
        assertEquals(MemoryType.USER, memory.allEntries().single().type)
    }

    @Test fun `save with a stale supersedes id is refused, not silently appended`() = runTest {
        val memory = svc("tools_6")
        val out = MemoryAgentTools(memory).save(args("text" to "new fact", "supersedes" to "does-not-exist"))
        assertTrue(out.isError == true)
        assertTrue(memory.allEntries().isEmpty())
    }

    @Test fun `forget deletes by id`() = runTest {
        val memory = svc("tools_7")
        val tools = MemoryAgentTools(memory)
        val id = memory.save(MemoryType.USER, "temporary fact").id
        val out = tools.forget(args("id" to id))
        assertFalse(out.isError == true)
        assertTrue(memory.allEntries().isEmpty())
    }

    @Test fun `forget refuses a pinned memory - the pin is the user's word`() = runTest {
        val memory = svc("tools_8")
        val id = memory.save(MemoryType.USER, "pinned fact").id
        memory.setPinned(id, true)
        val out = MemoryAgentTools(memory).forget(args("id" to id))
        assertTrue(out.isError == true)
        assertEquals(1, memory.allEntries().size)
    }

    @Test fun `forget on an unknown id is an error, not a crash`() = runTest {
        val out = MemoryAgentTools(svc("tools_9")).forget(args("id" to "nope"))
        assertTrue(out.isError == true)
    }

    @Test fun `missing required args are refused`() = runTest {
        val tools = MemoryAgentTools(svc("tools_10"))
        assertTrue(tools.save(JsonObject(emptyMap())).isError == true)
        assertTrue(tools.recall(JsonObject(emptyMap())).isError == true)
        assertTrue(tools.forget(JsonObject(emptyMap())).isError == true)
    }
}
