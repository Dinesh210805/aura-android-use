package com.aura.aura_ui.agent

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import com.aura.aura_ui.agent.mcpbridge.client.CallToolResultKoogTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deferred tool loading (A1, 2026-09-14). Every tool stays in the run's registry, so any call
 * executes; but a request carries full schemas only for [CORE] plus what the model has loaded.
 * The rest ride the system prompt as one line each (name + hint) — the model picks by name, then
 * `load_tools` puts the schema in the next request. Tool schemas were ~7.3k tokens on every call.
 *
 * Sent names only grow, in load order, so the request's tools field changes by appending — the
 * prefix Gemini's implicit cache matches on survives a load.
 */
class DeferredTools(registered: Collection<String>) {

    private val registered = registered.toSet()

    // Guarded by `this`: load_tools runs on the agent's coroutine, the event handler may not.
    private val sent = LinkedHashSet(
        (CORE + if ("web_search" in this.registered) emptyList() else RESEARCH_FALLBACK)
            .filter { it in this.registered },
    )

    /** Names still behind the menu, in registry order. */
    fun onDemand(): List<String> = synchronized(this) { registered.filter { it !in sent } }

    fun isRegistered(name: String) = name in registered

    /** Adds [names] to every later request; returns the ones that exist and were not already sent. */
    fun load(names: Collection<String>): List<String> = synchronized(this) {
        names.filter { it in registered && sent.add(it) }
    }

    /** Koog hands over the whole registry; keep only what has been sent, in send order. */
    fun sendable(tools: List<ToolDescriptor>): List<ToolDescriptor> {
        val byName = tools.associateBy { it.name }
        return synchronized(this) { sent.mapNotNull { byName[it] } }
    }

    companion object {
        /**
         * What almost every run touches: look, act, finish, and the client tools that steer the
         * run — plus the deterministic routes, whose best runs are 2 calls and would pay a third
         * for a load (~900 tokens here vs a whole extra request). Everything else — browser,
         * perception extras, files, memory — is a menu line. A name the run lacks is ignored.
         */
        val CORE = listOf(
            "read_screen", "tap", "type_text", "press_enter", "press_back", "press_home",
            "scroll_up", "scroll_down", "swipe", "launch_app", "end_session",
            "system_intent", "media_control", "read_notifications", "resolve_contact", "web_search",
            // record_finding is core rather than a menu line on purpose: proof is recorded the
            // moment each item is read, and a tool load standing between reading and recording is
            // exactly the friction that ends with the harvest summarized from memory instead.
            "ask_user", "set_plan", "mark_step", "record_finding", "use_skill", LoadToolsKoogTool.NAME,
            // Doctrine's when_stuck names it: being stuck must not first cost a load.
            "look_up",
        )

        /**
         * No Tavily key → no `web_search`, and Doctrine's `research_via_browser` tells the model to
         * look things up with these two before set_plan. The research step must not cost a load.
         */
        val RESEARCH_FALLBACK = listOf("browser_open", "browser_read")
    }
}

/** The one place the tools field is cut: a stock executor that sends [DeferredTools.sendable] only. */
internal class DeferredToolsExecutor(
    private val deferred: DeferredTools,
    client: Pair<LLMProvider, LLMClient>,
) : MultiLLMPromptExecutor(client) {

    // The on-device check that the cut reaches the wire: logcat tag AuraAgent should read ~30 of ~60.
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
        super.execute(prompt, model, deferred.sendable(tools).also {
            android.util.Log.i("AuraAgent", "Tool schemas sent: ${it.size} of ${tools.size}")
        })

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
        super.executeStreaming(prompt, model, deferred.sendable(tools))

    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
        super.executeMultipleChoices(prompt, model, deferred.sendable(tools))
}

@OptIn(InternalAgentsApi::class)
internal class LoadToolsKoogTool(private val deferred: DeferredTools) : CallToolResultKoogTool(
    ToolDescriptor(
        name = NAME,
        description = "Load tools from the 'Load first' list so you can call them. Pass every name " +
            "this task needs in one call; they are callable from your next turn.",
        requiredParameters = listOf(
            ToolParameterDescriptor("names", "Tool names to load.", ToolParameterType.List(ToolParameterType.String)),
        ),
        optionalParameters = emptyList(),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult {
        // Weak models send "a, b" as often as ["a","b"]; both mean the same thing.
        val names = when (val raw = args.toKotlinxJsonObject()["names"]) {
            is JsonArray -> raw.mapNotNull { it.jsonPrimitive.contentOrNull }
            null -> emptyList()
            else -> raw.jsonPrimitive.contentOrNull.orEmpty().split(',')
        }.map { it.trim() }.filter { it.isNotEmpty() }
        val loaded = deferred.load(names)
        val unknown = names.filterNot { deferred.isRegistered(it) }
        val already = names - loaded.toSet() - unknown.toSet()
        val text = buildString {
            if (loaded.isNotEmpty()) append("Loaded: ${loaded.joinToString()}. Call them now.")
            // Said plainly so a model does not re-load in a loop waiting for a different answer.
            if (already.isNotEmpty()) append(" Already loaded — call directly, do not load again: ${already.joinToString()}.")
            if (unknown.isNotEmpty()) append(" Not a tool: ${unknown.joinToString()}.")
            if (names.isEmpty()) append("Pass the tool names to load.")
        }.trim()
        return CallToolResult(content = listOf(TextContent(text)), isError = loaded.isEmpty() && unknown.isNotEmpty())
    }

    companion object {
        const val NAME = "load_tools"
    }
}
