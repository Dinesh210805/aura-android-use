package com.aura.aura_ui.services

/**
 * Spec 2026-07-31 — *self-action window*: was this event AURA's own doing, or a human's?
 *
 * Android exposes no clean public API for "a finger touched the screen anywhere", so
 * pause cannot fire on contact. It fires on the first *effect* — an accessibility event,
 * a window change. The catch is that AURA's own actions produce identical effects, so
 * without this check the agent taps a button, observes the resulting event, concludes the
 * user grabbed the phone, and pauses itself on every single action.
 *
 * Hence: for a short beat after AURA acts, resulting events are treated as its own.
 * Anything outside the beat is a human.
 *
 * ### One window was the bug (2026-08-03)
 *
 * The first version had a single 800 ms constant for everything, and it made AURA pause
 * on its own scrolling. The timings are not comparable:
 *
 * | AURA does | The device emits | For how long |
 * |---|---|---|
 * | Tap | `VIEW_CLICKED` | Immediately, once |
 * | Type | `VIEW_TEXT_CHANGED` | Per commit; an IME trickles them |
 * | **Scroll / swipe** | `VIEW_SCROLLED` | The gesture, **then the fling decays for seconds** |
 * | Launch an app | everything | A cold start paints for seconds |
 *
 * A scroll gesture is itself ~300–800 ms, and when the finger lifts the list keeps moving
 * under its own momentum, emitting `VIEW_SCROLLED` the whole way down. 800 ms from the
 * *start* of the gesture expires mid-fling, so the tail of AURA's own scroll arrived
 * looking exactly like a human flicking the screen. Widening the one constant to cover a
 * fling would have made a tap's window 3 s wide too — swallowing real touches for three
 * seconds after every click, which is the expensive direction.
 *
 * So the settle time is a property of **what AURA did**, not a global. Callers say which
 * kind of action they dispatched and the window follows from it.
 *
 * Ties still break toward the human: too short and AURA pauses on itself (annoying but
 * visible), too long and a genuine touch is swallowed while AURA keeps driving a phone its
 * owner has taken back (a betrayal, and invisible). The numbers below are deliberately the
 * smallest that cover the observed echo, not comfortable margins.
 */
internal object SelfActionWindow {

    /**
     * What AURA just did, and how long the device keeps echoing it.
     *
     * These are *reasoned* upper bounds on echo duration, and the honest note is that
     * fling physics vary by OEM, list implementation and scroll distance. [SCROLL] is the
     * one to re-measure first if AURA is still pausing on itself — see
     * `ControlLockDiagnostics`, which logs the gap between a dispatch and the events that
     * follow it precisely so this stops being a guess.
     */
    enum class ActionKind(val settleMs: Long, val maxSelfMs: Long) {
        /**
         * A tap or long-press. The click event lands immediately; nothing trails it —
         * but a tap that *navigates* is followed by a whole screen repainting, which is
         * why [maxSelfMs] is far larger than [settleMs] here. See [attribute].
         */
        TAP(800L, 4_000L),

        /**
         * Text entry, via `ACTION_SET_TEXT` or the AURA IME's `commitText`.
         *
         * Longer than a tap because a commit is not always one event: soft keyboards
         * deliver composing text in pieces, and a field with a formatter (phone numbers,
         * card numbers) re-emits as it reformats what was just written.
         */
        TYPE(1_200L, 4_000L),

        /**
         * A scroll or swipe — the one that broke the single-window design.
         *
         * Covers the gesture itself plus the fling that follows the finger lifting.
         * Re-stamped on gesture completion by [GestureDispatcher], so in practice this
         * budget is spent almost entirely on the fling rather than on the stroke.
         */
        SCROLL(2_500L, 6_000L),

        /**
         * Starting an app or following a deep link.
         *
         * A cold start paints for seconds, and everything it paints on the way up —
         * text arriving, lists settling — is AURA's doing. This is also why
         * `TYPE_WINDOW_STATE_CHANGED` is not a [PauseTrigger] signal: even four seconds
         * is not reliably enough for a cold start on a loaded device.
         */
        LAUNCH(4_000L, 10_000L),
    }

    /**
     * The last thing AURA did, when it did it, and when its most recent *effect* landed.
     *
     * [lastEffectAtMs] is what makes a navigating tap work (2026-08-05). `settleMs` alone
     * sizes the window by what AURA *did*; the events that matter are produced by what
     * happened *next*. A tap on "Save" opens a screen that paints for seconds, and every
     * frame of that paint is still AURA's doing.
     */
    data class Dispatch(
        val atMs: Long,
        val kind: ActionKind,
        val lastEffectAtMs: Long = atMs,
    )

    /**
     * How long the device may fall silent before the next event is somebody else's.
     *
     * Deliberately the same 350 ms the perception path already calls "settled"
     * (`AgentContextConfig.settleQuietMs`) — one definition of quiet, not two.
     */
    const val QUIET_GAP_MS = 350L

    sealed interface Attribution {
        /** AURA's own echo. [updated] carries the re-stamped dispatch — store it. */
        data class SelfCaused(val updated: Dispatch) : Attribution

        data object Human : Attribution
    }

    /**
     * Was this event AURA's own doing? — and if so, what does the dispatch become?
     *
     * ### Why chaining, and not a bigger constant (2026-08-05)
     *
     * The device traces showed AURA pausing itself with its own "Save" button. A tap is
     * classified [ActionKind.TAP] with an 800 ms window, but tapping Save *navigates*: the
     * destination screen paints for ~2 s, emitting `VIEW_SCROLLED` and `VIEW_TEXT_CHANGED`
     * the whole time. Both are [PauseTrigger] human signals, and they land outside 800 ms.
     * Three `end_session` calls were refused as a result.
     *
     * Widening `TAP.settleMs` to cover it would blind AURA to real touches for seconds
     * after every click — the expensive direction. So instead: an effect arriving within
     * [QUIET_GAP_MS] of the *previous effect* extends the window. AURA keeps ownership for
     * exactly as long as the device keeps echoing, and loses it the moment things go quiet.
     *
     * [ActionKind.maxSelfMs] caps the chain, because a self-animating screen (carousel,
     * live feed) emits forever and would otherwise suppress every human touch for the rest
     * of the run. Ties still break toward the human at both ends.
     */
    fun attribute(eventAtMs: Long, dispatch: Dispatch?): Attribution {
        // Nothing dispatched yet — the opening state of every session. Whatever just
        // happened, AURA did not cause it.
        val last = dispatch ?: return Attribution.Human

        // An earlier event cannot be the effect of a later action. Event queues and clocks
        // are not perfectly ordered, and attributing it to AURA would swallow a real touch.
        if (eventAtMs < last.atMs) return Attribution.Human

        // Ceiling first, so no amount of chaining can outlast it.
        if (eventAtMs - last.atMs >= last.kind.maxSelfMs) return Attribution.Human

        val withinInitialGrace = eventAtMs - last.atMs < last.kind.settleMs
        val chainsToPreviousEffect = eventAtMs - last.lastEffectAtMs < QUIET_GAP_MS

        return if (withinInitialGrace || chainsToPreviousEffect) {
            Attribution.SelfCaused(last.copy(lastEffectAtMs = eventAtMs))
        } else {
            Attribution.Human
        }
    }

    /** Retained for [ControlLockDiagnostics] and the pre-2026-08-05 tests. */
    fun isSelfCaused(eventAtMs: Long, dispatch: Dispatch?): Boolean =
        attribute(eventAtMs, dispatch) is Attribution.SelfCaused
}
