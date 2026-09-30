package com.aura.aura_ui.agent.mcpbridge.hooks

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E6 — ArgSanityHook denies only the CERTAINLY-invalid arg shapes, with a
 * model-readable reason; anything merely unusual proceeds.
 */
class ArgSanityHookTest {

    private val hook = ArgSanityHook()
    private val ctx = HookContext(confirm = { true })

    private suspend fun decide(tool: String, args: kotlinx.serialization.json.JsonObject) =
        hook.onPreTool(tool, args, ctx)

    @Test fun `tap with negative x and no som_id denies`() = runTest {
        val d = decide("tap", buildJsonObject { put("x", -5); put("y", 100) })
        assertTrue(d is PreToolDecision.Deny)
        assertTrue((d as PreToolDecision.Deny).modelReason.contains("som_id"))
    }

    @Test fun `tap with missing coords and no som_id denies`() = runTest {
        assertTrue(decide("tap", buildJsonObject {}) is PreToolDecision.Deny)
    }

    @Test fun `tap with a som_id proceeds without coordinates`() = runTest {
        assertTrue(decide("tap", buildJsonObject { put("som_id", "7") }) is PreToolDecision.Proceed)
    }

    @Test fun `tap with valid coordinates proceeds`() = runTest {
        assertTrue(decide("tap", buildJsonObject { put("x", 10); put("y", 20) }) is PreToolDecision.Proceed)
    }

    @Test fun `swipe missing an endpoint denies`() = runTest {
        val d = decide("swipe", buildJsonObject { put("x1", 1); put("y1", 2); put("x2", 3) })
        assertTrue(d is PreToolDecision.Deny)
        assertTrue((d as PreToolDecision.Deny).modelReason.contains("som_id"))
    }

    @Test fun `swipe by som_id and direction proceeds`() = runTest {
        val d = decide("swipe", buildJsonObject { put("som_id", 7); put("direction", "left") })
        assertTrue(d is PreToolDecision.Proceed)
    }

    @Test fun `swipe with a som_id but no direction denies`() = runTest {
        assertTrue(decide("swipe", buildJsonObject { put("som_id", 7) }) is PreToolDecision.Deny)
    }

    @Test fun `swipe with all four coordinates proceeds`() = runTest {
        val d = decide(
            "swipe",
            buildJsonObject { put("x1", 1); put("y1", 2); put("x2", 3); put("y2", 4) },
        )
        assertTrue(d is PreToolDecision.Proceed)
    }

    @Test fun `long_press with an absurd duration denies`() = runTest {
        val d = decide("long_press", buildJsonObject { put("x", 1); put("y", 2); put("duration_ms", 120_000) })
        assertTrue(d is PreToolDecision.Deny)
    }

    @Test fun `type_text with blank text denies`() = runTest {
        assertTrue(decide("type_text", buildJsonObject { put("text", "  ") }) is PreToolDecision.Deny)
        assertTrue(decide("type_text", buildJsonObject {}) is PreToolDecision.Deny)
    }

    @Test fun `type_text with real text proceeds`() = runTest {
        assertTrue(decide("type_text", buildJsonObject { put("text", "hello") }) is PreToolDecision.Proceed)
    }

    @Test fun `unrelated tools always proceed`() = runTest {
        assertTrue(decide("web_search", buildJsonObject {}) is PreToolDecision.Proceed)
        assertTrue(decide("press_home", buildJsonObject {}) is PreToolDecision.Proceed)
    }

    @Test fun `unparseable coordinate values count as missing`() = runTest {
        assertTrue(decide("tap", buildJsonObject { put("x", "left"); put("y", "top") }) is PreToolDecision.Deny)
    }
}
