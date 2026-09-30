package com.aura.aura_ui.agent.strategy

/**
 * Pure context-eviction policy for the action plane.
 *
 * A phone screenshot from an earlier step is worthless once the screen changed, yet its
 * SoM-JSON (~3–5k tokens) otherwise rides the prompt forever — the unbounded growth that
 * drives the Groq TPM rate-limit spiral. This policy decides *which* perception
 * tool-results are stale: every perception result except the most recent one.
 *
 * Deliberately pure (operates on a list of "which perception tool, if any, this message's
 * tool-result carried") so it is unit-testable without constructing Koog `Message` objects.
 * The thin Koog-message rewrite that consumes it lives in [AuraVisionStrategy] (the text twin
 * of that loop's existing screenshot eviction).
 */
object PerceptionEvictionPolicy {

    /** Replacement text for a collapsed (stale) perception result. */
    const val PLACEHOLDER = "[earlier screen removed]"

    /** Tool names whose results carry an ephemeral, replaceable view of the screen. */
    val PERCEPTION_TOOLS = setOf("perceive_screen", "read_screen", "get_screenshot")

    /**
     * Given, in message order, the perception-tool name each message's tool-result carried
     * (or `null` if the message carried no perception result), return the indices whose
     * perception output should be collapsed to [PLACEHOLDER]: every perception message
     * except the last one. Returns empty when there are zero or one perception messages.
     */
    fun staleIndices(perceptionToolPerMessage: List<String?>): Set<Int> {
        val lastIdx = perceptionToolPerMessage.indexOfLast { it != null }
        if (lastIdx < 0) return emptySet()
        return perceptionToolPerMessage.indices
            .filter { i -> perceptionToolPerMessage[i] != null && i != lastIdx }
            .toSet()
    }

    /**
     * Perception tools that do NOT invalidate a retained screenshot.
     *
     * `read_screen` is here deliberately, and the history matters. O4 below can only
     * fire when a perception tool runs WITHOUT bringing pixels — and `perceive_screen`
     * and `get_screenshot` both always bring an image. So until 2026-08-18 the only tool
     * that could trigger it was `get_ui_tree`, which traces measured at **0 calls in
     * 850**. The rule has never actually run.
     *
     * `read_screen` replacing `get_ui_tree` as the agent's DEFAULT look would have
     * switched it on for the first time, with a consequence nobody chose: the agent
     * escalates to `perceive_screen` precisely because the tree was not enough, then
     * calls `read_screen` on the next turn to re-ground a som_id, and the image it
     * escalated for is dropped — forcing a second CV pass to get it back.
     *
     * A cheap tree read is *supplementary* to an escalation image, not a replacement
     * for it. Its own stale results are still collapsed by [staleIndices]; it simply
     * does not evict pixels.
     */
    val NON_SUPERSEDING_TOOLS = setOf("read_screen")

    /**
     * O4: whether the re-injected screenshot should be dropped this turn. The image
     * channel is normally *replaced* when a new screenshot arrives; a text-only
     * perception that supersedes the visual state drops it instead, so the model cannot
     * ground on a screen whose text twin already reads "[earlier screen — replaced]".
     *
     * See [NON_SUPERSEDING_TOOLS] for why `read_screen` is excluded from that.
     */
    fun shouldDropScreenshot(turnPerceptionTools: List<String>, newScreenshotArrived: Boolean): Boolean =
        !newScreenshotArrived && turnPerceptionTools.any { it !in NON_SUPERSEDING_TOOLS }

    /**
     * O9: how many trailing messages compaction must keep verbatim so the newest
     * re-injected screenshot is never summarized away mid-task. [lastImageIndex] is the
     * index of the newest image-bearing message, or -1 when there is none.
     */
    fun compactKeepCount(messageCount: Int, lastImageIndex: Int, configuredKeep: Int): Int =
        if (lastImageIndex < 0) configuredKeep else maxOf(configuredKeep, messageCount - lastImageIndex)
}
