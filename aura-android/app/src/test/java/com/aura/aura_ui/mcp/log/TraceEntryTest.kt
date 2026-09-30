package com.aura.aura_ui.mcp.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A conversation that starts a phone task is one story on one clock. [buildTraceEntries] is
 * what makes the native trace screen able to tell it: speech and agent turns merged by
 * timestamp, rather than the turns-only view that silently dropped every utterance.
 */
class TraceEntryTest {

    private fun session(vararg parts: Any) = SessionLog(
        sessionId = "s",
        startedAtMillis = 0,
        agentLabel = null,
        tokenId = null,
        source = LiveConversationLogger.SOURCE_LIVE,
    ).apply {
        parts.forEach {
            when (it) {
                is Utterance -> utterances.add(it)
                is LlmCall -> llmCalls.add(it)
                is ToolInvocation -> invocations.add(it)
            }
        }
    }

    private fun said(at: Long, text: String) =
        Utterance(atMillis = at, speaker = "user", text = text, kind = "speech")

    private fun llm(at: Long, index: Int = 0) = LlmCall(
        index = index,
        timestampMillis = at,
        provider = "gemini",
        model = "gemini-3.5-flash-lite",
        prompt = "p",
        response = "r",
        promptTokens = null,
        completionTokens = null,
        totalTokens = null,
        durationMs = 10,
    )

    private fun tool(at: Long, name: String, index: Int = 0) =
        ToolInvocation(index = index, timestampMillis = at, toolName = name, argsJson = null)

    @Test
    fun `speech and agent turns interleave in time order`() {
        val entries = buildTraceEntries(
            session(
                said(100, "open Instagram"),
                llm(200),
                tool(210, "open_app"),
                said(300, "you're on your profile"),
            ),
        )

        assertEquals(3, entries.size)
        assertTrue(entries[0] is TraceEntry.Spoken)
        assertTrue(entries[1] is TraceEntry.Turn)
        assertTrue(entries[2] is TraceEntry.Spoken)
        assertEquals("open Instagram", (entries[0] as TraceEntry.Spoken).utterance.text)
        assertEquals("open_app", (entries[1] as TraceEntry.Turn).turn.tools.single().toolName)
    }

    @Test
    fun `a conversation with no task is all speech`() {
        val entries = buildTraceEntries(session(said(1, "hello"), said(2, "hi there")))

        assertEquals(2, entries.size)
        assertTrue(entries.all { it is TraceEntry.Spoken })
    }

    @Test
    fun `an agent run with no speech is unchanged from the turns view`() {
        val s = session(llm(100), tool(110, "tap"), llm(200), tool(210, "type_text", index = 1))
        val entries = buildTraceEntries(s)

        assertEquals(buildAgentTurns(s).size, entries.size)
        assertTrue(entries.all { it is TraceEntry.Turn })
    }

    @Test
    fun `a turn is placed by its LLM call, and a leading toolonly turn by its first tool`() {
        // Tools that precede the first LLM call form a turn with llm = null; it must still sort.
        val entries = buildTraceEntries(
            session(said(50, "before"), tool(100, "perceive_screen"), llm(200), said(300, "after")),
        )

        assertEquals(4, entries.size)
        assertEquals("before", (entries[0] as TraceEntry.Spoken).utterance.text)
        assertTrue("the tool-only turn sorts by its first tool", entries[1] is TraceEntry.Turn)
        assertTrue(entries[2] is TraceEntry.Turn)
        assertEquals("after", (entries[3] as TraceEntry.Spoken).utterance.text)
    }
}
