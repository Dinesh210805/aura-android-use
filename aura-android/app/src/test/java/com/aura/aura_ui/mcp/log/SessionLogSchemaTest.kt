package com.aura.aura_ui.mcp.log

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trace-v2 added optional fields to [SessionLog]/[LlmCall]/[ToolInvocation]. These
 * tests pin the **back-compat contract**: a `metadata.json` written before v2 (no
 * new fields) must still parse, and the new fields must round-trip.
 */
class SessionLogSchemaTest {

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    /** An on-disk log written before Trace-v2 — none of the new keys are present. */
    private val legacyJson = """
        {
          "sessionId": "1782622648548-c1096245",
          "startedAtMillis": 1782622648548,
          "endedAtMillis": 1782622654326,
          "endReason": "failed",
          "agentLabel": "GEMINI / gemini-3.1-flash-lite",
          "tokenId": null,
          "source": "agent",
          "command": "Open Apple Music",
          "invocations": [
            {
              "index": 0, "timestampMillis": 1782622649662, "toolName": "lookup_app",
              "argsJson": "{}", "success": true, "durationMs": 3928,
              "outputSummary": "ok", "hasScreenshot": false
            }
          ],
          "llmCalls": [
            {
              "index": 0, "timestampMillis": 1782622649651, "provider": "Gemini",
              "model": "gemini-3.1-flash-lite", "prompt": "p", "response": "r",
              "promptTokens": 4980, "completionTokens": 19, "totalTokens": 4999, "durationMs": 1052
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `legacy log parses and new fields default to null or false`() {
        val log = json.decodeFromString<SessionLog>(legacyJson)
        assertEquals("1782622648548-c1096245", log.sessionId)
        assertNull("systemPrompt defaults to null for legacy logs", log.systemPrompt)
        assertFalse("somImage defaults to false", log.invocations[0].somImage)
        assertNull("reasoning defaults to null", log.llmCalls[0].reasoning)
        assertNull("finishReason defaults to null", log.llmCalls[0].finishReason)
        // Phase 2: assembly and memoryWrite default to null
        assertNull("assembly defaults to null", log.assembly)
        assertNull("memoryWrite defaults to null", log.memoryWrite)
        // Phase 2: tool invocation's origin/hookDecision/skillLoaded default to null
        assertNull("origin defaults to null", log.invocations[0].origin)
        assertNull("hookDecision defaults to null", log.invocations[0].hookDecision)
        assertNull("skillLoaded defaults to null", log.invocations[0].skillLoaded)
    }

    @Test
    fun `new fields round-trip through encode then decode`() {
        val original = SessionLog(
            sessionId = "s1",
            startedAtMillis = 1L,
            agentLabel = "GEMINI / gemini-3.1-flash-lite",
            tokenId = null,
            source = "agent",
            command = "do a thing",
            systemPrompt = "You are AURA…",
            assembly = RunAssembly(
                localToolCount = 12,
                remoteServers = listOf(RemoteServerInfo("claude-code", 5, "use claude code")),
                skills = listOf("vim", "bash"),
                injectedHints = "Learned: tap faster"
            ),
            memoryWrite = MemoryWrite("play_media", listOf("Library", "Playlists", "Liked Songs"))
        ).apply {
            invocations.add(
                ToolInvocation(
                    index = 0, timestampMillis = 2L, toolName = "perceive_screen",
                    argsJson = "{}", somImage = true,
                    origin = "local",
                    hookDecision = HookDecisionLog("deny", "ActionGuard", "sensitive action blocked"),
                    skillLoaded = "vim"
                ),
            )
            llmCalls.add(
                LlmCall(
                    index = 0, timestampMillis = 3L, provider = "Gemini",
                    model = "gemini-3.1-flash-lite", prompt = "delta", response = "→ tap(...)",
                    reasoning = "I should tap the search box", finishReason = "tool_calls",
                ),
            )
        }

        val decoded = json.decodeFromString<SessionLog>(json.encodeToString(original))
        assertEquals("You are AURA…", decoded.systemPrompt)
        assertTrue(decoded.invocations[0].somImage)
        assertEquals("I should tap the search box", decoded.llmCalls[0].reasoning)
        assertEquals("tool_calls", decoded.llmCalls[0].finishReason)
        // Phase 2: assembly and memory round-trip
        assertNotNull("assembly should round-trip", decoded.assembly)
        assertEquals(12, decoded.assembly?.localToolCount)
        assertEquals(1, decoded.assembly?.remoteServers?.size)
        assertEquals("claude-code", decoded.assembly?.remoteServers?.get(0)?.name)
        assertEquals(2, decoded.assembly?.skills?.size)
        assertEquals("Learned: tap faster", decoded.assembly?.injectedHints)
        assertNotNull("memoryWrite should round-trip", decoded.memoryWrite)
        assertEquals("play_media", decoded.memoryWrite?.goalKey)
        assertEquals(3, decoded.memoryWrite?.path?.size)
        // Phase 2: tool invocation phase 2 fields round-trip
        assertEquals("local", decoded.invocations[0].origin)
        assertNotNull("hookDecision should round-trip", decoded.invocations[0].hookDecision)
        assertEquals("deny", decoded.invocations[0].hookDecision?.kind)
        assertEquals("ActionGuard", decoded.invocations[0].hookDecision?.hook)
        assertEquals("vim", decoded.invocations[0].skillLoaded)
    }
}
