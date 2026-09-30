package com.aura.aura_ui.agent

import com.aura.mcp.server.McpToolScopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notch-pill verb map must know every registered device tool — an unknown
 * tool silently degrades to the generic "Working", which is how the pill ended
 * up stuck on one verb during Live runs. The scope map is the same registry
 * ground truth the guard lists test against.
 */
class ToolVerbsTest {

    @Test fun `every registered device tool has a specific verb`() {
        val generic = McpToolScopes.toolScopeMap.keys.filter { ToolVerbs.verbFor(it) == ToolVerbs.DEFAULT }
        assertTrue(
            "tools with no specific pill verb (add them to ToolVerbs): $generic",
            generic.isEmpty(),
        )
    }

    @Test fun `unknown tools fall back to the generic verb`() {
        assertEquals(ToolVerbs.DEFAULT, ToolVerbs.verbFor("some_future_tool"))
    }

    @Test fun `verbs stay short enough for the notch pill`() {
        val tooLong = McpToolScopes.toolScopeMap.keys.map { ToolVerbs.verbFor(it) }.filter { it.length > 12 }
        assertTrue("pill verbs must stay ≤12 chars: $tooLong", tooLong.isEmpty())
    }

    @Test fun `perception and action verbs differ so the pill visibly narrates`() {
        assertNotEquals(ToolVerbs.verbFor("perceive_screen"), ToolVerbs.verbFor("tap"))
    }
}
