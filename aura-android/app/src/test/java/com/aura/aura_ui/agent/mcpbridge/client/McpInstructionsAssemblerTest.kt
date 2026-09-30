package com.aura.aura_ui.agent.mcpbridge.client

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpInstructionsAssemblerTest {
    @Test fun `assembles advisory block from connected servers`() {
        val out = McpInstructionsAssembler.assemble(
            listOf(McpConnectionState.Connected(3, "Always call create_issue with a title."))
        )!!
        assertTrue(out.contains("advisory", ignoreCase = true))
        assertTrue(out.contains("create_issue"))
    }

    @Test fun `servers with no instructions contribute nothing`() {
        assertNull(McpInstructionsAssembler.assemble(listOf(McpConnectionState.Connected(1, null))))
    }

    @Test fun `empty list returns null`() {
        assertNull(McpInstructionsAssembler.assemble(emptyList()))
    }
}
