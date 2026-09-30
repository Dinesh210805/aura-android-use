package com.aura.aura_ui.agent.mcpbridge.telemetry

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Reports each on-device agent tool call as a Firebase Analytics `tool_call` event (tool name,
 * duration, success: never arguments or results) and a Crashlytics breadcrumb.
 *
 * - [shared] is the default every [com.aura.aura_ui.agent.mcpbridge.McpTool] uses.
 * - Change together: the event list in `telemetry/AuraAnalytics` and the privacy policy.
 */
class FirebaseToolTelemetrySink(
    private val analytics: FirebaseAnalytics = FirebaseAnalytics.getInstance(
        com.aura.aura_ui.AuraApplication.instance,
    ),
) : ToolTelemetrySink {

    override fun reportToolCall(toolName: String, durationMs: Long, success: Boolean) {
        analytics.logEvent(
            "tool_call",
            Bundle().apply {
                putString("tool_name", toolName)
                putLong("duration_ms", durationMs)
                putLong("success", if (success) 1L else 0L)
            },
        )
        FirebaseCrashlytics.getInstance().log(
            "tool: $toolName (${durationMs}ms, ${if (success) "ok" else "failed"})",
        )
    }

    companion object {
        val shared: FirebaseToolTelemetrySink by lazy { FirebaseToolTelemetrySink() }
    }
}
