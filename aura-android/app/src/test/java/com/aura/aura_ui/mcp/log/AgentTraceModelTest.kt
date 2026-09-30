package com.aura.aura_ui.mcp.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceModelTest {

    private fun session(block: SessionLog.() -> Unit) = SessionLog(
        sessionId = "s", startedAtMillis = 0L, agentLabel = null, tokenId = null, source = "agent",
    ).apply(block)

    private fun llm(ts: Long, idx: Int) = LlmCall(
        index = idx, timestampMillis = ts, provider = "Gemini", model = "g", prompt = "p", response = "r",
        promptTokens = 100, completionTokens = 10, totalTokens = 110, durationMs = 1000,
    )

    private fun tool(ts: Long, idx: Int, name: String) = ToolInvocation(
        index = idx, timestampMillis = ts, toolName = name, argsJson = "{}", durationMs = 500,
    )

    @Test
    fun `groups each llm call with the tools that follow it`() {
        val s = session {
            llmCalls.add(llm(ts = 10, idx = 0))
            invocations.add(tool(ts = 20, idx = 0, name = "lookup_app"))
            llmCalls.add(llm(ts = 30, idx = 1))
            invocations.add(tool(ts = 40, idx = 1, name = "perceive_screen"))
            invocations.add(tool(ts = 50, idx = 2, name = "tap"))
        }
        val turns = buildAgentTurns(s)
        assertEquals(2, turns.size)
        assertEquals(listOf("lookup_app"), turns[0].tools.map { it.toolName })
        assertEquals(listOf("perceive_screen", "tap"), turns[1].tools.map { it.toolName })
        assertEquals(1, turns[0].number)
        assertEquals(2, turns[1].number)
    }

    @Test
    fun `turn total is llm plus its tools`() {
        val s = session {
            llmCalls.add(llm(ts = 10, idx = 0))   // 1000ms
            invocations.add(tool(ts = 20, idx = 0, name = "tap")) // 500ms
        }
        assertEquals(1500L, buildAgentTurns(s)[0].totalMs)
    }

    @Test
    fun `tools before the first llm call form a leading null-llm turn`() {
        val s = session {
            invocations.add(tool(ts = 5, idx = 0, name = "get_device_status"))
            llmCalls.add(llm(ts = 10, idx = 0))
        }
        val turns = buildAgentTurns(s)
        assertEquals(2, turns.size)
        assertNull(turns[0].llm)
        assertEquals("get_device_status", turns[0].tools.single().toolName)
    }

    @Test
    fun `summary sums tokens and tool count`() {
        val s = session {
            llmCalls.add(llm(ts = 10, idx = 0))
            llmCalls.add(llm(ts = 30, idx = 1))
            invocations.add(tool(ts = 20, idx = 0, name = "tap"))
            endedAtMillis = 100L
        }
        val sum = summarize(s)
        assertEquals(2, sum.llmCount)
        assertEquals(1, sum.toolCount)
        assertEquals(220, sum.totalTokens)
        assertEquals(100L, sum.wallClockMs)
        assertTrue(sum.promptTokens == 200)
    }
}
