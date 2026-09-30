package com.aura.aura_ui.agent.memory

import com.aura.mcp.server.McpToolScopes
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * A verified navigation path learned from a successful run. Steps are PII-scrubbed structure.
 * [recoveries] are dead-end lessons captured mid-run ("X led nowhere → Y worked instead") —
 * learning from tool executions, not just from the task outcome.
 */
@Serializable
data class LearningEntry(
    val appPackage: String,
    val goalType: String,
    val steps: List<String>,
    val recordedAtEpochMs: Long,
    val successCount: Int = 1,
    val recoveries: List<String> = emptyList(),
    // Schema v2 (spec 2026-07-17) — additive, defaults keep v1 JSON readable.
    val appVersion: String = "",
    val source: String = "on-device-agent",
    // Schema v2.1 (memory-usefulness pass) — both neutralized/scrubbed short labels.
    /** What the run was trying to do ("send a whatsapp message") — lets the reading model judge relevance. */
    val goalLabel: String = "",
    /** Headline label of the screen the path landed on — a destination anchor for mid-replay verification. */
    val endsAt: String = "",
)

/**
 * Kinds of app-scoped facts. RECOVERY serves immediately (each pair was evidence-verified
 * mid-run); QUIRK/ANTI_PATTERN serve only once confirmed twice (count >= 2).
 */
enum class FactKind { RECOVERY, QUIRK, ANTI_PATTERN }

/** An app-scoped lesson that is not a full path: a recovery pair, a quirk, or a dead end. */
@Serializable
data class AppFactEntry(
    val appPackage: String,
    val kind: FactKind,
    val text: String,
    val count: Int = 1,
    val appVersion: String = "",
    val recordedAtEpochMs: Long,
    val source: String = "on-device-agent",
)

/** The action-plane face of memory: write verified paths, read stale-caveated hints. */
interface LearningsStore {
    suspend fun hintsFor(appPackage: String, goalType: String): String

    /**
     * Goal-optional app hints: quirks + top paths across goals + recoveries for one
     * app. [installedAppVersion] (when known) lets the renderer flag paths recorded
     * on an older app version so the reading model can calibrate trust.
     */
    suspend fun appHints(appPackage: String, installedAppVersion: String? = null): String

    suspend fun recordVerifiedPath(
        appPackage: String,
        goalType: String,
        steps: List<String>,
        recoveries: List<String> = emptyList(),
        appVersion: String = "",
        source: String = "on-device-agent",
        goalLabel: String = "",
        endsAt: String = "",
    )

    suspend fun recordFacts(
        appPackage: String,
        kind: FactKind,
        texts: List<String>,
        appVersion: String = "",
        source: String = "on-device-agent",
    )
}

/**
 * Learnings keyed by `goalType__appPackage` (Reflexion-style app-scoping). Writes are success-only
 * (the caller -- LearningsWriteHook -- fires only on an allowed end_session) and re-scrubbed at the
 * boundary (defence in depth). An identical re-recorded path bumps successCount (success
 * reinforcement). Per-key cap keeps the store bounded. hintsFor carries the verify-on-read caveat in
 * the payload itself -- a hint can never override what the agent perceives.
 */
class EncryptedLearningsStore(
    private val store: EncryptedJsonStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : LearningsStore {

    override suspend fun recordVerifiedPath(
        appPackage: String,
        goalType: String,
        steps: List<String>,
        recoveries: List<String>,
        appVersion: String,
        source: String,
        goalLabel: String,
        endsAt: String,
    ) {
        val scrubbed = steps.map { PiiFirewall.scrub(it) }.filter { it.isNotBlank() }
        if (scrubbed.isEmpty()) return
        val scrubbedRecoveries = recoveries.map { PiiFirewall.scrub(it) }
            .filter { it.isNotBlank() }
            .take(MAX_RECOVERIES_PER_ENTRY)
        val safeGoalLabel = PiiFirewall.scrub(goalLabel).trim()
        val safeEndsAt = PiiFirewall.scrub(endsAt).trim()
        val key = key(appPackage, goalType)
        val existing = store.read(key, serializer<LearningEntry>())
        val idx = existing.indexOfFirst { it.steps == scrubbed }
        val merged = if (idx >= 0) {
            existing.toMutableList().also {
                val prev = it[idx]
                it[idx] = prev.copy(
                    successCount = prev.successCount + 1,
                    recordedAtEpochMs = clock(),
                    recoveries = (prev.recoveries + scrubbedRecoveries)
                        .distinct()
                        .take(MAX_RECOVERIES_PER_ENTRY),
                    // Re-verification refreshes provenance: latest teacher + version win.
                    appVersion = appVersion,
                    source = source,
                    goalLabel = safeGoalLabel.ifBlank { prev.goalLabel },
                    endsAt = safeEndsAt.ifBlank { prev.endsAt },
                )
            }
        } else {
            existing + LearningEntry(
                appPackage, goalType, scrubbed, clock(), 1, scrubbedRecoveries,
                appVersion, source, safeGoalLabel, safeEndsAt,
            )
        }
        val capped = merged.sortedByDescending { it.recordedAtEpochMs }.take(MAX_PER_KEY)
        store.write(key, capped, serializer<LearningEntry>())
    }

    override suspend fun recordFacts(
        appPackage: String,
        kind: FactKind,
        texts: List<String>,
        appVersion: String,
        source: String,
    ) {
        // Same M4 rule as paths: the unknown bucket pools unattributed apps —
        // a fact filed there could surface inside the wrong app. Skip entirely.
        if (appPackage == UNKNOWN_APP) return
        val scrubbed = texts.map { PiiFirewall.scrub(it) }.filter { it.isNotBlank() }
        if (scrubbed.isEmpty()) return
        val key = factsKey(appPackage)
        var merged = store.read(key, serializer<AppFactEntry>())
        scrubbed.forEach { text ->
            val idx = merged.indexOfFirst { it.kind == kind && it.text == text }
            merged = if (idx >= 0) {
                merged.toMutableList().also {
                    val prev = it[idx]
                    it[idx] = prev.copy(count = prev.count + 1, recordedAtEpochMs = clock(), appVersion = appVersion, source = source)
                }
            } else {
                merged + AppFactEntry(appPackage, kind, text, 1, appVersion, clock(), source)
            }
        }
        store.write(key, merged.sortedByDescending { it.recordedAtEpochMs }.take(MAX_FACTS_PER_APP), serializer<AppFactEntry>())
    }

    override suspend fun hintsFor(appPackage: String, goalType: String): String {
        // M4: the `unknown` bucket pools paths from unattributed apps — replaying one
        // app's path as a hint inside a different app is prompt pollution. Entries stay
        // readable in the Settings inspector; they are just never surfaced as hints.
        if (appPackage == UNKNOWN_APP) return ""
        val now = clock()
        val entries = store.read(key(appPackage, goalType), serializer<LearningEntry>())
            .filter { it.steps.size >= MIN_PATH_STEPS } // one-step paths teach nothing
            .filter(::isReplayable)
        if (entries.isEmpty()) return ""
        val lines = entries.sortedByDescending { it.successCount }.take(MAX_HINTS)
            .joinToString("\n") { renderPath(it, now, installedAppVersion = null) }
        // Recovery lessons: dead ends this app is known for, and what worked instead.
        val recoveryLines = entries.sortedByDescending { it.recordedAtEpochMs }
            .flatMap { it.recoveries }
            .filter(::namesOnlyRegisteredTools)
            .distinct()
            .take(MAX_RECOVERY_HINTS)
            .joinToString("\n") { "• Recovery that worked before: $it" }
        return "# Learned hints for $appPackage · $goalType " +
            "(MAY BE STALE — re-verify against the live screen; never override what you see)\n" +
            lines +
            (if (recoveryLines.isEmpty()) "" else "\n$recoveryLines")
    }

    override suspend fun appHints(appPackage: String, installedAppVersion: String?): String {
        if (appPackage == UNKNOWN_APP) return ""
        val now = clock()
        val paths = pathKeysFor(appPackage)
            .flatMap { store.read(it, serializer<LearningEntry>()) }
            .filter { it.steps.size >= MIN_PATH_STEPS } // one-step paths teach nothing
            .filter(::isReplayable)
            .sortedByDescending { it.successCount }
            .take(MAX_HINTS)
        val facts = store.read(factsKey(appPackage), serializer<AppFactEntry>())
            .filter { namesOnlyRegisteredTools(it.text) }
        val recoveries = facts.filter { it.kind == FactKind.RECOVERY }
            .sortedByDescending { it.count }.take(MAX_RECOVERY_HINTS)
        val quirks = facts.filter { it.kind == FactKind.QUIRK && it.count >= FACT_CONFIRMATIONS }
            .sortedByDescending { it.count }.take(MAX_RECOVERY_HINTS)
        val antis = facts.filter { it.kind == FactKind.ANTI_PATTERN && it.count >= FACT_CONFIRMATIONS }
            .sortedByDescending { it.count }.take(MAX_RECOVERY_HINTS)
        if (paths.isEmpty() && recoveries.isEmpty() && quirks.isEmpty() && antis.isEmpty()) return ""
        return buildString {
            append("# Learned hints for $appPackage ")
            append("(MAY BE STALE — re-verify against the live screen; never override what you see)\n")
            quirks.forEach { append("• Known quirk: ${it.text} (confirmed ${it.count}×, ${ageOf(now, it.recordedAtEpochMs)})\n") }
            paths.forEach { append(renderPath(it, now, installedAppVersion) + "\n") }
            recoveries.forEach { append("• Recovery that worked before: ${it.text} (seen ${it.count}×, ${ageOf(now, it.recordedAtEpochMs)})\n") }
            antis.forEach { append("• Known dead end (no workaround found): ${it.text} (${it.count} strikes)\n") }
        }.trimEnd()
    }

    /**
     * Learned paths outlive the tools that made them. A step naming a tool that is no longer
     * registered (tap_text and scroll_to_element were removed) would be injected as a hint for
     * a tool the inventory says does not exist — so drop the whole entry: a path with a hole
     * in it is not replayable anyway. Self-healing for any future tool removal; no migration.
     */
    private fun isReplayable(e: LearningEntry): Boolean =
        e.steps.all { it.substringBefore(' ').substringBefore('(') in McpToolScopes.toolScopeMap }

    /**
     * Same guard for FREE-TEXT lines (recoveries, quirks, anti-patterns), which name tools inside
     * a sentence: "tap_text \"Go\" led nowhere → tap som worked instead". These are the higher-risk
     * channel — a recovery reads as "do this instead", not as advisory navigation. Tool names are
     * the only snake_case tokens a nav label realistically carries, so an underscored token that
     * is not registered means the line teaches a tool that no longer exists; drop it.
     */
    private fun namesOnlyRegisteredTools(text: String): Boolean =
        SNAKE_TOKEN.findAll(text).all { it.value in McpToolScopes.toolScopeMap }

    /**
     * One path hint line. Freshness + app-version delta let the reading model
     * calibrate trust instead of relying on the blanket staleness caveat; the
     * goal label lets it judge relevance to ITS goal (semantic matching is
     * delegated to the reader — no on-device embedding needed); the destination
     * anchor lets it verify mid-replay that the path still leads where it did.
     */
    private fun renderPath(e: LearningEntry, nowMs: Long, installedAppVersion: String?): String {
        val goal = e.goalLabel.takeIf { it.isNotBlank() }?.let { " · goal was \"$it\"" } ?: ""
        val dest = e.endsAt.takeIf { it.isNotBlank() }?.let { " → ends at \"$it\"" } ?: ""
        val version = when {
            e.appVersion.isBlank() || installedAppVersion.isNullOrBlank() -> ""
            installedAppVersion == e.appVersion -> ""
            else -> ", recorded on app v${e.appVersion} but v$installedAppVersion is installed — layout may have changed"
        }
        return "• A path that worked before (${e.goalType}$goal): ${e.steps.joinToString(" → ")}$dest" +
            "  (succeeded ${e.successCount}×, verified ${ageOf(nowMs, e.recordedAtEpochMs)}$version)"
    }

    private fun ageOf(nowMs: Long, thenMs: Long): String {
        val days = ((nowMs - thenMs) / DAY_MS).coerceAtLeast(0)
        return if (days == 0L) "today" else "${days}d ago"
    }

    /** All learned entries across keys, for the Settings -> Memory view. */
    fun allEntries(): List<LearningEntry> =
        store.keys().filterNot { it.startsWith(FACTS_PREFIX) }
            .flatMap { store.read(it, serializer<LearningEntry>()) }

    /** One app's lessons, split into full paths and app facts. */
    data class AppLessons(val paths: List<LearningEntry>, val facts: List<AppFactEntry>)

    /** All lessons grouped by app — the Memory UI's per-app view. */
    fun lessonsByApp(): Map<String, AppLessons> {
        val paths = store.keys().filterNot { it.startsWith(FACTS_PREFIX) }
            .flatMap { store.read(it, serializer<LearningEntry>()) }
            .groupBy { it.appPackage }
        val facts = store.keys().filter { it.startsWith(FACTS_PREFIX) }
            .flatMap { store.read(it, serializer<AppFactEntry>()) }
            .groupBy { it.appPackage }
        return (paths.keys + facts.keys).associateWith { app ->
            AppLessons(paths[app].orEmpty(), facts[app].orEmpty())
        }
    }

    suspend fun deletePath(appPackage: String, goalType: String, steps: List<String>) {
        val key = key(appPackage, goalType)
        store.write(
            key,
            store.read(key, serializer<LearningEntry>()).filterNot { it.steps == steps },
            serializer<LearningEntry>(),
        )
    }

    suspend fun deleteFact(appPackage: String, kind: FactKind, text: String) {
        val key = factsKey(appPackage)
        store.write(
            key,
            store.read(key, serializer<AppFactEntry>()).filterNot { it.kind == kind && it.text == text },
            serializer<AppFactEntry>(),
        )
    }

    suspend fun forgetApp(appPackage: String) {
        // Writing an empty list is serializer-agnostic (empty JSON array).
        (pathKeysFor(appPackage) + factsKey(appPackage)).forEach {
            store.write(it, emptyList(), serializer<AppFactEntry>())
        }
    }

    private fun pathKeysFor(appPackage: String): List<String> =
        store.keys().filter { !it.startsWith(FACTS_PREFIX) && it.endsWith("__$appPackage") }

    private fun key(appPackage: String, goalType: String) = "${goalType}__$appPackage"

    private fun factsKey(appPackage: String) = "$FACTS_PREFIX$appPackage"

    companion object {
        const val MAX_PER_KEY = 10
        const val MAX_HINTS = 3
        const val MAX_RECOVERIES_PER_ENTRY = 5
        const val MAX_RECOVERY_HINTS = 3
        const val MAX_FACTS_PER_APP = 30
        const val FACT_CONFIRMATIONS = 2
        const val FACTS_PREFIX = "facts__"

        /** One-step "paths" (e.g. a bare launch_app) carry no navigation knowledge. */
        const val MIN_PATH_STEPS = 2

        /** Underscored identifiers — the shape of a tool name inside free-text hint prose. */
        private val SNAKE_TOKEN = Regex("""[a-z][a-z0-9]*(?:_[a-z0-9]+)+""")
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** The bucket [AppPackageResolver] returns when no app could be attributed. */
        const val UNKNOWN_APP = "unknown"
    }
}
