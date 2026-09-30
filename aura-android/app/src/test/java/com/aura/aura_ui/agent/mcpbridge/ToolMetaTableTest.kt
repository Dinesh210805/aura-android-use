package com.aura.aura_ui.agent.mcpbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T12 — tool-level destructive classification feeding ConfirmDestructiveHook. */
class ToolMetaTableTest {

    @Test fun `notification_action is destructive - a direct reply sends immediately`() {
        assertTrue(ToolMetaTable.metaFor("notification_action").destructive)
    }

    @Test fun `ordinary gestures and openers are not tool-level destructive`() {
        // Contextual destructiveness (tapping "Send") is server-side SensitivePolicy
        // territory; these stay unflagged by design.
        listOf("tap", "type_text", "launch_app", "open_deeplink", "system_intent", "dismiss_notification")
            .forEach { assertFalse("$it must not be tool-level destructive", ToolMetaTable.metaFor(it).destructive) }
    }

    @Test fun `read-only classification is unchanged`() {
        assertTrue(ToolMetaTable.metaFor("perceive_screen").readOnly)
        assertFalse(ToolMetaTable.metaFor("tap").readOnly)
    }
}
