package com.aura.aura_ui.voice

import com.aura.aura_ui.agent.voice.vad.FrameScorer
import com.aura.aura_ui.agent.voice.vad.SpeechSegmenter

/**
 * Tracks whether a voice VAD heard speech in the last [holdFrames] frames, so a wake word hit
 * can be rejected when nobody spoke.
 *
 * - Contract: feed every mic chunk to [accept] in order, then read [heard]. Chunks of any length
 *   are cut into the 512-sample frames the scorer needs; the remainder carries over.
 *   Not thread-safe; one detection loop owns it.
 * - Why: the wake word classifier scores fan hum and room noise as high as a real "Hey AURA"
 *   (0.5–0.7 on device), so its threshold alone cannot separate them. Silero VAD is trained on
 *   exactly speech versus non-speech.
 * - Why a hold: the classifier peaks just after the phrase ends, by which time the VAD may
 *   already read silence. 47 frames of 32 ms is about 1.5 s, longer than the phrase.
 */
internal class RecentSpeech(
    private val scorer: FrameScorer,
    private val holdFrames: Int = 47,
    /** Silero probability that counts as speech; 0.5 is Silero's own default. */
    private val minProb: Float = 0.5f,
) {
    private val frame = FloatArray(SpeechSegmenter.FRAME_SAMPLES)
    private var filled = 0
    private var framesSinceSpeech = NEVER

    val heard: Boolean get() = framesSinceSpeech <= holdFrames

    fun accept(pcm: ShortArray, n: Int) {
        for (i in 0 until n) {
            frame[filled++] = pcm[i] / 32768f
            if (filled == frame.size) {
                filled = 0
                framesSinceSpeech = when {
                    scorer.score(frame) >= minProb -> 0
                    framesSinceSpeech == NEVER -> NEVER
                    else -> framesSinceSpeech + 1
                }
            }
        }
    }

    fun reset() {
        scorer.reset()
        filled = 0
        framesSinceSpeech = NEVER
    }

    private companion object {
        const val NEVER = Int.MAX_VALUE
    }
}
