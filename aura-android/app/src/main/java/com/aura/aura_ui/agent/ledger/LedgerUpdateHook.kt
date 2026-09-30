package com.aura.aura_ui.agent.ledger

import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import com.aura.aura_ui.agent.mcpbridge.hooks.PostActionObservationReader
import com.aura.aura_ui.agent.mcpbridge.hooks.PostToolFailureHook
import com.aura.aura_ui.agent.mcpbridge.hooks.PostToolHook
import com.aura.aura_ui.agent.memory.PathStepSanitizer
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Feeds the run ledger from the tool-call stream: every actionable step lands as a
 * [StepRecord] (with the E2 `screen_changed` signal when present), and definite dead ends
 * (errored steps, silent no-effect writes) land as constraints the model sees every turn.
 * Read-only/grounding calls and `end_session` contribute nothing. All throws are contained
 * by ToolHookChain — a ledger failure never breaks a run.
 */
class LedgerUpdateHook(
    private val controller: RunLedgerController,
) : PostToolHook, PostToolFailureHook {
    /** Success lane (E6): the step landed; a silent no-effect write is still a dead end. */
    override suspend fun onPostTool(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
        if (toolName == "end_session") return
        // Same read-only classification as the learnings trail: null = grounding call, not a step.
        val descriptor = PathStepSanitizer.sanitize(toolName, args) ?: return

        val observation = PostActionObservationReader.observation(result)
        val screenChanged = PostActionObservationReader.screenChanged(observation)

        controller.recordToolStep(
            tool = toolName,
            label = PathStepSanitizer.labelOf(toolName, args),
            ok = true,
            screenChanged = screenChanged,
        )
        if (screenChanged == false) {
            controller.recordDeadEnd("$descriptor — no effect (screen unchanged)")
        }
    }

    /** Failure lane (E6): errored steps land as constraints the model sees every turn. */
    override suspend fun onPostToolFailure(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
        if (toolName == "end_session") return
        val descriptor = PathStepSanitizer.sanitize(toolName, args) ?: return
        controller.recordToolStep(
            tool = toolName,
            label = PathStepSanitizer.labelOf(toolName, args),
            ok = false,
            screenChanged = null,
        )
        controller.recordDeadEnd("$descriptor — failed")
    }
}
