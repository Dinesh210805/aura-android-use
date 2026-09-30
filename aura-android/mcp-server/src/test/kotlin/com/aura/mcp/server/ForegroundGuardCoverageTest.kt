package com.aura.mcp.server

import com.aura.mcp.bridge.McpScope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T4 pattern — the ForegroundGuard name sets must exactly partition the scope
 * map, so a NEW tool cannot silently skip the gate decision: whoever adds a
 * tool must place it in exactly one of gated/ungated for its scope, on purpose.
 */
class ForegroundGuardCoverageTest {

    private val writeTools =
        McpToolScopes.toolScopeMap.filterValues { it == McpScope.WRITE }.keys
    private val readTools =
        McpToolScopes.toolScopeMap.filterValues { it == McpScope.READ }.keys

    @Test
    fun `every WRITE tool is exactly gated-gesture or ungated-write`() {
        assertEquals(
            writeTools,
            ForegroundGuard.GATED_GESTURES + ForegroundGuard.UNGATED_WRITE_TOOLS,
            "WRITE tools must be partitioned by ForegroundGuard on purpose",
        )
        assertEquals(
            emptySet(),
            ForegroundGuard.GATED_GESTURES intersect ForegroundGuard.UNGATED_WRITE_TOOLS,
        )
    }

    @Test
    fun `every READ tool is exactly gated-perception or ungated-read`() {
        assertEquals(
            readTools,
            ForegroundGuard.GATED_PERCEPTION + ForegroundGuard.UNGATED_READ_TOOLS,
            "READ tools must be partitioned by ForegroundGuard on purpose",
        )
        assertEquals(
            emptySet(),
            ForegroundGuard.GATED_PERCEPTION intersect ForegroundGuard.UNGATED_READ_TOOLS,
        )
    }
}
