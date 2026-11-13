package com.aura.aura_ui.agentcore

import com.aura.mcp.server.McpToolScopes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every phone tool the agent names in a list must be a tool the phone server has. A renamed or
 * removed server tool otherwise drops silently out of a toolset, the egress rule or the
 * after-action screen read.
 */
class ToolListSyncTest {

    private val server = McpToolScopes.toolScopeMap.keys

    private fun unknown(names: Set<String>) = names - server

    @Test
    fun `every tool the agent lists exists on the phone server`() {
        val lists = mapOf(
            "CORE_TOOLS" to PhoneToolSource.CORE_TOOLS,
            "SCREEN_TOOLS" to PhoneToolSource.SCREEN_TOOLS,
            "EGRESS_TOOLS" to PhoneToolSource.EGRESS_TOOLS,
            "COMMIT_TOOLS" to PhoneToolSource.COMMIT_TOOLS,
            "TRUSTED_RESULTS" to PhoneToolSource.TRUSTED_RESULTS,
            "PRESS_TOOLS" to PhoneToolSource.PRESS_TOOLS,
            "HIDDEN" to PhoneToolSource.HIDDEN,
            "ContextAssembler.SCREEN_CHANGING" to ContextAssembler.SCREEN_CHANGING,
            "ContextAssembler.SCREEN_TOOLS" to ContextAssembler.SCREEN_TOOLS,
        )
        assertEquals(emptyMap<String, Set<String>>(), lists.mapValues { unknown(it.value) }.filterValues { it.isNotEmpty() })
    }
}
