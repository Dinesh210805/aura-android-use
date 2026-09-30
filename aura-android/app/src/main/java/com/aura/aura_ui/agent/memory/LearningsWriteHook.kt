package com.aura.aura_ui.agent.memory

import com.aura.aura_ui.agent.llm.AgentTraceTap
import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import com.aura.aura_ui.agent.mcpbridge.hooks.PostActionObservationReader
import com.aura.aura_ui.agent.mcpbridge.hooks.PostToolFailureHook
import com.aura.aura_ui.agent.mcpbridge.hooks.PostToolHook
import com.aura.aura_ui.agent.mcpbridge.hooks.RunEndHook
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Thin hook-chain adapter over [LearningsAccumulator] (the shared learnings brain —
 * spec 2026-07-17). Reaching [onPostTool] for `end_session` means ActionGuard did NOT
 * deny it (a Deny returns before dispatch/post-hooks) — i.e. faithful completion.
 * That structural fact is what marks the run verified; "success-only path writes"
 * needs no flag.
 *
 * **Learning from tool executions (not just outcomes):** a step that errored (M7) or
 * dispatched fine but changed nothing on screen (E2 `screen_changed=false`) is a
 * *dead end*. It never enters the verified path, but when the NEXT successful step
 * lands, the pair is captured as a recovery lesson. Recovery pairs persist at run end
 * even when the run was NOT verified — each pair carried its own mid-run evidence.
 *
 * **Attribution (M4):** the E2 observation bundle carries the true `foreground_app`
 * package per gesture, which beats sniffing tool args for app names.
 *
 * All throws are contained by ToolHookChain, so a memory failure never breaks a run.
 */
class LearningsWriteHook(
    goal: String,
    store: LearningsStore,
) : PostToolHook, PostToolFailureHook, RunEndHook {
    private val acc = LearningsAccumulator(store, goal = goal)

    override suspend fun onPostTool(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
        val observation = PostActionObservationReader.observation(result)
        acc.onStep(
            toolName = toolName,
            args = args,
            failed = false,
            screenChanged = PostActionObservationReader.screenChanged(observation),
            foregroundApp = PostActionObservationReader.foregroundApp(observation),
            topLabel = PostActionObservationReader.topLabel(observation),
        )
    }

    /**
     * E6 failure lane (M7): a failed step is not part of a verified path — but it
     * IS the first half of a recovery lesson if a later step succeeds where it
     * failed. An errored `end_session` lands here too and never verifies the run.
     */
    override suspend fun onPostToolFailure(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
        if (toolName == "end_session") return
        acc.onStep(toolName, args, failed = true, screenChanged = null, foregroundApp = null)
    }

    /**
     * E6 RunEnd lane: flush at the run boundary, inside the agent's NonCancellable
     * teardown, once per run. Paths write only when the run verified; recoveries
     * write regardless (see class doc).
     */
    override suspend fun onRunEnd(reason: String) {
        val summary = acc.flush()
        // Phase 2: report the memory write to the trace tap (verified paths only).
        summary?.let { AgentTraceTap.reportMemoryWrite(it.goalType, it.steps) }
    }
}
