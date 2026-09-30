package com.aura.aura_ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolInventorySectionTest {

    @Test fun `every registry tool appears in the rendered section`() {
        val names = listOf("perceive_screen", "tap", "ask_user", "use_skill", "brand_new_tool")
        val out = ToolInventorySection.render(names)
        names.forEach { assertTrue("missing $it", out.contains(it)) }
        assertTrue(out.contains("these ${names.size} tools"))
    }

    @Test fun `includes the anti-invention anchor`() {
        val out = ToolInventorySection.render(listOf("tap"))
        assertTrue(out.contains("never invent or guess a tool name"))
    }

    @Test fun `deterministic - sorted and de-duplicated regardless of registry order`() {
        val a = ToolInventorySection.render(listOf("b_tool", "a_tool", "b_tool"))
        val b = ToolInventorySection.render(listOf("a_tool", "b_tool"))
        assertEquals(a, b)
        assertTrue(a.indexOf("a_tool") < a.indexOf("b_tool"))
    }

    @Test fun `blank names are dropped, not rendered`() {
        val out = ToolInventorySection.render(listOf("tap", "", "  "))
        assertTrue(out.contains("these 1 tools"))
    }
}
