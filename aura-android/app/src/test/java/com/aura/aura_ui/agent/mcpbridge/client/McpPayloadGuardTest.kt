package com.aura.aura_ui.agent.mcpbridge.client

import com.aura.aura_ui.agent.mcpbridge.McpToolInfo
import com.aura.aura_ui.agent.mcpbridge.ToolMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpPayloadGuardTest {
    private fun tool(n: String) = McpToolInfo("srv", n, "d", ToolMeta())

    @Test fun `tool list is capped`() {
        val many = (1..500).map { tool("t$it") }
        assertEquals(McpPayloadGuard.MAX_TOOLS, McpPayloadGuard.capTools(many).size)
    }

    @Test fun `description is truncated`() {
        val long = "x".repeat(5000)
        assertEquals(McpPayloadGuard.MAX_DESC_CHARS, McpPayloadGuard.capDescription(long).length)
    }

    @Test fun `instructions strip control chars but keep newlines`() {
        val bell = 7.toChar() // C0 control char (BEL) — must be stripped, no literal char in source
        val raw = "ab" + bell + "\nc"
        val out = McpPayloadGuard.sanitizeInstructions(raw)!!
        assertFalse(out.contains(bell))
        assertTrue(out.contains('\n'))
        assertEquals("ab\nc", out)
    }

    @Test fun `instructions are capped`() {
        val raw = "y".repeat(5000)
        assertEquals(McpPayloadGuard.MAX_INSTRUCTION_CHARS, McpPayloadGuard.sanitizeInstructions(raw)!!.length)
    }

    @Test fun `null and blank instructions return null`() {
        assertNull(McpPayloadGuard.sanitizeInstructions(null))
        assertNull(McpPayloadGuard.sanitizeInstructions("   "))
    }
}
