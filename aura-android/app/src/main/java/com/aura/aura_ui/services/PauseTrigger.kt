package com.aura.aura_ui.services

import android.view.accessibility.AccessibilityEvent

/**
 * Spec 2026-07-31 — which observed events mean *a human is using this phone right now*.
 *
 * Android has no public "a finger touched the screen anywhere" API, so pause fires on the
 * first *effect* rather than the first contact. [SelfActionWindow] filters out effects
 * AURA caused; this filters out effects **nobody** caused, which is the larger problem:
 * `onAccessibilityEvent` is a firehose of notifications, content refreshes, and background
 * app churn. Treat that as a human and the agent pauses on ambient noise forever.
 *
 * Hence an explicit allow-list rather than a denial-list — an unknown event type defaults
 * to "not a human", so a new Android event type cannot silently start pausing the agent.
 *
 * ### Two exclusions worth understanding
 *
 * **`TYPE_WINDOW_STATE_CHANGED`** is named in the spec and is deliberately NOT here. It is
 * the obvious "the user switched apps" signal, but it also fires when *AURA* launches an
 * app — and a cold launch routinely takes longer than the ~800ms self-action window, so
 * including it would make AURA pause itself on its own app launches. A missed app-switch
 * is a smaller failure than an agent that cannot launch anything. Revisit only alongside
 * launch-aware suppression.
 *
 * **AURA's own package** never counts. The overlay animates and its controls are real,
 * clickable views; without this, AURA touching its own UI reads as the user grabbing the
 * phone.
 *
 * ⚠️ This list is a considered starting point, not a measured one. Which events a real
 * finger produces varies by OEM, and the honest test is a device pass: use the phone
 * mid-run and check AURA yields, then let a run proceed untouched and check it never
 * pauses on its own.
 */
internal object PauseTrigger {

    /**
     * Events a person produces by touching the screen. Anything else is ambient.
     *
     * Kept deliberately small, because this service is registered with
     * `android:accessibilityEventTypes="typeAllMask"` — it receives the entire system
     * firehose, from every app, at all times. Anything admitted here fires far more often
     * than intuition suggests.
     *
     * Excluded despite being tempting, each for a specific reason:
     *  - `TYPE_VIEW_FOCUSED` — fires on every screen transition and keyboard show. Pure
     *    noise; it was briefly in this set and one stray event latched the lock.
     *  - `TYPE_VIEW_TEXT_CHANGED` — a person typing produces it, but so does every
     *    programmatic `setText` on the device: chat messages arriving, timers, counters.
     *  - `TYPE_VIEW_SCROLLED` — same, plus a livelock the others lack: an auto-advancing
     *    carousel emits it forever, re-pausing on every tick so no idle timeout could
     *    ever release the lock.
     */
    private val HUMAN_SIGNALS: Set<Int> = setOf(
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
        // TYPE_VIEW_SCROLLED and TYPE_VIEW_TEXT_CHANGED were admitted here 2026-08-02 with
        // the note "these two DO fire programmatically as well, so they cost false pauses.
        // That is now affordable."
        //
        // REMOVED 2026-08-05 — it was not affordable. Device capture on CPH2661 recorded 13
        // false pauses in a single session and **every one of them was TYPE_VIEW_SCROLLED
        // emitted by WhatsApp settling after AURA's own tap** (sinceDispatch 1457–3036ms).
        // Not one was a real touch. Two successive attempts to build a timing filter clever
        // enough to tell "a list finishing its animation" from "a person flicking the
        // screen" both failed, because at the event level they are the same event.
        //
        // The invariant this restores: a human interacts by TOUCHING; a screen changing on
        // its own is not an interaction. An event AURA cannot distinguish from its own
        // effect must not be able to pause AURA.
        //
        // Known cost, accepted deliberately: TYPE_TOUCH_INTERACTION_START only fires when
        // touch exploration (TalkBack) is on, so in practice this set is CLICKED +
        // LONG_CLICKED. A person who scrolls WITHOUT tapping will not pause the agent —
        // their first tap will. A one-action delay is a far smaller failure than a run that
        // pauses itself on its own button press.
        //
        // The real repair is a genuine touch signal (an overlay with
        // FLAG_WATCH_OUTSIDE_TOUCH reports ACTION_OUTSIDE without consuming the touch),
        // which would restore scroll/type detection without inference. Tracked separately.
    )

    fun isHumanSignal(eventType: Int, fromOwnApp: Boolean): Boolean =
        !fromOwnApp && eventType in HUMAN_SIGNALS
}
