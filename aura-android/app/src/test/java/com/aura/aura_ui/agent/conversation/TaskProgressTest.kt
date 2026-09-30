package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.agent.AgentStreamEvent
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskProgressTest {
    @Test fun `tool-starting maps to a short stage string naming the tool`() {
        assertTrue(AgentStreamEvent.ToolStarting("launch_app", "{}").toProgress().contains("launch_app"))
    }

    @Test fun `finished maps to a done stage`() {
        val p = AgentStreamEvent.Finished("opened spotify").toProgress().lowercase()
        assertTrue(p.contains("done") || p.contains("finish"))
    }

    @Test fun `failed maps to a failure stage`() {
        val p = AgentStreamEvent.ToolFailed("tap", "boom").toProgress().lowercase()
        assertTrue(p.contains("fail") || p.contains("error"))
    }

    // ── progressToolName: the notch-pill verb during Gemini Live runs depends on
    // recovering the tool from the progress string (the Live seam is String-typed),
    // so toProgress() and progressToolName() must stay format-compatible. ──

    @Test fun `tool name round-trips through the progress string for every event type`() {
        org.junit.Assert.assertEquals(
            "perceive_screen",
            progressToolName(AgentStreamEvent.ToolStarting("perceive_screen", "{}").toProgress()),
        )
        org.junit.Assert.assertEquals(
            "tap",
            progressToolName(AgentStreamEvent.ToolCompleted("tap", "ok").toProgress()),
        )
        org.junit.Assert.assertEquals(
            "type_text",
            progressToolName(AgentStreamEvent.ToolFailed("type_text", "boom").toProgress()),
        )
    }

    @Test fun `non-tool progress strings yield no tool name`() {
        org.junit.Assert.assertNull(progressToolName(AgentStreamEvent.Finished("done").toProgress()))
        org.junit.Assert.assertNull(progressToolName("Working on it…"))
    }
}
