package com.aura.aura_ui.services

import com.aura.mcp.bridge.McpScope
import com.aura.mcp.bridge.ToolPhaseSink
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositeToolPhaseSinkTest {

    private class Recording : ToolPhaseSink {
        val events = mutableListOf<String>()
        override fun onPhase(toolName: String, scope: McpScope, phase: ToolPhaseSink.Phase) {
            events.add("$toolName:$phase")
        }
    }

    @Test fun `fans one event out to every sink in order`() {
        val a = Recording()
        val b = Recording()
        CompositeToolPhaseSink(listOf(a, b))
            .onPhase("tap", McpScope.WRITE, ToolPhaseSink.Phase.STARTED)
        assertEquals(listOf("tap:STARTED"), a.events)
        assertEquals(listOf("tap:STARTED"), b.events)
    }

    @Test fun `a throwing sink is isolated - later sinks still see the event`() {
        val exploding = ToolPhaseSink { _, _, _ -> error("boom") }
        val after = Recording()
        CompositeToolPhaseSink(listOf(exploding, after))
            .onPhase("tap", McpScope.WRITE, ToolPhaseSink.Phase.STARTED)
        assertEquals(listOf("tap:STARTED"), after.events)
    }
}
