package com.aura.mcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Tools reference screen renders [McpToolCatalog.entries]. This guards the two
 * ways that screen could go stale:
 *  1. A classified tool with no human copy (would show its raw name to the user).
 *  2. The derived entry list drifting out of sync with the scope map.
 *
 * [ToolNameListsTest] separately guarantees the scope map itself covers every
 * registered tool, so together these mean: register + classify a tool and it shows
 * up here with real copy, or a test fails loudly.
 */
class McpToolCatalogCoverageTest {

    @Test
    fun `every classified tool has human-readable copy`() {
        val missing = McpToolCatalog.toolsMissingDescription()
        assertTrue(
            missing.isEmpty(),
            "tools classified in McpToolScopes but missing a description in McpToolCatalog " +
                "(they would render their raw name in the Tools screen): $missing",
        )
    }

    @Test
    fun `catalog entries cover exactly the scope map`() {
        assertEquals(
            McpToolScopes.toolScopeMap.keys,
            McpToolCatalog.entries.map { it.name }.toSet(),
            "McpToolCatalog.entries must render every classified tool and no phantom ones",
        )
    }
}
