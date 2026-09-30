package com.aura.mcp.tools

import com.aura.mcp.bridge.SystemIntentSpec
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SystemIntentPlanner is the pure validation layer for the `system_intent`
 * tool: model-supplied (action, args) either becomes a typed, dispatchable
 * [SystemIntentSpec] or an actionable Invalid message. All bounds/format
 * rules live HERE so the Android binding never sees a malformed spec.
 */
class SystemIntentPlannerTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun plan(action: String?, vararg args: Pair<String, String?>) =
        SystemIntentPlanner.plan(action, args.toMap(), utc)

    // ── action routing ───────────────────────────────────────────────────────

    @Test
    fun `unknown action is invalid and lists supported actions`() {
        val outcome = plan("fly_to_moon")
        val invalid = assertIs<SystemIntentPlanner.Outcome.Invalid>(outcome)
        assertTrue("set_alarm" in invalid.message, "supported actions should be listed: ${invalid.message}")
        assertTrue("navigate" in invalid.message)
    }

    @Test
    fun `missing action is invalid`() {
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan(null))
    }

    // ── set_alarm ────────────────────────────────────────────────────────────

    @Test
    fun `set_alarm with valid hour and minute plans`() {
        val outcome = plan("set_alarm", "hour" to "7", "minute" to "30", "label" to "Gym")
        val spec = assertIs<SystemIntentPlanner.Outcome.Plan>(outcome).spec
        assertEquals(SystemIntentSpec.SetAlarm(hour = 7, minute = 30, label = "Gym"), spec)
    }

    @Test
    fun `set_alarm minute defaults to zero`() {
        val spec = assertIs<SystemIntentPlanner.Outcome.Plan>(plan("set_alarm", "hour" to "22")).spec
        assertEquals(SystemIntentSpec.SetAlarm(hour = 22, minute = 0, label = null), spec)
    }

    @Test
    fun `set_alarm rejects out-of-range hour`() {
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("set_alarm", "hour" to "24"))
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("set_alarm", "hour" to "-1"))
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("set_alarm"))
    }

    // ── set_timer ────────────────────────────────────────────────────────────

    @Test
    fun `set_timer plans within bounds and rejects outside`() {
        val spec = assertIs<SystemIntentPlanner.Outcome.Plan>(
            plan("set_timer", "seconds" to "300", "label" to "Tea"),
        ).spec
        assertEquals(SystemIntentSpec.SetTimer(lengthSeconds = 300, label = "Tea"), spec)
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("set_timer", "seconds" to "0"))
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("set_timer", "seconds" to "90000"))
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("set_timer"))
    }

    // ── dial / compose_sms ───────────────────────────────────────────────────

    @Test
    fun `dial accepts plausible phone numbers`() {
        val spec = assertIs<SystemIntentPlanner.Outcome.Plan>(
            plan("dial", "phone_number" to "+91 98765 43210"),
        ).spec
        assertEquals(SystemIntentSpec.Dial("+91 98765 43210"), spec)
    }

    @Test
    fun `dial rejects garbage and missing numbers`() {
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("dial", "phone_number" to "call my mom"))
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("dial"))
    }

    @Test
    fun `compose_sms requires body and validates optional number`() {
        val spec = assertIs<SystemIntentPlanner.Outcome.Plan>(
            plan("compose_sms", "phone_number" to "9876543210", "body" to "On my way"),
        ).spec
        assertEquals(SystemIntentSpec.ComposeSms("9876543210", "On my way"), spec)
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("compose_sms", "phone_number" to "9876543210"))
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("compose_sms", "phone_number" to "not-a-number", "body" to "hi"))
    }

    // ── add_calendar_event ───────────────────────────────────────────────────

    @Test
    fun `calendar event parses ISO local datetimes in the given zone`() {
        val outcome = plan(
            "add_calendar_event",
            "title" to "Dentist",
            "start" to "2026-07-10T15:00",
            "end" to "2026-07-10T15:30",
            "location" to "Clinic",
        )
        val spec = assertIs<SystemIntentPlanner.Outcome.Plan>(outcome).spec
        val event = assertIs<SystemIntentSpec.AddCalendarEvent>(spec)
        val expectedStart = ZonedDateTime.of(2026, 7, 10, 15, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(expectedStart, event.startEpochMs)
        assertEquals(expectedStart + 30 * 60_000L, event.endEpochMs)
        assertEquals("Clinic", event.location)
    }

    @Test
    fun `calendar event rejects bad datetime and end before start`() {
        assertIs<SystemIntentPlanner.Outcome.Invalid>(
            plan("add_calendar_event", "title" to "X", "start" to "tomorrow 3pm"),
        )
        assertIs<SystemIntentPlanner.Outcome.Invalid>(
            plan(
                "add_calendar_event",
                "title" to "X",
                "start" to "2026-07-10T15:00",
                "end" to "2026-07-10T14:00",
            ),
        )
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("add_calendar_event", "start" to "2026-07-10T15:00"))
    }

    // ── share_text / navigate ────────────────────────────────────────────────

    @Test
    fun `share_text requires text`() {
        val spec = assertIs<SystemIntentPlanner.Outcome.Plan>(
            plan("share_text", "text" to "check this out", "subject" to "Link"),
        ).spec
        assertEquals(SystemIntentSpec.ShareText("check this out", "Link"), spec)
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("share_text"))
    }

    @Test
    fun `navigate validates mode`() {
        val spec = assertIs<SystemIntentPlanner.Outcome.Plan>(
            plan("navigate", "destination" to "Chennai Central", "mode" to "transit"),
        ).spec
        assertEquals(SystemIntentSpec.Navigate("Chennai Central", "transit"), spec)
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("navigate", "destination" to "X", "mode" to "teleport"))
        assertIs<SystemIntentPlanner.Outcome.Invalid>(plan("navigate"))
    }
}
