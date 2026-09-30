package com.aura.aura_ui.agent.strategy

/**
 * O2 — provider-agnostic prompt-size estimate that counts IMAGES, not just text.
 *
 * The previous estimate (`textContent().length / 4`) counted zero for the re-injected
 * screenshot — the dominant per-turn cost of a vision agent (~1-2k tokens/image on
 * typical providers) — so the run budget and the compaction threshold both tripped
 * late while real TPM pressure was higher than measured. Text stays at chars/4: the
 * downstream thresholds (`RunBudget`, `compactThresholdTokens`) were tuned
 * against that scale, so changing the text divisor is a re-tune, not a fix.
 */
internal object PromptTokenEstimate {
    /** Rough chars→tokens divisor for text (JSON-heavy text runs closer to 3 — see KDoc). */
    const val CHARS_PER_TOKEN = 4

    /** Flat per-image charge — provider-agnostic middle of the ~1-2k range. */
    const val TOKENS_PER_IMAGE = 1_500

    fun estimate(totalTextChars: Int, imageCount: Int): Int =
        totalTextChars / CHARS_PER_TOKEN + imageCount * TOKENS_PER_IMAGE
}
