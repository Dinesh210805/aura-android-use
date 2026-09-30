package com.aura.aura_ui.agent.memory

/** Decay windows for learned lessons (spec 2026-07-17). Tunable in one place. */
object LearningsDecay {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    const val PATH_TTL_MS = 60 * DAY_MS
    const val RECOVERY_TTL_MS = 45 * DAY_MS
    const val QUIRK_TTL_MS = 90 * DAY_MS
    const val ANTI_TTL_MS = 30 * DAY_MS
}

/** Pure consolidation: decay by TTL, de-dup by text, cap — all of it pin-aware and tier-aware. */
object MemoryConsolidator {
    /**
     * Types that expire. Episode summaries and task logs are records of moments and go stale;
     * who the user is, what they prefer and how they speak do not age out on a timer (spec
     * 2026-07-28, matching the "raw history expires fastest, semantic facts persist" rule).
     * A pinned entry is exempt from every rule here — that is what pinning means.
     */
    private val PERISHABLE = setOf(MemoryType.EPISODE, MemoryType.ACTION_LOG, MemoryType.PROJECT)

    /** Best-first: pinned, then best-established, then freshest evidence. */
    private val BEST_FIRST = compareByDescending<MemoryEntry> { it.pinned }
        .thenByDescending { it.confirmations }
        .thenByDescending { it.freshestAtEpochMs }

    fun consolidateEntries(
        entries: List<MemoryEntry>,
        nowEpochMs: Long,
        ttlMs: Long,
        cap: Int,
    ): List<MemoryEntry> {
        val alive = entries.filter {
            val ttl = if (it.subject == MemoryMaintenance.ROLLUP_SUBJECT) maxOf(ttlMs, ROLLUP_TTL_MS) else ttlMs
            it.pinned || it.type !in PERISHABLE || nowEpochMs - it.freshestAtEpochMs <= ttl
        }
        // De-dup within a type: identical text under two types is two different claims.
        val deduped = alive.groupBy { it.type to it.text }
            .map { (_, group) -> group.sortedWith(BEST_FIRST).first() }
            .sortedWith(BEST_FIRST)
        return capByTier(deduped, cap).sortedWith(BEST_FIRST)
    }

    /** A weekly summary is what remains of a week once its day lines are gone; it outlives them. */
    const val ROLLUP_TTL_MS = 365L * 24 * 60 * 60 * 1000

    /**
     * Own ceilings for the history tiers, outside [cap]. History rows are always the newest, and
     * every cap here ranks by freshness, so under one shared ceiling a fortnight of diary would
     * silently evict who the user is ("sister in Coimbatore") to make room for what they did.
     */
    val HISTORY_CAPS: Map<MemoryType, Int> = mapOf(
        MemoryType.EPISODE to 60,
        MemoryType.ACTION_LOG to 150,
    )

    /**
     * Cap each history tier at its [HISTORY_CAPS] ceiling and everything else at [cap], keeping the
     * front of [bestFirst] in each. Pinned entries always stay, counted inside their tier's ceiling.
     * Output is grouped by tier — callers re-order.
     */
    fun capByTier(bestFirst: List<MemoryEntry>, cap: Int): List<MemoryEntry> =
        bestFirst.groupBy { it.type.takeIf { t -> t in HISTORY_CAPS } }
            .flatMap { (tier, group) ->
                val limit = tier?.let { HISTORY_CAPS.getValue(it) } ?: cap
                val (pinned, rest) = group.partition { it.pinned }
                pinned + rest.take((limit - pinned.size).coerceAtLeast(0))
            }

    /** Keep the most-reinforced learnings (highest successCount) up to [cap]. */
    fun consolidateLearnings(entries: List<LearningEntry>, cap: Int): List<LearningEntry> =
        entries.sortedByDescending { it.successCount }.take(cap)

    /**
     * Spec 2026-07-17 — prune verified paths not re-verified within the TTL
     * (recordedAtEpochMs refreshes on reinforcement, so active paths survive).
     */
    fun decayLearnings(entries: List<LearningEntry>, nowEpochMs: Long): List<LearningEntry> =
        entries.filter { nowEpochMs - it.recordedAtEpochMs <= LearningsDecay.PATH_TTL_MS }

    /** Spec 2026-07-17 — prune app facts by per-kind TTL. */
    fun decayFacts(facts: List<AppFactEntry>, nowEpochMs: Long): List<AppFactEntry> =
        facts.filter {
            val ttl = when (it.kind) {
                FactKind.RECOVERY -> LearningsDecay.RECOVERY_TTL_MS
                FactKind.QUIRK -> LearningsDecay.QUIRK_TTL_MS
                FactKind.ANTI_PATTERN -> LearningsDecay.ANTI_TTL_MS
            }
            nowEpochMs - it.recordedAtEpochMs <= ttl
        }
}
