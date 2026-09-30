package com.aura.aura_ui.agent.mcpbridge.hooks

import android.util.Log
import com.aura.aura_ui.agent.llm.AgentTraceTap
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * The ordered set of hooks applied at the single tool-call chokepoint
 * ([com.aura.aura_ui.agent.mcpbridge.McpTool.execute]). One instance is shared across every tool
 * in a run's registry, so cross-tool invariants (perceive-before-act, loop detection) hold for the
 * whole tool set.
 *
 * Pure and Android-light enough to unit-test without a real MCP client: [runGatedToolCall] takes a
 * [dispatch] lambda, so tests fake the dispatch and assert the gating behaviour directly.
 */
class ToolHookChain(
    private val pre: List<PreToolHook>,
    private val post: List<PostToolHook>,
    /** E6 — failure lane: `isError` results dispatch here instead of [post]. */
    private val failure: List<PostToolFailureHook> = emptyList(),
) {
    /**
     * Resolve all [pre] decisions, then apply them in fixed precedence
     * **Deny > Confirm > Rewrite > Proceed** (deterministic, order-independent for Deny/Confirm).
     * A pre-hook that throws is contained (logged, treated as [PreToolDecision.Proceed]) so one bad
     * hook can never crash a tool call.
     *
     * Each pre-hook is evaluated **exactly once**: decisions are captured paired with the hook that
     * made them, and trace attribution reads the pair — never re-executes the hook (stateful hooks
     * like ActionGuard mutate counters on evaluation, so re-execution would double-count).
     *
     * A dispatch that throws is converted to a model-readable error result and every [post] hook
     * still runs on it — this component's contract is "blocked/failed calls return error results,
     * never a thrown exception" (cancellation is the one exception: it propagates untouched).
     * Throwing post-hooks are likewise contained.
     */
    suspend fun runGatedToolCall(
        toolName: String,
        args: JsonObject,
        ctx: HookContext,
        dispatch: suspend (JsonObject) -> CallToolResult,
    ): CallToolResult {
        // Every verdict is traced, Proceed included: "the guard looked and let it through" is
        // information, and a log that only shows refusals cannot show why something WASN'T refused.
        val decisions: List<Pair<PreToolHook, PreToolDecision>> = pre.map { hook ->
            val started = System.currentTimeMillis()
            val decision = try {
                hook.onPreTool(toolName, args, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "pre-hook threw for '$toolName', treating as Proceed: ${t.message}")
                step(toolName, "pre-hook", hook, "threw", "treated as proceed: ${t.message}", started)
                return@map hook to PreToolDecision.Proceed
            }
            when (decision) {
                PreToolDecision.Proceed -> step(toolName, "pre-hook", hook, "proceed", null, started)
                is PreToolDecision.Deny -> step(toolName, "pre-hook", hook, "deny", decision.modelReason, started)
                is PreToolDecision.Confirm -> step(toolName, "pre-hook", hook, "confirm", decision.reason, started)
                is PreToolDecision.Rewrite -> step(toolName, "pre-hook", hook, "rewrite", decision.newArgs.toString(), started)
            }
            hook to decision
        }

        decisions.firstOrNull { it.second is PreToolDecision.Deny }?.let { (_, decision) ->
            return errorResult((decision as PreToolDecision.Deny).modelReason)
        }
        val confirms = decisions.filter { it.second is PreToolDecision.Confirm }
        if (confirms.isNotEmpty()) {
            // GP14 — reasons COMPOSE: every hook's reason is surfaced in one ask,
            // never first-wins (a silently dropped second reason is a dropped
            // safety signal). One ask covers them all; declining denies with all.
            val joined = confirms.joinToString("; ") { (it.second as PreToolDecision.Confirm).reason }
            val asked = System.currentTimeMillis()
            val yes = ctx.confirm(joined)
            AgentTraceTap.reportStep(
                toolName, "confirm", "User", if (yes) "yes" else "no", joined, System.currentTimeMillis() - asked,
            )
            if (!yes) return errorResult("User declined: $joined")
        }
        val rewritePair = decisions.lastOrNull { it.second is PreToolDecision.Rewrite }
        val effectiveArgs = (rewritePair?.second as? PreToolDecision.Rewrite)?.newArgs ?: args

        val result = try {
            dispatch(effectiveArgs)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "dispatch threw for '$toolName', converting to error result: ${t.message}")
            errorResult("Tool '$toolName' failed: ${t.message ?: t::class.simpleName.orEmpty()}")
        }

        // E6 — lane routing: failures never ride the success path.
        if (result.isError == true) {
            failure.forEach { hook ->
                val started = System.currentTimeMillis()
                try {
                    hook.onPostToolFailure(toolName, effectiveArgs, result, ctx)
                    step(toolName, "failure-hook", hook, "ran", null, started)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "failure-hook threw for '$toolName' (ignored): ${t.message}")
                    step(toolName, "failure-hook", hook, "threw", t.message, started)
                }
            }
        } else {
            post.forEach { hook ->
                val started = System.currentTimeMillis()
                try {
                    hook.onPostTool(toolName, effectiveArgs, result, ctx)
                    step(toolName, "post-hook", hook, "ran", null, started)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "post-hook threw for '$toolName' (ignored): ${t.message}")
                    step(toolName, "post-hook", hook, "threw", t.message, started)
                }
            }
        }
        return result
    }

    private fun step(toolName: String, stage: String, hook: Any, verdict: String, detail: String?, startedMs: Long) =
        AgentTraceTap.reportStep(
            toolName, stage, hook::class.simpleName.orEmpty(), verdict, detail, System.currentTimeMillis() - startedMs,
        )

    private fun errorResult(text: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(text)), isError = true)

    private companion object {
        const val TAG = "ToolHookChain"
    }
}
