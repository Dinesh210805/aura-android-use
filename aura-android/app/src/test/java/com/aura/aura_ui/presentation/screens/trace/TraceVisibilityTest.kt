package com.aura.aura_ui.presentation.screens.trace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visibility policy and the export redactor.
 *
 * These matter more than most UI tests because the failure they guard is silent: nothing crashes
 * when an internal block leaks into a user's trace or into an exported file — it just quietly ends
 * up in somebody else's hands.
 */
class TraceVisibilityTest {

    private val internals = listOf(
        TraceVisibility.Facet.SYSTEM_PROMPT,
        TraceVisibility.Facet.SCREEN_ASCII,
        TraceVisibility.Facet.TOOL_IO,
        TraceVisibility.Facet.PROMPT_DELTA,
        TraceVisibility.Facet.ASSEMBLY,
        TraceVisibility.Facet.BUDGET,
    )

    private val userFacing = listOf(
        TraceVisibility.Facet.THINKING,
        TraceVisibility.Facet.SCREENSHOT,
        TraceVisibility.Facet.SPOKEN,
        TraceVisibility.Facet.TOOL_TIMELINE,
    )

    @Test fun `normal mode hides every internal block`() {
        internals.forEach {
            assertFalse("$it must not reach a user", TraceVisibility.visible(it, debugMode = false))
        }
    }

    /** Over-redacting is its own failure: a trace with no thinking answers nothing. */
    @Test fun `normal mode keeps what the trace is actually for`() {
        userFacing.forEach {
            assertTrue("$it is the point of the trace", TraceVisibility.visible(it, debugMode = false))
        }
    }

    @Test fun `debug mode shows everything`() {
        (internals + userFacing).forEach {
            assertTrue(TraceVisibility.visible(it, debugMode = true))
        }
    }

    /**
     * The rule that makes a forgotten decision safe: a facet nobody added to NORMAL_MODE is
     * hidden, so the cost of forgetting is a missing block rather than a leaked one.
     */
    @Test fun `every facet is decided, and unlisted ones default to hidden`() {
        TraceVisibility.Facet.entries.forEach { facet ->
            val shown = TraceVisibility.visible(facet, debugMode = false)
            assertTrue(
                "$facet is neither in the user-facing list nor hidden — decide it",
                if (facet in userFacing) shown else !shown,
            )
        }
    }

    // ── export redaction ─────────────────────────────────────────────────────

    private val metadata = """
        {
          "sessionId": "s1",
          "command": "open youtube",
          "systemPrompt": "You are AURA...",
          "assembly": { "localToolCount": 58 },
          "budget": { "requests": 4 },
          "invocations": [
            {
              "index": 0,
              "toolName": "read_screen",
              "success": true,
              "durationMs": 12,
              "argsJson": "{\"detail\":\"full\"}",
              "outputSummary": "raw tool output",
              "gridPayload": "+---+\n| A |\n+---+",
              "screenCanvas": "....##...."
            }
          ],
          "llmCalls": [
            { "prompt": "the whole prompt", "response": "I will tap 3", "reasoning": "the button is at 3" }
          ]
        }
    """.trimIndent()

    @Test fun `a normal-mode export carries no system prompt, ascii or raw tool payloads`() {
        val out = TraceRedaction.redactMetadata(metadata, debugMode = false)
        listOf("systemPrompt", "assembly", "budget", "argsJson", "outputSummary", "gridPayload", "screenCanvas")
            .forEach { assertFalse("$it must not be exported", out.contains("\"$it\"")) }
        assertFalse("the grid itself must not survive either", out.contains("| A |"))
    }

    @Test fun `a normal-mode export keeps the thinking, the decision and what ran`() {
        val out = TraceRedaction.redactMetadata(metadata, debugMode = false)
        assertTrue(out.contains("the button is at 3"))
        assertTrue(out.contains("I will tap 3"))
        assertTrue("the timeline is what happened", out.contains("read_screen"))
        assertTrue(out.contains("durationMs"))
        assertTrue("the user's own task must survive", out.contains("open youtube"))
    }

    /** The prompt delta is ours and it is the biggest thing in the file. */
    @Test fun `a normal-mode export drops the per-turn prompt`() {
        val out = TraceRedaction.redactMetadata(metadata, debugMode = false)
        assertFalse(out.contains("the whole prompt"))
    }

    @Test fun `a debug export is byte-identical to what was stored`() {
        assertTrue(
            "debug must not reformat the file — an export is diffed against the session",
            TraceRedaction.redactMetadata(metadata, debugMode = true) == metadata,
        )
    }

    /**
     * A session whose metadata is corrupt is exactly the one someone exports in order to show
     * somebody what broke. Losing it to a parse failure would be the wrong trade.
     */
    @Test fun `unparseable metadata is passed through rather than failing the export`() {
        val junk = "{not json at all"
        assertTrue(junk == TraceRedaction.redactMetadata(junk, debugMode = false))
    }

    @Test fun `a session with no invocations or llm calls redacts cleanly`() {
        val out = TraceRedaction.redactMetadata("""{"sessionId":"s","systemPrompt":"x"}""", debugMode = false)
        assertFalse(out.contains("systemPrompt"))
        assertTrue(out.contains("sessionId"))
    }
}
