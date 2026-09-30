package com.aura.mcp.tools

import com.aura.mcp.bridge.SystemIntentSpec
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure validation layer for the `system_intent` tool: (action, string args) →
 * typed [SystemIntentSpec] or an actionable Invalid message. Every bound and
 * format rule lives here on the JVM; the `:app` intent binding never sees a
 * malformed spec.
 */
object SystemIntentPlanner {

    sealed interface Outcome {
        data class Plan(val spec: SystemIntentSpec) : Outcome
        data class Invalid(val message: String) : Outcome
    }

    val SUPPORTED_ACTIONS: List<String> = listOf(
        "set_alarm", "set_timer", "show_alarms", "dial", "compose_sms",
        "add_calendar_event", "share_text", "navigate",
    )

    /**
     * The actions that finish with NOTHING on screen.
     *
     * `set_alarm` and `set_timer` carry `EXTRA_SKIP_UI`, so the clock applies them in the
     * background — at most a toast. Every other action here opens a real activity (dialer, SMS
     * composer, calendar editor, share sheet, maps, the alarm list), which IS visible.
     *
     * This split is the one home for that fact, and it is load-bearing in three places: the
     * post-action observation is skipped for these (a settle would report `screen_changed: false`
     * on a success and the model reads that as failure), the agent's grounding is not staled by
     * them, and the tool returns its own read-back instead of pointing at the screen.
     */
    val SILENT_ACTIONS: Set<String> = setOf("set_alarm", "set_timer")

    fun isSilent(action: String?): Boolean = action?.trim()?.lowercase() in SILENT_ACTIONS

    private val NAV_MODES = setOf("drive", "walk", "bike", "transit")

    /** Digits with the usual separators — deliberately loose, but no letters. */
    private val PHONE_REGEX = Regex("""^\+?[0-9()\-\s.]{3,20}$""")

    fun plan(
        action: String?,
        args: Map<String, String?>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Outcome = when (action?.trim()?.lowercase()) {
        "set_alarm" -> planSetAlarm(args)
        "set_timer" -> planSetTimer(args)
        "show_alarms" -> Outcome.Plan(SystemIntentSpec.ShowAlarms)
        "dial" -> planDial(args)
        "compose_sms" -> planComposeSms(args)
        "add_calendar_event" -> planCalendarEvent(args, zone)
        "share_text" -> planShareText(args)
        "navigate" -> planNavigate(args)
        else -> Outcome.Invalid(
            "Unknown or missing 'action'. Supported actions: ${SUPPORTED_ACTIONS.joinToString(", ")}.",
        )
    }

    private fun planSetAlarm(args: Map<String, String?>): Outcome {
        val hour = args.int("hour")
            ?: return Outcome.Invalid("set_alarm requires integer 'hour' (0-23); optional 'minute' (0-59) and 'label'.")
        if (hour !in 0..23) return Outcome.Invalid("set_alarm 'hour' must be 0-23, got $hour.")
        val minute = args.int("minute") ?: 0
        if (minute !in 0..59) return Outcome.Invalid("set_alarm 'minute' must be 0-59, got $minute.")
        return Outcome.Plan(SystemIntentSpec.SetAlarm(hour, minute, args.text("label")))
    }

    private fun planSetTimer(args: Map<String, String?>): Outcome {
        val seconds = args.int("seconds")
            ?: return Outcome.Invalid("set_timer requires integer 'seconds' (1-86400); optional 'label'.")
        if (seconds !in 1..86_400) return Outcome.Invalid("set_timer 'seconds' must be 1-86400, got $seconds.")
        return Outcome.Plan(SystemIntentSpec.SetTimer(seconds, args.text("label")))
    }

    private fun planDial(args: Map<String, String?>): Outcome {
        val number = args.text("phone_number")
            ?: return Outcome.Invalid("dial requires 'phone_number' (digits, may include +, spaces, dashes).")
        if (!PHONE_REGEX.matches(number)) {
            return Outcome.Invalid("'$number' does not look like a phone number. Pass digits only (e.g. \"+91 98765 43210\") — call resolve_contact(name) first to turn a contact name into a number.")
        }
        return Outcome.Plan(SystemIntentSpec.Dial(number))
    }

    private fun planComposeSms(args: Map<String, String?>): Outcome {
        val body = args.text("body")
            ?: return Outcome.Invalid("compose_sms requires non-empty 'body'; optional 'phone_number'.")
        val number = args.text("phone_number")
        if (number != null && !PHONE_REGEX.matches(number)) {
            return Outcome.Invalid("'$number' does not look like a phone number. Pass digits only (use resolve_contact(name) for contact names), or omit it to let the user pick the recipient.")
        }
        return Outcome.Plan(SystemIntentSpec.ComposeSms(number, body))
    }

    private fun planCalendarEvent(args: Map<String, String?>, zone: ZoneId): Outcome {
        val title = args.text("title")
            ?: return Outcome.Invalid("add_calendar_event requires 'title'; optional 'start'/'end' (ISO local datetime like 2026-07-10T15:00), 'location', 'notes'.")
        val start = args.text("start")?.let { parseLocalDateTime(it, zone) ?: return badDateTime("start", it) }
        val end = args.text("end")?.let { parseLocalDateTime(it, zone) ?: return badDateTime("end", it) }
        if (start != null && end != null && end < start) {
            return Outcome.Invalid("add_calendar_event 'end' must not be before 'start'.")
        }
        return Outcome.Plan(
            SystemIntentSpec.AddCalendarEvent(title, start, end, args.text("location"), args.text("notes")),
        )
    }

    private fun planShareText(args: Map<String, String?>): Outcome {
        val text = args.text("text")
            ?: return Outcome.Invalid("share_text requires non-empty 'text'; optional 'subject'.")
        return Outcome.Plan(SystemIntentSpec.ShareText(text, args.text("subject")))
    }

    private fun planNavigate(args: Map<String, String?>): Outcome {
        val destination = args.text("destination")
            ?: return Outcome.Invalid("navigate requires 'destination' (place name or address); optional 'mode' (${NAV_MODES.joinToString("/")}).")
        val mode = args.text("mode")?.lowercase()
        if (mode != null && mode !in NAV_MODES) {
            return Outcome.Invalid("navigate 'mode' must be one of ${NAV_MODES.joinToString(", ")}, got '$mode'.")
        }
        return Outcome.Plan(SystemIntentSpec.Navigate(destination, mode))
    }

    private fun badDateTime(field: String, value: String): Outcome.Invalid = Outcome.Invalid(
        "add_calendar_event '$field' must be an ISO local datetime like 2026-07-10T15:00, got '$value'. Convert relative dates yourself first.",
    )

    private fun parseLocalDateTime(raw: String, zone: ZoneId): Long? =
        runCatching { LocalDateTime.parse(raw.trim()).atZone(zone).toInstant().toEpochMilli() }.getOrNull()

    private fun Map<String, String?>.text(key: String): String? = this[key]?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, String?>.int(key: String): Int? = text(key)?.toIntOrNull()
}
