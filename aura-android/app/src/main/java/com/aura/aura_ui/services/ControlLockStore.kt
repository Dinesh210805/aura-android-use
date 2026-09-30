package com.aura.aura_ui.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Spec 2026-07-31 — *Control ownership: one lock, three holders*. App-side state.
 *
 * > **human > local agent > MCP client — the human always wins.**
 *
 * Pause, the browser handoff, and refusing remote MCP calls are the same lock changing
 * hands. This class is where that hand-off is remembered; the decisions live elsewhere
 * ([SelfActionWindow] answers "was that event ours?", `ControlLock` answers "what does a
 * held lock mean for a tool call?").
 *
 * ### The lock is only armed while somebody is driving (2026-08-03)
 *
 * The original version armed the trigger permanently, and that produced the headline bug:
 * *"I open the overlay and it says paused."* A person using their phone emits
 * `VIEW_CLICKED` every few seconds, each one re-stamping the idle countdown, so the lock
 * engaged during ordinary phone use and the 30 s auto-release could never fire. By the
 * time the user opened AURA to ask for something, AURA was already "paused" — on nothing.
 *
 * Pausing is only *meaningful* when there is something to pause. If no driver holds the
 * wheel, a human touching their own phone is not an interruption; it is a Tuesday. So
 * [onObservedEvent] is a no-op unless [isDriving].
 *
 * "Driving" deliberately spans **both** drivers, because they fail differently:
 *  - the **local agent** has a clean lifecycle, so it is tracked explicitly
 *    ([noteLocalRunStarted] / [noteLocalRunFinished]);
 *  - a **remote MCP client** has none — it is a PC firing tool calls at a phone with no
 *    session boundary AURA can see — so it is tracked as a rolling window since its last
 *    call ([noteRemoteToolCall]). That case is the one the spec cared about most: the
 *    user's PC must not keep driving a phone they just picked up.
 *
 * Keeping the state dumb is deliberate — the accessibility thread writes it and MCP
 * dispatch threads read it, so the fewer rules living here, the fewer places that race.
 *
 * ### Why resume is explicit
 *
 * The lock is one-way until someone says otherwise. A stray event cannot clear a pause,
 * because "the agent quietly started driving again while the user was still typing" is
 * the exact betrayal the feature exists to prevent.
 *
 * @param scope drives the idle expiry. Injected because the expiry must be a real timer
 *   rather than a side effect of somebody happening to read the state — see
 *   [releaseIfHumanHasStopped].
 */
internal class ControlLockStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * True while the agent is suspended on an `ask_user` question.
     *
     * The lock exists to detect a human taking the wheel *away* from AURA. While a question is
     * pending the opposite is true: the run is stopped, waiting, and the human touching the phone
     * is the run **proceeding**. Every touch involved in answering — the card, the text field, the
     * keyboard that appears over it — is a human signal, so without this the act of answering
     * pauses the run that asked.
     *
     * Suppressed here, at the store, rather than at the event source on purpose. The source is not
     * reliably identifiable: the overlay is AURA's own package and should already be filtered by
     * `PauseTrigger.isHumanSignal(fromOwnApp = true)`, yet the 2026-08-26 eval traces show a pause
     * landing mid-question anyway — most likely from the IME, which is a third package. Gating on
     * *why we are waiting* instead of *who emitted the event* is correct whatever the answer to
     * that turns out to be.
     *
     * Defaults to false so unit tests get a store with no dependency on process-global state; the
     * shared instance wires it to the real broker.
     */
    private val awaitingUserAnswer: () -> Boolean = { false },
) {
    private val _pausedByHuman = MutableStateFlow(false)

    /** Observable so the status pill can hide and the Pause pill appear without polling. */
    val pausedByHuman: StateFlow<Boolean> = _pausedByHuman.asStateFlow()

    /**
     * Cheap synchronous read — this is what the MCP chokepoint calls on every tool.
     *
     * Still expires a stale pause on read. That is now a *backstop* rather than the
     * mechanism: [armExpiry] schedules the real thing. Both exist because they fail in
     * opposite directions — a timer can be starved on a dozing device, and a getter is
     * only as timely as its next caller, which while paused may be never.
     */
    val isPausedByHuman: Boolean get() {
        releaseIfHumanHasStopped()
        return _pausedByHuman.value
    }

    @Volatile
    private var lastDispatch: SelfActionWindow.Dispatch? = null

    @Volatile
    private var lastHumanSignalAtMs: Long? = null

    @Volatile
    private var resumedAtMs: Long? = null

    @Volatile
    private var localRunActive: Boolean = false

    private val _localRunActive = MutableStateFlow(false)

    /**
     * Observable "a local agent run has the wheel", so the on-screen run controls can
     * appear and disappear without polling.
     *
     * Deliberately the **local** half of [isDriving] only. A remote MCP client has no
     * finished event — [isDriving] covers it with a rolling window — and turning that
     * window into a flow would need a timer whose only job is to hide a button. Remote
     * driven runs therefore get no on-screen controls; the notification still carries
     * Cancel.
     */
    val localRunning: StateFlow<Boolean> = _localRunActive.asStateFlow()

    /**
     * The human pressed Pause (or said it) rather than merely touching the phone.
     *
     * This is what makes the button honest. [releaseIfHumanHasStopped] exists to expire a
     * pause that was *inferred* from a touch — the user grabbed their phone, and thirty
     * quiet seconds later they evidently do not care any more. An explicit pause is the
     * opposite: pressing Pause and putting the phone down is the case where the timer
     * would resume the agent behind the user's back, which is exactly the betrayal this
     * lock exists to prevent. So an explicit pause never expires; only [resume] clears it.
     */
    @Volatile
    private var pausedExplicitly: Boolean = false

    @Volatile
    private var lastRemoteCallAtMs: Long? = null

    private var expiryJob: Job? = null

    /**
     * Is anybody actually driving this phone right now?
     *
     * The arming condition for the whole feature. When this is false a human touch is
     * ignored, because there is nothing to take the wheel *from*.
     */
    val isDriving: Boolean get() {
        if (localRunActive) return true
        val lastRemote = lastRemoteCallAtMs ?: return false
        return clock() - lastRemote < REMOTE_DRIVING_WINDOW_MS
    }

    /**
     * What the agent was asked to do, so a refusal can name it: "I'm paused on messaging mum."
     *
     * A refusal that cannot say what it is paused *on* forces the user to remember, at exactly
     * the moment they were interrupted by something else — which is when they remember least.
     */
    @Volatile
    var activeTask: String? = null
        private set

    fun noteTaskStarted(task: String) {
        activeTask = task.take(MAX_TASK_CHARS)
    }

    /** The user abandoned the paused task ("drop it") — clears it and hands the wheel back. */
    fun dropTask() {
        activeTask = null
        resume()
    }

    // ── Who is driving ───────────────────────────────────────────────────────

    /** A local agent run has the wheel. Arms the pause trigger. */
    fun noteLocalRunStarted() {
        localRunActive = true
        _localRunActive.value = true
    }

    /**
     * The local run is over — nothing to pause, so disarm.
     *
     * Also clears [activeTask]: a finished task is not a paused task, and leaving it set
     * makes the next refusal name work that already completed.
     */
    fun noteLocalRunFinished() {
        localRunActive = false
        _localRunActive.value = false
        activeTask = null
    }

    /**
     * A remote MCP client just called a tool. Arms the trigger for [REMOTE_DRIVING_WINDOW_MS].
     *
     * A window rather than a flag because a remote client has no session AURA can observe:
     * there is no "run finished" callback from somebody else's PC, only the observation
     * that calls have stopped arriving.
     */
    fun noteRemoteToolCall() {
        lastRemoteCallAtMs = clock()
    }

    // ── Attribution ──────────────────────────────────────────────────────────

    /**
     * Call immediately BEFORE AURA acts, so its own echo is attributable.
     *
     * [kind] sets how long that echo is expected to last; passing the wrong one costs
     * either a self-pause (too short) or a swallowed human touch (too long). Defaults to
     * the *shortest* window, so a caller that forgets errs toward yielding to the human.
     */
    fun noteDispatch(kind: SelfActionWindow.ActionKind = SelfActionWindow.ActionKind.TAP) {
        lastDispatch = SelfActionWindow.Dispatch(clock(), kind)
    }

    /**
     * The last thing AURA did, for [ControlLockDiagnostics] only.
     *
     * Exposed so an instrumented session can print *why* an event was attributed the way
     * it was — "self=true" alone cannot distinguish a correct call from a settle window
     * that happens to be far too wide.
     */
    val lastDispatchForDiagnostics: SelfActionWindow.Dispatch? get() = lastDispatch

    /**
     * Report an observed device event. Hands the lock to the human unless AURA just acted.
     *
     * Idempotent while already paused: re-pausing is a no-op, and — critically — an event
     * that *looks* self-caused must not clear an existing pause.
     */
    /**
     * @param isHumanSignal whether this event type is one a person produces
     *   ([PauseTrigger.isHumanSignal]). Events that are NOT human signals can still
     *   *extend* AURA's self-attribution — they are the repaint evidence proving the
     *   device is still echoing what AURA did — but they can never cause a pause.
     *
     *   This split is the 2026-08-05 device fix. The call site previously invoked this
     *   method **only** for human signals, so `CONTENT_CHANGED` / `WINDOWS_CHANGED` never
     *   reached the chain and every repaint gap looked like silence. Measured on device
     *   after a tap on WhatsApp's new-group button: effects at 500, 519, 526, 624, 770,
     *   819, 835, 1071, 1286, 1287, 1352 ms — largest gap 236 ms, comfortably inside
     *   [SelfActionWindow.QUIET_GAP_MS] — then a `SCROLLED` at 1359 ms that paused the
     *   agent because none of the preceding effects had been allowed to advance the chain.
     */
    fun onObservedEvent(isHumanSignal: Boolean = true) {
        // Nobody is driving, so there is nothing to interrupt. This is what stops ordinary
        // phone use from leaving AURA "paused" on no task at all.
        if (!isDriving) return

        // The agent asked the user a question and is suspended on the answer. Touching the phone
        // right now IS the answer, not an interruption — see [awaitingUserAnswer]. Placed before
        // attribution so it holds no matter which package the events come from.
        if (awaitingUserAnswer()) return

        val now = clock()

        // Attribution is chained (2026-08-05): an effect arriving within QUIET_GAP_MS of
        // the previous one extends AURA's ownership, so a tap that navigates keeps owning
        // the destination screen's whole repaint instead of pausing on it.
        //
        // Re-stamping is the load-bearing half. Dropping `updated` would leave every chain
        // anchored to the original dispatch and reproduce the exact bug this replaced.
        when (val attribution = SelfActionWindow.attribute(now, lastDispatch)) {
            is SelfActionWindow.Attribution.SelfCaused -> {
                lastDispatch = attribution.updated
                return
            }

            // Not AURA's echo. Only a *human signal* may pause from here; ambient churn
            // (a notification repainting, a background app ticking) is neither AURA's nor
            // a person's, and must do nothing at all.
            is SelfActionWindow.Attribution.Human -> if (!isHumanSignal) return
        }

        // An explicit resume has to STICK. Without this grace period a screen that emits
        // human-ish events on its own — an auto-advancing carousel, a live-updating feed —
        // re-pauses the instant the user taps Resume or says "continue", and there is no
        // way out at all: every tool call is refused, so the agent cannot even leave the
        // screen causing it.
        resumedAtMs?.let { if (now - it < RESUME_GRACE_MS) return }

        // Stamped even when already paused: this is what restarts the auto-release
        // countdown, so somebody using their phone for two minutes does not have AURA
        // resume underneath them at the thirty-second mark.
        lastHumanSignalAtMs = now
        _pausedByHuman.value = true
        armExpiry()
    }

    /**
     * A real finger landed on the glass — reported by [TouchWatchOverlay] via
     * `MotionEvent.ACTION_OUTSIDE`, not inferred from an accessibility event.
     *
     * ### Why this exists (2026-08-05)
     *
     * [SelfActionWindow]'s KDoc opens with *"Android exposes no clean public API for 'a
     * finger touched the screen anywhere'"*, and the entire pause design was built around
     * that assumption: watch for *effects* and try to work out who caused them. That is
     * what produced 13 false pauses in one device session — every one a `VIEW_SCROLLED`
     * from a list settling after AURA's own tap, which is the same event a person flicking
     * the screen produces.
     *
     * The assumption was not quite true. A window carrying `FLAG_WATCH_OUTSIDE_TOUCH`
     * receives `ACTION_OUTSIDE` for touches outside it **without consuming them**, which is
     * an actual hardware touch. Nothing to infer.
     *
     * Self-attribution stays, because `dispatchGesture` injects at the input layer and
     * AURA's own taps reach the overlay too — but here it is easy: an injected touch lands
     * *at* dispatch time, not a second and a half later during a repaint. The window is
     * doing the job it was designed for instead of being stretched to cover animation.
     */
    fun onHumanTouch() {
        if (!isDriving) return

        val now = clock()
        if (SelfActionWindow.attribute(now, lastDispatch) is SelfActionWindow.Attribution.SelfCaused) return

        resumedAtMs?.let { if (now - it < RESUME_GRACE_MS) return }

        lastHumanSignalAtMs = now
        _pausedByHuman.value = true
        armExpiry()
    }

    /**
     * The human takes the wheel deliberately — spoken "pause"/"stop", or a UI control.
     *
     * Distinct from [onObservedEvent] because it must work even when nothing was touched
     * and no gesture was dispatched: saying "stop" from across the room produces no
     * accessibility event at all — and it is honoured whether or not anyone is driving,
     * because an explicit instruction is never noise.
     */
    fun pause() {
        lastHumanSignalAtMs = clock()
        pausedExplicitly = true
        _pausedByHuman.value = true
        // No armExpiry(): see [pausedExplicitly]. A deliberate pause outlasts silence.
    }

    /** The human hands the wheel back. The next tool call simply works. */
    fun resume() {
        lastHumanSignalAtMs = null
        pausedExplicitly = false
        resumedAtMs = clock()
        expiryJob?.cancel()
        expiryJob = null
        _pausedByHuman.value = false
    }

    /**
     * Schedule the idle release for real, instead of hoping somebody reads the getter.
     *
     * This is the other half of the "it says paused" bug. The Pause pill's visibility
     * comes from [pausedByHuman], a `StateFlow`; the release used to live *only* inside
     * the [isPausedByHuman] getter. While paused, every tool call is refused, so the agent
     * stops calling tools — and nothing reads the getter. The pill therefore stayed on
     * screen indefinitely after the user put the phone down: an expiry that structurally
     * cannot run when it is needed most.
     */
    private fun armExpiry() {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(AUTO_RESUME_AFTER_MS)
            releaseIfHumanHasStopped()
        }
    }

    /**
     * Expire a pause once the human has clearly stopped touching the phone.
     *
     * The honest v1 of the spec's real auto-resume (login form gone **and** no touch for
     * ~2 s **and** the window still frontmost). Those conditions need the visible browser
     * window; a bare idle timeout needs nothing, and converts "stuck paused" into "briefly
     * paused" in the meantime.
     */
    private fun releaseIfHumanHasStopped() {
        if (pausedExplicitly) return
        val since = lastHumanSignalAtMs ?: return
        if (clock() - since >= AUTO_RESUME_AFTER_MS) resume()
    }

    companion object {
        /**
         * How long after the human's last touch the lock releases itself.
         *
         * Long enough that somebody actively using their phone is not interrupted —
         * people touch far more often than every 30 s while using a device — and short
         * enough that a false pause is a hiccup rather than a brick.
         */
        const val AUTO_RESUME_AFTER_MS = 30_000L

        /**
         * How long an explicit resume suppresses re-pausing.
         *
         * Short — this is only meant to outlast the burst of events that follows the
         * user's own tap on Resume, plus one tick of a self-animating screen. Any longer
         * and a genuine touch right after resuming would be ignored.
         */
        const val RESUME_GRACE_MS = 3_000L

        /**
         * How long after a remote tool call the phone still counts as being driven.
         *
         * A minute is generous on purpose: an MCP client's gaps are model thinking time,
         * not idleness, and treating a thinking pause as "nobody is driving" would
         * un-arm the trigger in the middle of exactly the scenario this exists for — a
         * PC steering a phone whose owner has just picked it up.
         */
        const val REMOTE_DRIVING_WINDOW_MS = 60_000L

        /** Task text is spoken back, so it is trimmed to something sayable. */
        const val MAX_TASK_CHARS = 120

        /**
         * The one lock for this process.
         *
         * Genuinely process-global, and that is the point rather than a shortcut: the
         * accessibility service, the agent's in-process MCP server and the external
         * WebRTC server are three different objects that must agree on a single answer.
         * Give them an instance each and "the user picked up their phone" reaches some of
         * them and not others — which is the desync the spec warns about when it insists
         * these are one lock, not three features.
         *
         * The constructor parameters exist for tests, which build their own instances and
         * never touch this one.
         */
        val shared: ControlLockStore by lazy {
            ControlLockStore(
                awaitingUserAnswer = {
                    com.aura.aura_ui.agent.hitl.AskUserBroker.shared.pending.value != null
                },
            )
        }
    }
}
