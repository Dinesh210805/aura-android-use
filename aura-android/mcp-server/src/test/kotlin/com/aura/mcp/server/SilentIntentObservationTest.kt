package com.aura.mcp.server

import com.aura.mcp.tools.SystemIntentPlanner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The observation split inside `system_intent`.
 *
 * `set_alarm` and `set_timer` carry `EXTRA_SKIP_UI`: the clock applies them with nothing on
 * screen. Observing them settled for ~1.5 s and then reported `screen_changed: false` on a
 * PERFECTLY SUCCESSFUL alarm — and the tool's hint, the observation's hint and `end_session` all
 * tell the model to verify from that block. Three instructions pointed at a payload that says the
 * work did not happen, and the model did the reasonable thing: abandoned the one-call route and
 * drove the clock app by hand.
 *
 * The other six actions open a real activity, where the observation is exactly right.
 */
class SilentIntentObservationTest {

    private fun args(action: String?): JsonObject =
        JsonObject(action?.let { mapOf("action" to JsonPrimitive(it)) } ?: emptyMap())

    @Test fun `the two SKIP_UI actions are not observed`() {
        assertFalse(PostActionObservation.observes("system_intent", args("set_alarm")))
        assertFalse(PostActionObservation.observes("system_intent", args("set_timer")))
    }

    @Test fun `the actions that open an activity are still observed`() {
        listOf("dial", "compose_sms", "add_calendar_event", "share_text", "navigate", "show_alarms")
            .forEach { assertTrue(PostActionObservation.observes("system_intent", args(it)), it) }
    }

    @Test fun `case and padding do not smuggle an action past the split`() {
        assertFalse(PostActionObservation.observes("system_intent", args("  SET_ALARM ")))
    }

    @Test fun `an unreadable action still gets observed`() {
        // Safe direction: a missing or unknown action means the planner will reject the call
        // anyway, and observing something that showed nothing costs a settle, not correctness.
        assertTrue(PostActionObservation.observes("system_intent", args(null)))
        assertTrue(PostActionObservation.observes("system_intent", args("teleport")))
        assertTrue(PostActionObservation.observes("system_intent", null))
    }

    @Test fun `every other tool keeps its name-only classification`() {
        assertTrue(PostActionObservation.observes("tap", args("set_alarm")))
        assertTrue(PostActionObservation.observes("launch_app", null))
        assertFalse(PostActionObservation.observes("volume_up", null))
        assertFalse(PostActionObservation.observes("read_screen", null))
    }

    @Test fun `the silent set stays a subset of the supported actions`() {
        // A typo here would silently disable the split rather than fail anything.
        assertTrue(SystemIntentPlanner.SUPPORTED_ACTIONS.containsAll(SystemIntentPlanner.SILENT_ACTIONS))
    }

    @Test fun `isSilent tolerates the shapes a model actually sends`() {
        assertTrue(SystemIntentPlanner.isSilent("set_alarm"))
        assertTrue(SystemIntentPlanner.isSilent("Set_Alarm"))
        assertFalse(SystemIntentPlanner.isSilent(null))
        assertFalse(SystemIntentPlanner.isSilent(""))
        assertFalse(SystemIntentPlanner.isSilent("dial"))
    }
}
