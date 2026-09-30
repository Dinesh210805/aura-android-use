package com.aura.mcp.bridge

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Two-way shared-memory seam (spec 2026-07-17). Implemented by the host app over
 * its encrypted learnings store; NoOp when absent (tests, degraded boot) — the
 * server then behaves exactly as before this seam existed.
 *
 * Trust posture: the server streams its own dispatch evidence into the gateway —
 * verified paths and recovery lessons are derived from what the server OBSERVED
 * (post_action_observation, error results), never from client claims. The only
 * client-supplied signals are `end_session`'s explicit outcome/goal_type args,
 * which gate whether a path may be persisted at all.
 */
interface LearningsGateway {

    /** Streamed from tool dispatch — every executed (non-blocked) tool call. */
    fun onToolExecuted(
        toolName: String,
        args: JsonObject?,
        result: CallToolResult,
        failed: Boolean,
        clientLabel: String?,
    )

    /**
     * Session boundary (`end_session`). [outcome] is "success"/"failure"/null
     * (absent); only an explicit "success" may persist a verified path —
     * recovery lessons persist regardless.
     */
    suspend fun onSessionEnd(reason: String, outcome: String?, goalType: String?)

    /** App-scoped learned-hint block ("" when nothing learned). */
    suspend fun hintsFor(appPackage: String): String
}

object NoOpLearningsGateway : LearningsGateway {
    override fun onToolExecuted(
        toolName: String,
        args: JsonObject?,
        result: CallToolResult,
        failed: Boolean,
        clientLabel: String?,
    ) = Unit

    override suspend fun onSessionEnd(reason: String, outcome: String?, goalType: String?) = Unit

    override suspend fun hintsFor(appPackage: String): String = ""
}
