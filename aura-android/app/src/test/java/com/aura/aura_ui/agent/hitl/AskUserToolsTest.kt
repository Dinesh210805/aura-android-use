package com.aura.aura_ui.agent.hitl

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserToolsTest {

    private fun text(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString { it.text }

    @Test fun `missing question is a tool error`() = runTest {
        val tools = AskUserTools(AskUserBroker())
        val r = tools.askUser(buildJsonObject {})
        assertTrue(r.isError == true)
        assertTrue(text(r).contains("question"))
    }

    @Test fun `answered question returns the user's answer`() = runTest {
        val broker = AskUserBroker()
        val tools = AskUserTools(broker, timeoutMs = 5_000)
        launch {
            while (broker.pending.value == null) yield()
            broker.answer(broker.pending.value!!.id, "256 GB")
        }
        val r = tools.askUser(
            buildJsonObject {
                put("question", "Which size?")
                putJsonArray("options") { add("128 GB"); add("256 GB") }
            },
        )
        assertTrue(r.isError != true)
        assertTrue(text(r).contains("256 GB"))
    }

    @Test fun `timeout is a non-error result telling the agent to proceed sensibly`() = runTest {
        val tools = AskUserTools(AskUserBroker(), timeoutMs = 50)
        val r = tools.askUser(buildJsonObject { put("question", "Color?") })
        assertTrue("timeout must not feed the failure-loop counter", r.isError != true)
        assertTrue(text(r).contains("did not answer"))
    }

    @Test fun `options are capped and blanks dropped`() = runTest {
        val broker = AskUserBroker()
        val tools = AskUserTools(broker, timeoutMs = 5_000)
        launch {
            while (broker.pending.value == null) yield()
            val p = broker.pending.value!!
            assertEquals(AskUserTools.MAX_OPTIONS, p.options.size)
            assertTrue(p.options.none { it.isBlank() })
            broker.answer(p.id, p.options.first())
        }
        val r = tools.askUser(
            buildJsonObject {
                put("question", "Pick one")
                putJsonArray("options") {
                    add(""); add("A"); add("B"); add("C"); add("D"); add("E"); add("F")
                }
            },
        )
        assertTrue(r.isError != true)
    }
}
