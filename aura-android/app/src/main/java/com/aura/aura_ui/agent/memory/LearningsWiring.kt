package com.aura.aura_ui.agent.memory

import android.content.Context
import com.aura.aura_ui.agent.mcpbridge.hooks.PostToolHook

private const val LEARNINGS_STORE = "aura_learnings"

private fun learningsStore(context: Context) =
    // M8 — learnings hold user-derived text; refuse plaintext persistence on a broken keystore.
    EncryptedLearningsStore(EncryptedJsonStore(context, LEARNINGS_STORE, failClosedWhenUnencrypted = true))

/**
 * Gated write seam (mirror #3's attachSkillsIfAny): returns a LearningsWriteHook when learnings are
 * enabled, else null. Fail-soft: any error -> null (no write hook this run). Caller adds it to the
 * tool chain's extraPostHooks (default-empty => unchanged when null).
 */
internal suspend fun attachLearningsWriteHook(context: Context, goal: String): PostToolHook? =
    runCatching {
        if (!MemoryPrefs(context).learningsEnabled) return null
        LearningsWriteHook(goal, learningsStore(context))
    }.getOrNull()

/**
 * Gated read seam: returns a stale-caveated hint block for this goal, or null when nothing is learned
 * (-> byte-for-byte today's prompt). Appended to the action prompt as a justified dynamic tail (V.6).
 */
internal suspend fun attachLearningsHintsIfAny(context: Context, goal: String): String? =
    runCatching {
        if (!MemoryPrefs(context).learningsEnabled) return null
        val app = AppPackageResolver.resolve(goal)
        val goalType = GoalClassifier.classify(goal)
        learningsStore(context).hintsFor(app, goalType).ifEmpty { null }
    }.getOrNull()
