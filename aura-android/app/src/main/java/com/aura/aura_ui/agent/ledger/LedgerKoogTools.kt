package com.aura.aura_ui.agent.ledger

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import com.aura.aura_ui.agent.mcpbridge.client.CallToolResultKoogTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult

/**
 * Koog-tool glue exposing the run ledger's plan checklist to the agent via `set_plan` /
 * `mark_step`. Client-side only and pure bookkeeping — these tools never reach the MCP server
 * (external MCP clients must not touch the agent's run ledger) and can never grant capability.
 * The unit-tested logic lives in [LedgerPlanTools]; this file is thin, compile-verified
 * adaptation, mirroring SkillKoogTools.
 */

@OptIn(InternalAgentsApi::class)
internal class SetPlanKoogTool(private val tools: LedgerPlanTools) : CallToolResultKoogTool(
    ToolDescriptor(
        name = "set_plan",
        description = "Record your step-by-step plan in the RUN LEDGER. Call it together with your " +
            "first action for a goal with several steps; skip it for a single action. Call again " +
            "with a new list to re-plan.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "steps",
                "Ordered step descriptions, one short line each (max ${LedgerPlanTools.MAX_PLAN_STEPS}).",
                ToolParameterType.List(ToolParameterType.String),
            ),
            ToolParameterDescriptor(
                "deliverable",
                "What the user ends up with, in one line — \"a summary of 10 Reddit posts about " +
                    "the Nord 4\", \"the message sent to Amma\". Shown back to you every turn so " +
                    "a long run stays pointed at what was actually asked. If the request names a " +
                    "number of things, set target_count too.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "target_count",
                "How many separate things the user asked you to look at or do (10 for \"check 10 " +
                    "posts\"). Omit when the task has no count. You must prove this many with " +
                    "record_finding before you may claim success, so set it from the REQUEST, " +
                    "not from how many you expect to manage.",
                ToolParameterType.Integer,
            ),
        ),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult =
        tools.setPlan(args.toKotlinxJsonObject())
}

@OptIn(InternalAgentsApi::class)
internal class MarkStepKoogTool(private val tools: LedgerPlanTools) : CallToolResultKoogTool(
    ToolDescriptor(
        name = "mark_step",
        description = "Mark a plan step done, in_progress, skipped or failed. done is checked " +
            "against the screen you are on now, so claim it only once the result is visible, and " +
            "give evidence. failed gives the step up (put why in note) so you can move on. note " +
            "records a fact worth keeping.",
        requiredParameters = listOf(
            ToolParameterDescriptor("index", "0-based step number from set_plan.", ToolParameterType.Integer),
            ToolParameterDescriptor("status", "done | in_progress | skipped | failed", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "evidence",
                "Required for done: what on the current screen shows the step happened " +
                    "(e.g. \"Pause button shows, 'Believer' playing\").",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor("note", "Short fact worth remembering for later steps; for failed, why.", ToolParameterType.String),
        ),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult =
        tools.markStep(args.toKotlinxJsonObject())
}

/**
 * Attaches the two ledger plan tools to a run's registry (always on — the ledger always exists).
 * Pre-task research is not here: the harness runs it at run start (see TaskResearch).
 */
@OptIn(InternalAgentsApi::class)
internal fun attachLedgerTools(
    controller: RunLedgerController,
    baseRegistry: ToolRegistry,
    checkStep: (suspend (RunLedger, Int, String) -> StepVerdict)? = null,
): ToolRegistry {
    val tools = LedgerPlanTools(controller, checkStep)
    return baseRegistry + ToolRegistry {
        tool(SetPlanKoogTool(tools))
        tool(MarkStepKoogTool(tools))
    }
}
