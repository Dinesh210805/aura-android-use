package com.aura.mcp.tools

import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.server.CompletionEvidence
import com.aura.mcp.server.LearningsDispatch
import com.aura.mcp.server.scopedTool
import com.aura.mcp.server.sinks
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 10B — `end_session`.
 *
 * Agents call this when they consider a unit of work complete: an order
 * placed, a navigation finished, a multi-step task wrapped up. The
 * server records the session boundary in the on-device session log so
 * the user can later open that exact session as a self-contained entry
 * (with all tool calls, args, outputs, and raw screenshots).
 *
 * **Soft semantics.** The MCP server keeps listening after this call —
 * the next tool dispatch begins a fresh session. Use this to chapter
 * your work, not to shut down the server.
 *
 * Scope: READ (it neither moves pixels nor changes settings).
 */
internal fun Server.registerEndSessionTool(bridge: DeviceBridge) {
    // GP1 — capture this server's forensic session sink at registration.
    val session = sinks().session
    // Spec 2026-07-17 — shared-memory session boundary rides the same tool.
    val learnings = sinks().learnings
    scopedTool(
        name = "end_session",
        description = """
            Finish the task. `reason` is your answer to the user — shown and spoken — so say specifically what you did or found.

            outcome: "success" only when the latest screen you saw confirms it; "partial" when you did some of it (say how much); "failure" when it did not work (say what happened). partial and failure are always accepted.

            For goal_type send_message, send_email, purchase or post, success is refused unless you looked at the screen after your last action.

            A verified success saves the route (structure only) as a hint for next time. For outside MCP clients this also closes the logged session; the server keeps listening.
            """.trimIndent(),
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "reason" to stringSchema(
                        "Short summary of what this session accomplished, e.g. " +
                            "'opened Spotify and started playlist Discover Weekly'. " +
                            "Default: 'agent-end'.",
                    ),
                    "outcome" to stringSchema(
                        "'success' if the goal state was verified, 'partial' for some of it, " +
                            "'failure' otherwise. Successful sessions teach this device: the " +
                            "path you took is saved (structure only, never typed content) and " +
                            "offered to future agents as a learned hint.",
                    ),
                    "goal_type" to stringSchema(
                        "Optional coarse goal bucket for the learned path: one of " +
                            "play_media|send_message|open_app|search|navigate|other.",
                    ),
                ),
            ),
        ),
    ) { request ->
        val reason = request.arguments?.stringArg("reason") ?: "agent-end"
        val outcome = request.arguments?.stringArg("outcome")
        val goalType = request.arguments?.stringArg("goal_type")

        // The success gate. Refuses ONLY an unverified success claim on a goal where being
        // wrong is expensive, and never an honest failure — see [CompletionEvidence] for the
        // eval finding this closes and why the description above was not enough on its own.
        //
        // Returns BEFORE session.endSession: a refused call is not the end of anything, and
        // closing the session here would leave the agent's next tool call opening a fresh one
        // mid-task, splitting one task across two log entries.
        CompletionEvidence.refusalFor(outcome, goalType)?.let { refusal ->
            return@scopedTool CallToolResult(
                content = listOf(TextContent(refusal)),
                isError = true,
            )
        }

        session.endSession(reason)
        // The next run must gather its own evidence. Without this, a look taken before this
        // boundary would still satisfy the gate for the next task whenever nothing moved the
        // screen in between.
        CompletionEvidence.reset()
        // Typing during the run suppressed the soft keyboard (SHOW_MODE_HIDDEN,
        // sticky device-global state). The session is over — hand the keyboard
        // back to the user NOW instead of waiting out the service's inactivity
        // watchdog. Contained: never load-bearing for the tool result.
        runCatching { bridge.restoreKeyboard() }
        // Shared memory — flush the session's learnings. Contained: memory is
        // never load-bearing, an error here must not fail end_session.
        runCatching {
            learnings.onSessionEnd(
                reason = reason,
                outcome = LearningsDispatch.parseOutcome(outcome),
                goalType = LearningsDispatch.parseGoalType(goalType),
            )
        }
        CallToolResult(
            content = listOf(
                TextContent(
                    buildJsonObject {
                        put("success", true)
                        put("reason", reason)
                        put("hint", "Session closed. Server still listening; next tool call opens a new session.")
                    }.toString(),
                ),
            ),
        )
    }
}
