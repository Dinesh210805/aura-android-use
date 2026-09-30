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
 * Process-global "is the AURA app currently on screen?" signal, set by
 * MainActivity's onStart/onStop. Drives [ChipVisibilityPolicy]: the status
 * pill shows while the user is in the app and disappears when they leave or
 * swipe it away — the keep-alive service stays up, just invisibly.
 * Same pattern as [VoiceStatusRegistry].
 */
object AppVisibilityRegistry {
    private val _appInForeground = MutableStateFlow(false)
    val appInForeground: StateFlow<Boolean> = _appInForeground.asStateFlow()

    fun set(foreground: Boolean) {
        _appInForeground.value = foreground
    }
}

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


    /**
     * First line of a run, replaced by the model's narration or the first tool's line.
     *
     * Only ever set while a run is actually starting — never during conversation, where
     * "Working…" would be a claim about automation that is not happening. Does not overwrite
     * a line that is already there.
     */
    fun seedWorking() {
        if (_status.value.isBlank()) setStatus("Getting started…")
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
