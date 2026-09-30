package com.aura.aura_ui.mcp.log

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `set_plan` runs its own web search through the run's hook chain, which never reaches Koog's
 * tool-event stream — so a pulled session listed `set_plan` and no `browser_open`, while
 * `memoryWrite.path` recorded `["browser_open", …]`. One run, two records, disagreeing.
 */
class NestedCallLogTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `nested calls round-trip through the session log`() {
        val inv = ToolInvocation(index = 0, timestampMillis = 1, toolName = "set_plan", argsJson = null)
        inv.nestedCalls.add(NestedCallLog("browser_open", succeeded = true, detail = "\"how to play liked songs\""))

        val log = SessionLog(sessionId = "s", startedAtMillis = 1, agentLabel = null, tokenId = null)
        log.invocations.add(inv)

        val decoded = json.decodeFromString(SessionLog.serializer(), json.encodeToString(SessionLog.serializer(), log))
        val nested = decoded.invocations.single().nestedCalls.single()

        assertEquals("browser_open", nested.toolName)
        assertTrue(nested.succeeded)
        assertEquals("\"how to play liked songs\"", nested.detail)
    }

    @Test
    fun `a session recorded before the field existed still parses`() {
        val legacy = """
            {"sessionId":"s","startedAtMillis":1,"agentLabel":null,"tokenId":null,
             "invocations":[{"index":0,"timestampMillis":1,"toolName":"set_plan","argsJson":null}]}
        """.trimIndent()

        val log = json.decodeFromString(SessionLog.serializer(), legacy)
        assertTrue("defaults to empty, not null", log.invocations[0].nestedCalls.isEmpty())
        assertEquals(null, log.invocations[0].llmCallIndex)
    }
}
