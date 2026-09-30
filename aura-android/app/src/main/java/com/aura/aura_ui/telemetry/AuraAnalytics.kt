package com.aura.aura_ui.telemetry

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.google.firebase.analytics.FirebaseAnalytics
import java.util.concurrent.atomic.AtomicLong

/**
 * Every custom Firebase Analytics event AURA sends, in one place so the privacy policy can list
 * them exactly. Firebase adds its own automatic ones (`first_open`, which is the install date,
 * `session_start`, `app_update`, `screen_view`).
 *
 * | Event | Params |
 * |---|---|
 * | `aura_open` | `source`: how the assistant was opened (`app`, `wake_word`, `volume_keys`, `assist`, `notification`) |
 * | `agent_task` | `source` (`voice`, `text`, `external`), `outcome` (`done`, `failed`, `cancelled`), `steps`, `tool_failures`, `duration_band`, `provider`, `error_category` (failed only) |
 * | `mcp_session_start` | `pc_os` |
 * | `mcp_session_end` | `duration_band`, `tool_calls` |
 * | `mcp_paired` / `mcp_connect_denied` | none (denied: the user said no, or a proof or protocol check failed) |
 * | `onboarding_step` | `step` (0–9) |
 * | `onboarding_complete` | none |
 * | `tool_call` | sent by `agent/mcpbridge/telemetry/FirebaseToolTelemetrySink` |
 *
 * - Contract: never sends content. No task or reply text, tool arguments or results, app names,
 *   screen text, contacts, hostnames, IP addresses, or custom endpoint URLs; values are fixed
 *   labels, counts and [AnalyticsBuckets] bands.
 * - Sends nothing while diagnostics are off: `DiagnosticsStore` turns collection off, which makes
 *   `logEvent` a no-op.
 * - Fails: never throws.
 * - Change together: the "Anonymous diagnostics" list in `presentation/screens/legal/LegalCopy.kt`.
 */
object AuraAnalytics {

    private const val TAG = "AuraAnalytics"

    private val mcpSessionStartMs = AtomicLong(0L)
    private val mcpToolCalls = AtomicLong(0L)

    fun assistantOpened(context: Context, source: String) =
        log(context, "aura_open") { putString("source", source) }

    /**
     * One finished on-device agent run.
     *
     * @param failure the exception that ended it, only to pick an [AnalyticsBuckets.errorCategory]
     */
    fun agentTask(
        context: Context,
        source: String,
        outcome: String,
        steps: Int,
        toolFailures: Int,
        durationMs: Long,
        failure: Throwable? = null,
    ) = log(context, "agent_task") {
        putString("source", source)
        putString("outcome", outcome)
        putLong("steps", steps.toLong())
        putLong("tool_failures", toolFailures.toLong())
        putString("duration_band", AnalyticsBuckets.durationBand(durationMs))
        putString("provider", providerFamily(context))
        failure?.let { putString("error_category", AnalyticsBuckets.errorCategory(it.javaClass.name, it.message)) }
    }

    /** A PC's MCP session was approved (new or reconnecting). */
    fun mcpSessionStarted(platform: String?) {
        val context = appContext() ?: return
        mcpSessionStartMs.set(System.currentTimeMillis())
        mcpToolCalls.set(0L)
        log(context, "mcp_session_start") { putString("pc_os", AnalyticsBuckets.pcOs(platform)) }
    }

    /** Counts one tool call served to a PC; reported with [mcpSessionEnded]. */
    fun mcpToolCall() {
        mcpToolCalls.incrementAndGet()
    }

    fun mcpSessionEnded() {
        val context = appContext() ?: return
        val started = mcpSessionStartMs.getAndSet(0L)
        if (started == 0L) return
        log(context, "mcp_session_end") {
            putString("duration_band", AnalyticsBuckets.durationBand(System.currentTimeMillis() - started))
            putLong("tool_calls", mcpToolCalls.getAndSet(0L))
        }
    }

    /** A new PC was approved, or any PC's connection was denied. */
    fun mcpPairing(approved: Boolean) {
        val context = appContext() ?: return
        log(context, if (approved) "mcp_paired" else "mcp_connect_denied") {}
    }

    fun onboardingStep(context: Context, step: Int) =
        log(context, "onboarding_step") { putLong("step", step.toLong()) }

    fun onboardingComplete(context: Context) = log(context, "onboarding_complete") {}

    /** The application context, or null before `AuraApplication.onCreate` (and in JVM tests). */
    private fun appContext(): Context? = runCatching<Context> { com.aura.aura_ui.AuraApplication.instance }.getOrNull()

    private fun providerFamily(context: Context): String = runCatching {
        val id = ProviderKeyStore(context).getSelectedEndpointId()
        AnalyticsBuckets.providerFamily(id, LlmEndpointCatalog.builtinById(id) != null)
    }.getOrDefault("unknown")

    private inline fun log(context: Context, event: String, params: Bundle.() -> Unit) {
        try {
            FirebaseAnalytics.getInstance(context.applicationContext).logEvent(event, Bundle().apply(params))
        } catch (e: Exception) {
            Log.w(TAG, "Could not log $event: ${e.message}")
        }
    }
}
