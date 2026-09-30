package com.aura.aura_ui.agent.mcpbridge

import com.aura.mcp.bridge.McpScope
import com.aura.mcp.server.McpToolScopes
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4 (client half) — ActionGuard's hand-maintained name lists must track the server's
 * scope map (the single source of truth `:app` already reuses for run detection).
 * A new WRITE tool that mutates the screen must land in SCREEN_CHANGING_TOOLS or
 * ActionGuard silently stops seeing its screen-staleness — this test makes that loud.
 */
class ActionGuardListsTest {

    private val writeTools =
        McpToolScopes.toolScopeMap.filterValues { it == McpScope.WRITE }.keys

    private val readTools =
        McpToolScopes.toolScopeMap.filterValues { it == McpScope.READ }.keys

    @Test fun `every WRITE tool is tracked as screen-changing or non-visual`() {
        // The two sets partition WRITE: pixels-mutating tools stale the screen;
        // audio/shade tools are exempt ON PURPOSE. A new WRITE tool must be
        // placed in exactly one of them, deliberately.
        val untracked = writeTools - ActionGuard.SCREEN_CHANGING_TOOLS - ActionGuard.NON_VISUAL_TOOLS
        assertTrue(
            "WRITE tools missing from ActionGuard.SCREEN_CHANGING_TOOLS/NON_VISUAL_TOOLS (staleness tracking would silently skip them): $untracked",
            untracked.isEmpty(),
        )
    }

    @Test fun `screen-changing and non-visual sets are disjoint`() {
        val both = ActionGuard.SCREEN_CHANGING_TOOLS intersect ActionGuard.NON_VISUAL_TOOLS
        assertTrue("a tool cannot both stale the screen and be exempt: $both", both.isEmpty())
    }

    @Test fun `screen-changing entries reference real tools`() {
        val phantom =
            (ActionGuard.SCREEN_CHANGING_TOOLS + ActionGuard.NON_VISUAL_TOOLS) - McpToolScopes.toolScopeMap.keys
        assertTrue(
            "SCREEN_CHANGING_TOOLS/NON_VISUAL_TOOLS names no registered tool (typo or removed tool): $phantom",
            phantom.isEmpty(),
        )
    }

    @Test fun `grounding-gated gestures are WRITE tools`() {
        val notWrite = ActionGuard.GESTURE_REQUIRES_GROUNDING - writeTools
        assertTrue(
            "GESTURE_REQUIRES_GROUNDING must contain only WRITE gestures: $notWrite",
            notWrite.isEmpty(),
        )
    }

    @Test fun `grounding tools are READ perception tools`() {
        val notRead = ActionGuard.GROUNDING_TOOLS - readTools
        assertTrue(
            "GROUNDING_TOOLS must contain only READ perception tools: $notRead",
            notRead.isEmpty(),
        )
    }
}
