package com.aura.mcp.bridge

/**
 * Port for firing Android's **well-known structured intents** — the standard
 * verbs every phone understands (set alarm, dial, compose SMS, add calendar
 * event, share, navigate). This is how system assistants act: a typed intent
 * is deterministic and cannot mis-tap, so it always beats gesture navigation
 * when one exists for the goal.
 *
 * Safety-by-design: every verb here lands on a CONFIRMATION UI owned by the
 * target app (the dialer with the number pre-filled, the SMS composer with the
 * body pre-filled, the calendar editor, the share sheet). Nothing is sent,
 * called, or saved until the USER taps the final button — the agent only does
 * the prefilling. Direct-send variants (`SmsManager`, `ACTION_CALL`) are
 * deliberately NOT part of this port.
 *
 * Specs are produced by `SystemIntentPlanner` (pure, tested) so argument
 * validation lives on the JVM; the `:app` binding only translates a valid
 * spec into a real `Intent`.
 */
interface SystemIntentBridge {

    fun dispatch(spec: SystemIntentSpec): SystemIntentResult

    /**
     * The device's next scheduled alarm ("07:05 Fri"), or null when there is none or it cannot
     * be read. This is the read-back that lets a SKIP_UI alarm prove itself: nothing appears on
     * screen, so the screen can never be the evidence, but `AlarmManager` knows.
     *
     * Device-wide, not per-app, and only ever the NEXT one — so after four alarms it still names
     * the earliest. A positive signal, never a verdict. Defaulted so fakes and non-Android hosts
     * need no implementation.
     */
    fun nextAlarm(): String? = null
}

/** One validated, dispatchable system verb. */
sealed interface SystemIntentSpec {

    /** `AlarmClock.ACTION_SET_ALARM` — opens the clock app's confirmation UI. */
    data class SetAlarm(val hour: Int, val minute: Int, val label: String?) : SystemIntentSpec

    /** `AlarmClock.ACTION_SET_TIMER`. */
    data class SetTimer(val lengthSeconds: Int, val label: String?) : SystemIntentSpec

    /** `AlarmClock.ACTION_SHOW_ALARMS` — opens the clock's alarm list (check/manage). */
    data object ShowAlarms : SystemIntentSpec

    /** `ACTION_DIAL` — dialer opens pre-filled; the USER places the call. */
    data class Dial(val phoneNumber: String) : SystemIntentSpec

    /** `ACTION_SENDTO smsto:` — composer opens pre-filled; the USER hits send. */
    data class ComposeSms(val phoneNumber: String?, val body: String) : SystemIntentSpec

    /** `CalendarContract` insert — calendar editor opens pre-filled. */
    data class AddCalendarEvent(
        val title: String,
        val startEpochMs: Long?,
        val endEpochMs: Long?,
        val location: String?,
        val notes: String?,
    ) : SystemIntentSpec

    /** `ACTION_SEND text/plain` — the system share sheet. */
    data class ShareText(val text: String, val subject: String?) : SystemIntentSpec

    /**
     * `google.navigation:` / `geo:` — turn-by-turn or map view.
     * @param mode `drive`, `walk`, `bike`, or `transit`; null = app default
     */
    data class Navigate(val destination: String, val mode: String?) : SystemIntentSpec
}

/**
 * @param launched short tag of what was opened (`clock`, `dialer`, `sms_composer`,
 *   `calendar_editor`, `share_sheet`, `maps`) for the agent's verification step
 */
data class SystemIntentResult(
    val success: Boolean,
    val launched: String?,
    val error: String?,
)
