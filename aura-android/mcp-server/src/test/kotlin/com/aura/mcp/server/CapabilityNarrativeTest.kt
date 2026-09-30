package com.aura.mcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 2026-08-02 §7 — the voice model must know the agent's REAL capabilities.
 *
 * The bug this replaces: `AuraCapabilities.text` was a hand-written prose paragraph
 * injected verbatim into both planes, with a KDoc asking contributors to keep it truthful
 * to the real tool surface. That is a manual discipline, and manual disciplines drift —
 * roughly a dozen `browser_*` tools landed and the voice model never heard of any of them.
 * It then did the safe thing a model does when unsure of its own reach: handed the task
 * back ("I've opened it, you do it").
 *
 * So the prose is generated from the registered tool set instead, and the first test below
 * is the load-bearing one: **a tool that no family describes fails the build.** Drift stops
 * being possible rather than being discouraged.
 */
class CapabilityNarrativeTest {

    @Test
    fun `every registered tool is described by some family`() {
        // THE guardrail. Add a tool, forget to describe it, and this fails — which is the
        // whole point. A build failure is the only mechanism that survives the next person
        // who is in a hurry.
        val uncovered = CapabilityNarrative.uncovered(McpToolScopes.toolScopeMap.keys)

        assertTrue(
            uncovered.isEmpty(),
            "these tools have no spoken description, so the voice model cannot know it has " +
                "them — add each to a family in CapabilityNarrative: $uncovered",
        )
    }

    @Test
    fun `the browser tools are actually mentioned`() {
        // The specific regression that caused the report. Not a generic smoke test.
        val text = CapabilityNarrative.narrate(McpToolScopes.toolScopeMap.keys).lowercase()

        assertTrue(text.contains("web"), "browsing must be described: $text")
    }

    @Test
    fun `a family with no registered tools is not claimed`() {
        // Overclaiming is worse than underclaiming: a model that believes it can do
        // something it cannot will promise it to the user and then fail in front of them.
        val text = CapabilityNarrative.narrate(setOf("tap")).lowercase()

        assertTrue(!text.contains("web page"), "claimed browsing with no browser tools: $text")
    }

    @Test
    fun `nothing registered means nothing claimed`() {
        assertEquals("", CapabilityNarrative.narrate(emptySet()))
    }

    @Test
    fun `the narration is safe to speak aloud`() {
        // It is read out by the conversation plane. A tool name like `browser_open` spoken
        // aloud is gibberish, and markdown is worse — this is why generation produces
        // sentences rather than a tool list.
        val text = CapabilityNarrative.narrate(McpToolScopes.toolScopeMap.keys)

        assertTrue(!text.contains("_"), "tool names leaked into speech: $text")
        assertTrue(!text.contains("*") && !text.contains("#"), "markdown in spoken text: $text")
        assertTrue(text.trim().endsWith("."), "spoken text must end as a sentence: $text")
    }
}
