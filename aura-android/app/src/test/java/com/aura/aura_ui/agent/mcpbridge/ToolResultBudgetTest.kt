package com.aura.aura_ui.agent.mcpbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultBudgetTest {

    @Test
    fun `under-cap text is returned unchanged`() {
        val t = "small result"
        assertEquals(t, capToolResultText(t, 100))
    }

    @Test
    fun `over-cap text is truncated and carries the marker`() {
        val t = "x".repeat(500)
        val out = capToolResultText(t, 100)
        assertTrue("must be capped near the limit", out.length <= 100 + TRUNCATION_MARKER.length)
        assertTrue("must carry the truncation marker", out.contains(TRUNCATION_MARKER))
    }

    @Test
    fun `prefers a clean closing-brace boundary near the limit`() {
        val json = """{"som_id":1,"label":"A"}{"som_id":2,"label":"B"}""" + "z".repeat(200)
        val out = capToolResultText(json, 28)
        // The cut should land on a '}' boundary (no dangling half-object) before the marker.
        val body = out.removeSuffix(TRUNCATION_MARKER)
        assertTrue("body should end on a closing brace", body.endsWith("}"))
    }
}
