package com.aura.aura_ui.agent.mcpbridge.hooks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * E6 — cheap dispatch-time argument sanity. Catches the arg shapes that are
 * CERTAIN to fail or hit a wrong target (negative coordinates, missing swipe
 * ends, absurd durations, empty text) before a round trip is wasted; anything
 * merely unusual proceeds. Deny reasons are model-readable so the next turn
 * self-corrects. Server-side staleness (P1) and policy gates are unaffected —
 * this hook only saves the wasted dispatch.
 */
class ArgSanityHook : PreToolHook {
    override suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext): PreToolDecision {
        fun num(name: String): Long? =
            args[name]?.jsonPrimitive?.contentOrNull?.trim()?.toDoubleOrNull()?.let {
                if (it.isFinite()) it.toLong() else null
            }

        fun missingOrNegative(vararg names: String): String? =
            names.firstOrNull { (num(it) ?: -1L) < 0L }

        return when (toolName) {
            "tap", "double_tap", "long_press" -> {
                val hasSom = !args["som_id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                val badCoord = missingOrNegative("x", "y")
                when {
                    !hasSom && badCoord != null -> PreToolDecision.Deny(
                        "$toolName needs a som_id from the latest screen.",
                    )
                    toolName == "long_press" && (num("duration_ms") ?: 0L) > MAX_DURATION_MS ->
                        PreToolDecision.Deny("long_press duration_ms too large (max $MAX_DURATION_MS).")
                    else -> PreToolDecision.Proceed
                }
            }
            "swipe" -> {
                val bySom = !args["som_id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() &&
                    !args["direction"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                if (bySom || missingOrNegative("x1", "y1", "x2", "y2") == null) {
                    PreToolDecision.Proceed
                } else {
                    PreToolDecision.Deny("swipe needs a som_id and a direction.")
                }
            }
            "type_text" ->
                if (args["text"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                    PreToolDecision.Deny("type_text needs the text to type.")
                } else {
                    PreToolDecision.Proceed
                }
            else -> PreToolDecision.Proceed
        }
    }

    private companion object {
        const val MAX_DURATION_MS = 60_000L
    }
}
