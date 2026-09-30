package com.aura.aura_ui.mcp.bridge

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.aura.mcp.bridge.SystemIntentBridge
import com.aura.mcp.bridge.SystemIntentResult
import com.aura.mcp.bridge.SystemIntentSpec
import com.aura.aura_ui.services.startActivityAsAura
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `:app` binding for [SystemIntentBridge] — validated specs in, real Android
 * intents out. Every intent is fired with `FLAG_ACTIVITY_NEW_TASK` (we launch
 * from a service context) and resolution failures come back as tool errors,
 * never crashes.
 *
 * Confirmation model (mirrors Google Assistant):
 *  - set_alarm / set_timer apply DIRECTLY in the clock app (SKIP_UI) — that's
 *    what `com.android.alarm.permission.SET_ALARM` grants.
 *  - dial / compose_sms / add_calendar_event / share_text only PRE-FILL a UI
 *    owned by the target app; the user taps the final button.
 */
class AppSystemIntentBridge(context: Context) : SystemIntentBridge {

    private val appContext = context.applicationContext

    override fun dispatch(spec: SystemIntentSpec): SystemIntentResult {
        val (intent, launched) = when (spec) {
            is SystemIntentSpec.SetAlarm -> Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, spec.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, spec.minute)
                spec.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            } to "clock"

            is SystemIntentSpec.SetTimer -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, spec.lengthSeconds)
                spec.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            } to "clock"

            is SystemIntentSpec.ShowAlarms -> Intent(AlarmClock.ACTION_SHOW_ALARMS) to "clock"

            is SystemIntentSpec.Dial -> Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:${Uri.encode(spec.phoneNumber)}"),
            ) to "dialer"

            is SystemIntentSpec.ComposeSms -> Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("smsto:${Uri.encode(spec.phoneNumber ?: "")}"),
            ).apply {
                putExtra("sms_body", spec.body)
            } to "sms_composer"

            is SystemIntentSpec.AddCalendarEvent -> Intent(
                Intent.ACTION_INSERT,
                CalendarContract.Events.CONTENT_URI,
            ).apply {
                putExtra(CalendarContract.Events.TITLE, spec.title)
                spec.startEpochMs?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
                spec.endEpochMs?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
                spec.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                spec.notes?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            } to "calendar_editor"

            is SystemIntentSpec.ShareText -> Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, spec.text)
                    spec.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                },
                null,
            ) to "share_sheet"

            is SystemIntentSpec.Navigate -> Intent(
                Intent.ACTION_VIEW,
                mapsDirectionsUri(spec.destination, spec.mode),
            ) to "maps"
        }

        return fire(intent, launched)
    }

    /**
     * The device's next alarm, read straight off `AlarmManager` — the proof a SKIP_UI alarm can
     * never leave on screen.
     *
     * Device-wide, covering any app that used `setAlarmClock` (which is what puts the alarm icon
     * in the status bar), and only ever the NEXT one: after four alarms it still names the
     * earliest. That makes it evidence, not a verdict, and the tool presents it that way.
     */
    override fun nextAlarm(): String? = runCatching {
        val triggerMs = appContext.getSystemService(AlarmManager::class.java)
            ?.nextAlarmClock?.triggerTime
        triggerMs?.let { SimpleDateFormat("HH:mm EEE", Locale.getDefault()).format(Date(it)) }
    }.getOrNull()

    /**
     * Universal Maps directions URL — resolves to the Google Maps app when
     * installed (App Links) and any browser otherwise, and it is the only
     * documented form that supports all four travel modes including transit.
     */
    private fun mapsDirectionsUri(destination: String, mode: String?): Uri {
        val travelMode = when (mode) {
            "drive" -> "driving"
            "walk" -> "walking"
            "bike" -> "bicycling"
            "transit" -> "transit"
            else -> null
        }
        val base = "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}"
        return Uri.parse(travelMode?.let { "$base&travelmode=$it" } ?: base)
    }

    private fun fire(intent: Intent, launched: String): SystemIntentResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            appContext.startActivityAsAura(intent)
            SystemIntentResult(success = true, launched = launched, error = null)
        }.getOrElse { t ->
            val reason = when (t) {
                is ActivityNotFoundException -> "no installed app handles this action ($launched)"
                else -> t.message ?: "failed to launch $launched"
            }
            SystemIntentResult(success = false, launched = null, error = reason)
        }
    }
}
