package com.aura.aura_ui.agent.memory

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
 * Koog-tool glue exposing the user's memory to the agent, mirroring `LedgerKoogTools`. Client-side
 * only — these tools never reach the MCP server, so nothing outside this app can read or rewrite
 * what AURA remembers about its user. The unit-tested logic lives in [MemoryAgentTools].
 *
 * Descriptors are kept deliberately tight. Every tool description is paid for on every turn, and
 * `recall_memory` in particular has to say "only for what is NOT already in the remembered block"
 * or a model will call it reflexively at the top of every run.
 */

@OptIn(InternalAgentsApi::class)
internal class RecallMemoryKoogTool(private val tools: MemoryAgentTools) : CallToolResultKoogTool(
    ToolDescriptor(
        name = "recall_memory",
        description = "Look up something the user told you EARLIER that is not already in the " +
            "remembered-context block — a past conversation, a task outcome, an older preference. " +
            "Returns ids you can pass to forget_memory or save_memory(supersedes).",
        requiredParameters = listOf(
            ToolParameterDescriptor("query", "A few keywords, e.g. \"food preference\".", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "limit",
                "How many to return (default ${MemoryAgentTools.DEFAULT_RECALL}).",
                ToolParameterType.Integer,
            ),
        ),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult = tools.recall(args.toKotlinxJsonObject())
}

@OptIn(InternalAgentsApi::class)
internal class SaveMemoryKoogTool(private val tools: MemoryAgentTools) : CallToolResultKoogTool(
    ToolDescriptor(
        name = "save_memory",
        description = "Remember one durable fact or preference the user just stated about " +
            "themselves. This is also how you CORRECT a memory: pass 'subject' (or 'supersedes') " +
            "and the new fact REPLACES the old one instead of piling up beside it. Do not save " +
            "task steps, screen contents, or anything they did not say about themselves. Never " +
            "save their name or the languages they speak - those live in Settings and you " +
            "already have them above.",
        requiredParameters = listOf(
            ToolParameterDescriptor("text", "The fact, one short sentence.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "type",
                "user (a fact about them, default) | feedback (how they want you to behave) | style (how they speak).",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                "subject",
                "The slot this fact occupies — \"diet\", \"home city\", \"employer\". ALWAYS set it: a " +
                    "later fact with the same subject replaces this one, which is how you keep " +
                    "contradictions from accumulating.",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                "supersedes",
                "Id from recall_memory that this fact replaces outright.",
                ToolParameterType.String,
            ),
        ),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult = tools.save(args.toKotlinxJsonObject())
}

@OptIn(InternalAgentsApi::class)
internal class ForgetMemoryKoogTool(private val tools: MemoryAgentTools) : CallToolResultKoogTool(
    ToolDescriptor(
        name = "forget_memory",
        description = "Delete one remembered fact, by id from recall_memory. Use only when the " +
            "user asks you to forget something, or says a memory is simply wrong (to correct a " +
            "fact that merely changed, use save_memory instead).",
        requiredParameters = listOf(
            ToolParameterDescriptor("id", "Memory id from recall_memory.", ToolParameterType.String),
        ),
        optionalParameters = emptyList(),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult = tools.forget(args.toKotlinxJsonObject())
}

@OptIn(InternalAgentsApi::class)
internal class SetReminderKoogTool(private val tools: MemoryAgentTools) : CallToolResultKoogTool(
    ToolDescriptor(
        name = "set_reminder",
        description = "Remind the user later with a notification. Give in_minutes, or at (plus " +
            "days_from_now for another day). Tell them the time the result confirms.",
        requiredParameters = listOf(
            ToolParameterDescriptor("text", "What to remind them about, short.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("in_minutes", "Minutes from now.", ToolParameterType.Integer),
            ToolParameterDescriptor("at", "Local time, 24-hour HH:mm.", ToolParameterType.String),
            ToolParameterDescriptor("days_from_now", "With at: 1 = tomorrow. Default 0.", ToolParameterType.Integer),
            ToolParameterDescriptor("repeat_daily", "true for a routine: rings every day at that time.", ToolParameterType.Boolean),
        ),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult = tools.remind(args.toKotlinxJsonObject())
}

/** Attaches the memory tools (and reminders, which live in the same store) to a run's registry. */
@OptIn(InternalAgentsApi::class)
internal fun attachMemoryTools(memory: EncryptedMemoryService, baseRegistry: ToolRegistry): ToolRegistry {
    val tools = MemoryAgentTools(memory)
    return baseRegistry + ToolRegistry {
        tool(RecallMemoryKoogTool(tools))
        tool(SaveMemoryKoogTool(tools))
        tool(ForgetMemoryKoogTool(tools))
        tool(SetReminderKoogTool(tools))
    }
}
