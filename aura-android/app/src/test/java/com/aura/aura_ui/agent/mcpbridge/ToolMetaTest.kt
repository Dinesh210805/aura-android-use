package com.aura.aura_ui.agent.mcpbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolMetaTest {
    @Test fun `perception tools are read-only and concurrency-safe`() {
        val m = ToolMetaTable.metaFor("perceive_screen")
        assertTrue(m.readOnly); assertTrue(m.concurrencySafe); assertFalse(m.destructive)
    }

    @Test fun `read_screen is read-only`() = assertTrue(ToolMetaTable.metaFor("read_screen").readOnly)

    @Test fun `gestures are not read-only nor concurrency-safe`() {
        val m = ToolMetaTable.metaFor("tap")
        assertFalse(m.readOnly); assertFalse(m.concurrencySafe)
    }

    @Test fun `unknown tools get conservative defaults`() {
        val m = ToolMetaTable.metaFor("totally_unknown_tool")
        assertFalse(m.readOnly); assertFalse(m.concurrencySafe); assertFalse(m.destructive)
        assertEquals(30_000, m.maxResultChars)
    }
}
