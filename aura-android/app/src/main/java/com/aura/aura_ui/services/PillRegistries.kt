package com.aura.aura_ui.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-global "the overlay's automation pill (progress + Cancel) is
 * currently posted" signal. While true, the AssistantForegroundService chip
 * yields (goes silent) so the notch never shows two AURA pills at once.
 */
object AutomationPillRegistry {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun set(active: Boolean) {
        _active.value = active
    }
}

/**
 * What AURA is doing right now, and what is being said, for the on-screen status strip.
 *
 * ### One writer for the line, on purpose
 *
 * [status] is derived from the tool call by `StatusNarrationHook` — plain, immediate, and
 * factually incapable of describing a tap that never happened.
 *
 * The model's own prose used to compete for this line and win. It came from the raw
 * `message.content` of the LLM response, which is not the one sentence the doctrine asked
 * for: on a thinking model it carries the reasoning echo, and on any model it carries
 * whatever paragraph it felt like writing. That leaked onto the user's screen. The strip
 * shows what the phone actually DID, sourced from tool results — nothing else reaches it.
 */
object AgentStatusRegistry {
    private val _status = MutableStateFlow("")

    /** The line to show. Tool-derived, and the only thing the strip ever displays. */
    val line: StateFlow<String> = _status.asStateFlow()

    /** Who said something out loud. */
    enum class Speaker { AURA, USER }

    data class Utterance(val speaker: Speaker, val text: String)

    private val _speech = MutableStateFlow<Utterance?>(null)

    /**
     * The last thing said out loud, by either side.
     *
     * The user's own words are included — a change from the first cut, which showed only
     * AURA's. When you barge in mid-run you need to see that the interruption registered;
     * a strip that stays silent while you talk looks broken at exactly the wrong moment.
     */
    val speech: StateFlow<Utterance?> = _speech.asStateFlow()

    /** Derived from the tool call — the only source for the strip. */
    fun setStatus(text: String) {
        _status.value = text
        _beat.value = if (text.isBlank()) null else Beat(Kind.WORK, text, _beat.value?.headline)
    }

    /**
     * What kind of moment a line describes — picks its icon, and whether it earns the one colour
     * the strip is allowed ([Kind.HOLD] only: the user is needed, or AURA stopped for safety).
     */
    enum class Kind { WORK, LOOK, ACT, TYPE, NAV, SEARCH, DONE, RECOVER, LEARN, HOLD }

    /**
     * One line of the strip: [text] is what just happened; [headline] is where the run is —
     * the plan step it serves ("Step 2 of 4 · Open the chat"), so a tap reads as progress
     * toward something rather than a tap.
     */
    data class Beat(val kind: Kind, val text: String, val headline: String? = null)

    private val _beat = MutableStateFlow<Beat?>(null)
    val beat: StateFlow<Beat?> = _beat.asStateFlow()

    fun setBeat(beat: Beat) {
        _beat.value = beat
        _status.value = beat.text
    }


    /** The only status line the strip shows during a run: the model is working out what to do. */
    const val THINKING = "Thinking…"

    /** True once the run has acted on the phone; from then on the strip stays off until the run ends. */
    @Volatile
    private var acting = false

    /**
     * An on-device run is starting: forget the last run's line and that it acted, so
     * [seedThinking] can show. Called by the agent before it marks the run, so the overlay never
     * sees the run before this.
     */
    fun startRun() {
        acting = false
        _status.value = ""
        _beat.value = null
    }

    /**
     * The overlay saw a run begin: drop a line left over from an earlier run, so [seedThinking]
     * is not blocked by it. Keeps the state of a run that is already acting.
     */
    fun clearStaleLine() {
        if (acting || _status.value == THINKING) return
        _status.value = ""
        _beat.value = null
    }

    /**
     * Shows [THINKING] while a run has not yet acted on the phone: a spoken question being
     * answered, or a task being planned. Does nothing once the run acts ([noteActing]) or when a
     * line is already up.
     */
    fun seedThinking() {
        if (!acting && _status.value.isBlank()) setStatus(THINKING)
    }

    /** The run ended: [THINKING] is no longer true, so it comes down before the reply's caption. */
    fun endRun() {
        if (_status.value == THINKING) setStatus("")
    }

    /**
     * The run has started driving the phone. Takes [THINKING] down: while a task runs the
     * screen belongs to the task, and the Pause pill is the only AURA surface on it.
     */
    fun noteActing() {
        acting = true
        if (_status.value == THINKING) setStatus("")
    }

    /**
     * A run is taking over: drop whatever was last said.
     *
     * The previous turn's Live caption is about the conversation that just ended, not the
     * work now starting, and leaving it up parks a stale sentence beside a live status line
     * for the whole run. Anything AURA says *during* the run republishes immediately, so
     * nothing current is lost.
     */
    fun clearSpeech() {
        _speech.value = null
    }

    fun setSpoken(text: String) = say(Speaker.AURA, text)

    fun setHeard(text: String) = say(Speaker.USER, text)

    private fun say(speaker: Speaker, text: String) {
        val clean = text.trim()
        _speech.value = if (clean.isBlank()) null else Utterance(speaker, clean)
    }

    fun clear() {
        acting = false
        _status.value = ""
        _beat.value = null
        _speech.value = null
    }
}

/**
 * Takes AURA's own overlays off the glass while a screenshot is being taken.
 *
 * Without this the status strip and the run controls land in every frame the agent
 * perceives — and a white pill carrying a Cancel button, present on every screen, is not
 * merely cosmetic noise: perception would hand it a `som_id` and the model could tap it
 * and cancel its own run.
 *
 * A registry because the capture happens in the accessibility service and the windows
 * belong to the overlay service — two different components, no direct reference between
 * them. Absent hook = nothing to hide, which is the correct behaviour when the overlay
 * service is not running.
 */
object OverlayCaptureGate {

    /** Implemented by the overlay service; called on the main thread. */
    interface Hook {
        fun hideForCapture()
        fun restoreAfterCapture()

        /**
         * Where the TOUCHABLE run controls currently sit, in real display pixels, or null
         * when they are off screen or not yet laid out.
         *
         * Only the controls are reported. The status strip is `FLAG_NOT_TOUCHABLE` and
         * cannot swallow a gesture, so including it would hide the controls for taps that
         * were never at risk — the exact flicker this exists to avoid.
         */
        fun controlsBounds(): OverlayEvasion.Bounds?
    }

    @Volatile
    var hook: Hook? = null
}
