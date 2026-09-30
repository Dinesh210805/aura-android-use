package com.aura.aura_ui.agent.memory

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Transport-agnostic learnings brain (spec 2026-07-17): accumulates a PII-scrubbed
 * structural step trail, detects dead ends (tool error, or dispatched-but-screen-
 * unchanged) and pairs them with the next landing step as recovery lessons.
 *
 * Two-tier flush:
 *  - Verified PATHS only when [markVerified] was called (a policy-allowed successful
 *    end_session — structural success, never the model's self-assessment).
 *  - RECOVERY pairs always — each was individually evidence-verified mid-run (the
 *    working half landed with `screen_changed=true`), so they survive failed runs.
 *
 * Fed by two adapters: [LearningsWriteHook] (on-device agent hook chain) and the MCP
 * server's LearningsGateway implementation. Not thread-safe by design — each adapter
 * owns one instance and calls it from a single sequential dispatch path.
 */
class LearningsAccumulator(
    private val store: LearningsStore,
    private val goal: String? = null,
    private val defaultSource: String = "on-device-agent",
    private val appVersionOf: (String) -> String = { "" },
    private val maxSteps: Int = MAX_STEPS,
) {
    /** What a verified flush wrote — for trace taps. Null when no path was written. */
    data class FlushSummary(val goalType: String, val steps: List<String>)

    private val trail = mutableListOf<String>()
    private val recoveries = mutableListOf<String>()
    private val observedApps = mutableListOf<String>()
    private var pendingDeadEnd: String? = null
    private var verified = false
    private var source: String = defaultSource
    private var lastLandingLabel: String? = null

    fun onStep(
        toolName: String,
        args: JsonObject,
        failed: Boolean,
        screenChanged: Boolean?,
        foregroundApp: String?,
        sourceLabel: String? = null,
        topLabel: String? = null,
    ) {
        sourceLabel?.takeIf { it.isNotBlank() }?.let { source = it }
        APP_ARG_NAMES.forEach { key ->
            args[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { observedApps.add(it) }
        }
        foregroundApp?.takeIf { it.isNotBlank() }?.let { observedApps.add(it) }

        if (toolName == "end_session") {
            // Agent lane: reaching post-hooks for end_session means ActionGuard
            // allowed it — structural verification. (The MCP lane never streams
            // end_session here; it verifies via the explicit outcome arg.)
            if (!failed) verified = true
            return
        }

        val step = PathStepSanitizer.sanitize(toolName, args) ?: return

        // A failed dispatch, or one that dispatched fine but changed nothing on
        // screen, is a dead end: kept out of the path, remembered as the first
        // half of a potential recovery lesson.
        if (failed || screenChanged == false) {
            pendingDeadEnd = step
            return
        }
        pendingDeadEnd?.let { deadEnd ->
            if (recoveries.size < MAX_RECOVERIES) recoveries.add("$deadEnd led nowhere → $step worked instead")
            pendingDeadEnd = null
        }
        trail.add(step)
        if (trail.size > maxSteps) trail.removeAt(0)
        // The landed screen's headline label — the LAST one becomes the path's
        // destination anchor ("ends at") when the path is flushed.
        topLabel?.takeIf { it.isNotBlank() }?.let { lastLandingLabel = it }
    }

    fun markVerified() {
        verified = true
    }

    /**
     * Session-end flush; resets all state. Returns what was written as a verified
     * path (null when only recoveries — or nothing — were persisted).
     */
    suspend fun flush(reasonText: String? = null, explicitGoalType: String? = null): FlushSummary? {
        val appPackage = AppPackageResolver.resolve(goal ?: reasonText.orEmpty(), observedApps)
        val appVersion = runCatching { appVersionOf(appPackage) }.getOrDefault("")
        var summary: FlushSummary? = null
        if (recoveries.isNotEmpty()) {
            store.recordFacts(appPackage, FactKind.RECOVERY, recoveries.toList(), appVersion, source)
        }
        // MIN_PATH_STEPS: a one-step "path" (a bare launch_app) teaches nothing —
        // the reader already knows how to launch an app. Recoveries above are exempt.
        if (verified && trail.size >= EncryptedLearningsStore.MIN_PATH_STEPS) {
            val goalType = explicitGoalType ?: GoalClassifier.classify(goal ?: reasonText.orEmpty())
            store.recordVerifiedPath(
                appPackage = appPackage,
                goalType = goalType,
                steps = trail.toList(),
                recoveries = emptyList(),
                appVersion = appVersion,
                source = source,
                goalLabel = PathStepSanitizer.neutralizeLabel(goal ?: reasonText).orEmpty(),
                endsAt = PathStepSanitizer.neutralizeLabel(lastLandingLabel).orEmpty(),
            )
            summary = FlushSummary(goalType, trail.toList())
        }
        trail.clear()
        recoveries.clear()
        observedApps.clear()
        pendingDeadEnd = null
        verified = false
        source = defaultSource
        lastLandingLabel = null
        return summary
    }

    private companion object {
        val APP_ARG_NAMES = listOf("package_name", "app_name", "app", "package")
        const val MAX_RECOVERIES = 5
        const val MAX_STEPS = 40
    }
}
