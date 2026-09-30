package com.aura.aura_ui.agent.hitl

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
 * Koog-tool glue exposing HITL to the agent via `ask_user`. Client-side only —
 * the question renders in AURA's own overlay (option chips + free-text) and,
 * on the Live voice plane, is spoken to the user; it never reaches the MCP
 * server and can never grant capability. Unit-tested logic lives in
 * [AskUserTools]; this file is thin adaptation, mirroring LedgerKoogTools.
 */
@OptIn(InternalAgentsApi::class)
internal class AskUserKoogTool(private val tools: AskUserTools) : CallToolResultKoogTool(
    ToolDescriptor(
        name = "ask_user",
        description = "Ask the user one short question and wait. It is shown and spoken; they " +
            "can tap an option, or say or type an answer. Give 2 to 4 short options whenever you " +
            "can — the likeliest answers even for open questions; their own words still win. " +
            "They may not answer; the result says so.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "question",
                "One short, specific question (e.g. 'Which storage size?').",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "options",
                "Up to ${AskUserTools.MAX_OPTIONS} short tap-to-answer choices " +
                    "(e.g. [\"128 GB\", \"256 GB\", \"512 GB\"]).",
                ToolParameterType.List(ToolParameterType.String),
            ),
        ),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult =
        tools.askUser(args.toKotlinxJsonObject())
}

/** Attaches the ask_user tool to a run's registry (always on — HITL is core). */
@OptIn(InternalAgentsApi::class)
internal fun attachAskUserTool(broker: AskUserBroker, baseRegistry: ToolRegistry): ToolRegistry =
    baseRegistry + ToolRegistry { tool(AskUserKoogTool(AskUserTools(broker))) }
