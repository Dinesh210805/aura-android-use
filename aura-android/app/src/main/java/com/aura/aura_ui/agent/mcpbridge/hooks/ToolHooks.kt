package com.aura.aura_ui.agent.mcpbridge.hooks

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Pluggable replacement for the single hardcoded [com.aura.aura_ui.agent.mcpbridge.ActionGuard]
 * that used to be wired directly into [com.aura.aura_ui.agent.mcpbridge.McpTool]. A tool call now
 * passes through an ordered chain of [PreToolHook]s (which can block, confirm, or rewrite) and
 * [PostToolHook]s (observe / learn / verify).
 *
 * Adversarial rule: a hook can only make a call *safer*. A [PreToolDecision.Proceed] never
 * overrides another hook's [PreToolDecision.Deny]; metadata and hooks are advisory and never
 * grant authority. Blocked calls return an error tool-result the model re-plans from — never a
 * thrown exception — so the Koog agent loop continues.
 */
sealed interface PreToolDecision {
    /** No objection from this hook. */
    data object Proceed : PreToolDecision

    /** Run, but with these arguments instead. Multiple rewrites: the last one wins. */
    data class Rewrite(val newArgs: JsonObject) : PreToolDecision

    /** Run only if the user says yes to [reason] (bridged via [HookContext.confirm]). */
    data class Confirm(val reason: String) : PreToolDecision

    /** Do not run. [modelReason] is returned to the model as an error tool-result. */
    data class Deny(val modelReason: String) : PreToolDecision
}

/**
 * Per-run context handed to every hook. [confirm] bridges to the on-device human-in-the-loop
 * yes/no prompt; until the Settings/HITL sub-project wires it, callers pass `{ true }`.
 */
class HookContext(val confirm: suspend (String) -> Boolean)

/** Inspect/guard a tool call before dispatch. The first non-[PreToolDecision.Proceed] decision matters. */
interface PreToolHook {
    suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext): PreToolDecision
}

/** Observe a SUCCESSFUL tool call after dispatch. Never blocks; for learning/verification. */
interface PostToolHook {
    suspend fun onPostTool(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext)
}

/**
 * E6 — PostToolUseFailure lane. `isError` results (and thrown dispatches, converted
 * to error results by the chain) dispatch HERE instead of [PostToolHook], so
 * failure handling is first-class at the seam rather than an `isError` flag every
 * success-path hook must remember to check. A hook needing both lanes implements
 * both interfaces (it is registered once; the chain routes).
 */
interface PostToolFailureHook {
    suspend fun onPostToolFailure(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext)
}

/**
 * E6 — RunEnd lane. Fired exactly once at run teardown (any outcome: completed,
 * failed, cancelled, budget), after the last tool call, inside a NonCancellable
 * block. This is the structural home for end-of-run flushes — replacing the
 * "post-hook keyed on tool name == end_session" hack that alternate dispatch
 * sites silently broke (T5/GP10 class).
 */
interface RunEndHook {
    suspend fun onRunEnd(reason: String)
}
