package com.aura.mcp.tools

import com.aura.mcp.bridge.SystemIntentBridge
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.delay
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Long enough for the clock to apply a SKIP_UI intent, short enough not to be felt. */
private const val ALARM_READBACK_MS = 350L

/**
 * The hint for the two SKIP_UI actions. It says the opposite of the normal one on purpose: for an
 * alarm there is no screen to look at, and a model told to "verify via post_action_observation"
 * finds `screen_changed: false` on a success and concludes it failed — which is exactly how a
 * one-call alarm turned into a clock-app tapping session.
 */
private const val SILENT_HINT =
    "Applied in the background — nothing appears on screen, so THIS RESULT is the confirmation. " +
        "Do not read the screen to check it and do not open the clock app. For several alarms, " +
        "call this again with the next time; device_next_alarm only ever names the earliest one."

private const val CONFIRM_UI_HINT =
    "Verify via the post_action_observation block. Where a confirmation UI " +
        "opened (dialer/composer/calendar/share sheet), the user taps the final " +
        "button — do not try to tap it yourself unless the user asked you to."

/**
 * `system_intent` — Android's standard structured verbs, the way system
 * assistants act. A typed intent is deterministic and cannot mis-tap, so it
 * ALWAYS beats gesture navigation when one covers the goal. Every verb lands
 * on a confirmation UI owned by the target app (dialer pre-filled, SMS
 * composer pre-filled, calendar editor, share sheet) — nothing is sent or
 * saved until the USER confirms, except set_alarm/set_timer which the clock
 * app applies directly.
 */
internal fun Server.registerSystemIntentTool(bridge: SystemIntentBridge) {
    scopedTool(
        name = "system_intent",
        description = "Fire a standard Android system verb WITHOUT any screen navigation. " +
            "PREFER THIS over launching apps and tapping whenever the goal matches an action: " +
            "set_alarm (hour, minute?, label?) · set_timer (seconds, label?) · " +
            "dial (phone_number — opens the dialer pre-filled, user places the call) · " +
            "compose_sms (body, phone_number? — opens the composer pre-filled, user hits send) · " +
            "add_calendar_event (title, start?, end? as ISO local datetime like 2026-07-10T15:00, location?, notes?) · " +
            "share_text (text, subject? — opens the share sheet) · " +
            "navigate (destination, mode? drive/walk/bike/transit — opens maps). " +
            "Alarms/timers are set directly; every other verb only PRE-FILLS a confirmation UI, " +
            "so the user makes the final decision.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "action" to stringSchema(
                        "One of: ${SystemIntentPlanner.SUPPORTED_ACTIONS.joinToString(", ")}",
                    ),
                    "hour" to numberSchema("set_alarm: hour 0-23"),
                    "minute" to numberSchema("set_alarm: minute 0-59 (default 0)"),
                    "seconds" to numberSchema("set_timer: length in seconds (1-86400)"),
                    "label" to stringSchema("set_alarm/set_timer: optional label"),
                    "phone_number" to stringSchema("dial/compose_sms: digits, may include +, spaces, dashes"),
                    "body" to stringSchema("compose_sms: message text to pre-fill"),
                    "title" to stringSchema("add_calendar_event: event title"),
                    "start" to stringSchema("add_calendar_event: ISO local datetime, e.g. 2026-07-10T15:00"),
                    "end" to stringSchema("add_calendar_event: ISO local datetime"),
                    "location" to stringSchema("add_calendar_event: optional location"),
                    "notes" to stringSchema("add_calendar_event: optional description"),
                    "text" to stringSchema("share_text: content to share"),
                    "subject" to stringSchema("share_text: optional subject"),
                    "destination" to stringSchema("navigate: place name or address"),
                    "mode" to stringSchema("navigate: drive, walk, bike, or transit"),
                ),
            ),
            required = listOf("action"),
        ),
    ) { request ->
        val args = request.arguments
        val argMap = listOf(
            "hour", "minute", "seconds", "label", "phone_number", "body", "title",
            "start", "end", "location", "notes", "text", "subject", "destination", "mode",
        ).associateWith { args?.stringArg(it) }

        when (val outcome = SystemIntentPlanner.plan(args?.stringArg("action"), argMap)) {
            is SystemIntentPlanner.Outcome.Invalid -> errorResult(outcome.message)
            is SystemIntentPlanner.Outcome.Plan -> {
                val action = args?.stringArg("action")?.trim()?.lowercase().orEmpty()
                val silent = SystemIntentPlanner.isSilent(action)
                val result = bridge.dispatch(outcome.spec)
                // A SKIP_UI action shows nothing, so this result is the only evidence there will
                // ever be — read the alarm back off AlarmManager and put it in the payload. The
                // clock applies the intent asynchronously; one short wait is enough for it to
                // land, and a miss costs a missing field, never a failed call.
                val nextAlarm = if (silent && result.success) {
                    delay(ALARM_READBACK_MS)
                    runCatching { bridge.nextAlarm() }.getOrNull()
                } else {
                    null
                }
                jsonOkPayload(
                    buildJsonObject {
                        put("success", result.success)
                        put("action", action)
                        result.launched?.let { put("launched", it) }
                        result.error?.let { put("error", it) }
                        nextAlarm?.let { put("device_next_alarm", it) }
                        if (result.success) {
                            put("hint", if (silent) SILENT_HINT else CONFIRM_UI_HINT)
                        }
                    },
                    success = result.success,
                )
            }
        }
    }
}
