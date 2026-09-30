package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.agent.voice.vad.SpeechEvent

/**
 * Decides which microphone chunks reach the Live server **while a phone task is running**.
 *
 * ## Why this exists
 *
 * The mic used to be switched off for the whole of `drive_phone` so automation noise could not
 * reach the model. That made AURA half-deaf exactly when the user most wants to interject — you
 * could not ask "how's it going?", correct a wrong turn, or say "stop" — because there was nothing
 * listening. It also made `drive_phone`'s `NON_BLOCKING` declaration half a lie: the model was free
 * to keep talking, the user was not.
 *
 * Simply leaving the mic open is not the answer either. During a task the phone is being driven:
 * apps play sounds, keyboards click, notifications chime, media starts. All of that would stream to
 * Google's server VAD, which would read it as the user taking a turn and answer noise.
 *
 * So the mic stays open and this gate decides what is worth sending: **speech passes, everything
 * else is dropped.** The speech/not-speech judgement comes from the same on-device Silero detector
 * that already powers barge-in, so no new model and no new permission.
 *
 * ## Three details that matter
 *
 * **Pre-roll.** [SpeechEvent.SpeechStart] only fires after ~96 ms of sustained speech, so opening
 * the gate at that instant would clip the first syllable of every sentence — the server would hear
 * "ow's it going" and transcribe nonsense. A small ring of recent chunks is kept while the gate is
 * shut and flushed the moment it opens, so the word arrives whole.
 *
 * **Hold-over.** Closing the instant [SpeechEvent.SpeechEnd] fires would clip trailing consonants
 * and chop a sentence at every natural mid-thought pause. The gate stays open for
 * [holdOverMs] after speech ends.
 *
 * **What this does NOT filter.** Silero detects *speech*, not *your* speech. Dialogue from a video
 * the agent just started will pass. That is an acceptable floor: it is rare, it is recoverable (the
 * model has the task's progress facts and can tell the exchange makes no sense), and the
 * alternative — a deaf assistant for the entire task — is worse. Speaker identification would be
 * the real fix and is far beyond this change.
 *
 * Pure Kotlin, no Android dependencies, driven by an injected clock — so the timing rules above are
 * unit-tested by intent rather than by sleeping.
 */
class TaskAudioGate(
    /** Wall-clock duration of one captured chunk, used to size the pre-roll ring. */
    private val chunkMs: Int,
    private val preRollMs: Int = DEFAULT_PRE_ROLL_MS,
    private val holdOverMs: Int = DEFAULT_HOLD_OVER_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val preRollCapacity = (preRollMs / chunkMs).coerceAtLeast(1)
    private val preRoll = ArrayDeque<ByteArray>(preRollCapacity)

    private var open = false
    private var speechEndedAtMs: Long? = null

    /** True while chunks are being forwarded (speech, or inside the hold-over tail). */
    val isOpen: Boolean get() = open

    /**
     * Offer one captured chunk plus whatever [SpeechEvent]s the segmenter produced for it.
     *
     * Returns the chunks to send, in order — usually empty (silence/noise) or a single chunk
     * (mid-utterance). On the transition into speech it returns the buffered pre-roll followed by
     * this chunk, which is the only case that returns more than one.
     */
    fun accept(chunk: ByteArray, events: List<SpeechEvent>): List<ByteArray> {
        val now = clock()
        var justOpened = false

        events.forEach { event ->
            when (event) {
                SpeechEvent.SpeechStart -> {
                    if (!open) {
                        open = true
                        justOpened = true
                    }
                    // Speech resumed inside the hold-over tail — cancel the pending close.
                    speechEndedAtMs = null
                }
                SpeechEvent.SpeechEnd -> speechEndedAtMs = now
            }
        }

        if (open) {
            val endedAt = speechEndedAtMs
            if (endedAt != null && now - endedAt >= holdOverMs) {
                open = false
                speechEndedAtMs = null
            }
        }

        if (!open) {
            rememberForPreRoll(chunk)
            return emptyList()
        }

        return if (justOpened) drainPreRoll() + chunk else listOf(chunk)
    }

    /** Drop all buffered audio and shut the gate — call when task mode begins or ends. */
    fun reset() {
        preRoll.clear()
        open = false
        speechEndedAtMs = null
    }

    private fun rememberForPreRoll(chunk: ByteArray) {
        if (preRoll.size == preRollCapacity) preRoll.removeFirst()
        preRoll.addLast(chunk)
    }

    private fun drainPreRoll(): List<ByteArray> {
        if (preRoll.isEmpty()) return emptyList()
        val flushed = preRoll.toList()
        preRoll.clear()
        return flushed
    }

    companion object {
        /**
         * How much audio is kept behind the closed gate. Must comfortably exceed the segmenter's
         * `minSpeechMs` (96 ms) or the onset it took to *decide* speech is the onset we lose.
         */
        const val DEFAULT_PRE_ROLL_MS = 320

        /**
         * How long the gate stays open after speech ends. Long enough to carry trailing consonants
         * and a mid-sentence breath; short enough that a task's app noise does not ride out on the
         * tail of an unrelated remark.
         */
        const val DEFAULT_HOLD_OVER_MS = 600
    }
}
