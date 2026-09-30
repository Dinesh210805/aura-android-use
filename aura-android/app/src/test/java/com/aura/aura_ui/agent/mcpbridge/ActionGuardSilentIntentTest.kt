package com.aura.aura_ui.agent.mcpbridge

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The client half of the SKIP_UI split.
 *
 * The server stopped observing `set_alarm`/`set_timer`, which on its own would have made things
 * WORSE here: guard 1e refuses `end_session` after a screen change that carried no observation,
 * so "set an alarm, then finish" would have been blocked waiting for a look at a screen that
 * never changed. The two changes only work together — hence this test beside the server's.
 */
class ActionGuardSilentIntentTest {

    private val alarm = """{"action":"set_alarm","hour":7,"minute":5}"""
    private val dial = """{"action":"dial","phone_number":"+91 98765 43210"}"""

    @Test
    fun `a silent alarm does not stale grounding, so the run can finish`() = runTest {
        val guard = ActionGuard()
        guard.recordAfter("system_intent", alarm, success = true, screenText = """{"success":true}""")
        assertNull(
            "setting an alarm shows nothing — demanding a look before finishing is a deadlock",
            guard.blockReasonFor("end_session", "{}"),
        )
    }

    @Test
    fun `four alarms in a row still finish cleanly`() = runTest {
        val guard = ActionGuard()
        listOf(0, 5, 10, 15).forEach { minute ->
            val args = """{"action":"set_alarm","hour":7,"minute":$minute}"""
            assertNull("alarm at 7:$minute must not be refused", guard.blockReasonFor("system_intent", args))
            guard.recordAfter("system_intent", args, success = true, screenText = """{"success":true}""")
        }
        assertNull(guard.blockReasonFor("end_session", "{}"))
    }

    @Test
    fun `an action that opens a UI is still held to the verify-before-finish rule`() = runTest {
        val guard = ActionGuard()
        // No post_action_observation in the result text — the dialer opened and was never looked at.
        guard.recordAfter("system_intent", dial, success = true, screenText = """{"success":true}""")
        assertNotNull(
            "dial opens the dialer; finishing without confirming it is the old over-claim",
            guard.blockReasonFor("end_session", "{}"),
        )
    }

    @Test
    fun `malformed args fall back to the strict classification`() = runTest {
        val guard = ActionGuard()
        guard.recordAfter("system_intent", "not json at all", success = true, screenText = "{}")
        assertNotNull(
            "an unreadable action is treated as screen-changing, the safe direction",
            guard.blockReasonFor("end_session", "{}"),
        )
    }
}
