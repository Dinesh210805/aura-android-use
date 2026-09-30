package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.agent.AgentStreamEvent

/**
 * The single seam between the conversation plane and the action plane (umbrella §3). The prod adapter
 * wraps AuraAgent.runFromSavedSettings, so a drive_phone call routes through the UNCHANGED hook chain
 * + SensitivePolicy -- the conversation plane gains no privilege. CompanionTools depends only on this
 * interface, so the companion core unit-tests against a fake with zero Koog/MCP/network.
 */
interface PhoneTaskRunner {
    suspend fun run(task: String, onProgress: (String) -> Unit): String

    /**
     * Resume the most recent interrupted run (Build 3), or return null when there is nothing
     * resumable. The prod adapter routes to `AuraAgent.resumeRun`, so a resumed run still passes
     * the unchanged hook chain + SensitivePolicy. Default no-op keeps other implementations thin.
     */
    suspend fun resumeLatest(onProgress: (String) -> Unit): String? = null
}

/** Pure mapping from an action-plane stream event to a short spoken-progress stage string. */
fun AgentStreamEvent.toProgress(): String = when (this) {
    is AgentStreamEvent.ToolStarting -> "running $tool"
    is AgentStreamEvent.ToolCompleted -> "did $tool"
    is AgentStreamEvent.ToolFailed -> "step failed: $tool"
    is AgentStreamEvent.Finished -> "done"
}

/**
 * Recover the tool name from a [toProgress]-formatted stage string, or null for
 * non-tool stages ("done", free-text status). The Live seam is String-typed on
 * purpose (CompanionTools unit-tests against fakes with zero Koog types), so
 * the notch-pill verb during a drive_phone run is derived by parsing this
 * format back — the two functions must stay in lockstep (TaskProgressTest
 * asserts the round-trip for every event type).
 */
fun progressToolName(progress: String): String? =
    listOf("running ", "did ", "step failed: ")
        .firstNotNullOfOrNull { prefix ->
            progress.removePrefix(prefix).takeIf { it != progress }
        }
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.contains(' ') }
