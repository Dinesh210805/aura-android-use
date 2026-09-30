package com.aura.aura_ui.agent.mcpbridge

import ai.koog.agents.core.tools.ToolRegistry
import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import com.aura.aura_ui.agent.mcpbridge.hooks.PostToolHook
import com.aura.aura_ui.agent.mcpbridge.hooks.PreToolHook
import com.aura.aura_ui.agent.mcpbridge.hooks.ToolHookChain
import com.aura.aura_ui.agent.mcpbridge.telemetry.FirebaseToolTelemetrySink
import com.aura.aura_ui.agent.mcpbridge.telemetry.ToolTelemetrySink
import io.modelcontextprotocol.kotlin.sdk.client.Client

/**
 * A [ToolRegistry] paired with the [ActionGuard] shared across all its tools (so
 * the caller can read the guard's run-level counters) and the [ToolHookChain] those
 * tools dispatch through. The chain is exposed so every OTHER call path into device
 * tools (remote MCP proxy, future attachments) reuses the SAME chain instance —
 * building a second chain silently drops `extraPostHooks` (T5: learnings steps
 * executed via `use_mcp_tool` never reached the trail).
 */
internal data class GatedToolRegistry(
    val registry: ToolRegistry,
    val guard: ActionGuard,
    val chain: ToolHookChain,
)

/**
 * Builds a Koog [ToolRegistry] from a connected MCP [Client] by discovering the
 * server's tools and wrapping each as an [McpTool]. This is the 0.8.3-tailored
 * stand-in for Koog's `McpToolRegistryProvider.fromClient` (whose module is
 * JVM-only + beta and targets SDK 0.11.1).
 *
 * Every wrapped tool shares one [ActionGuard] (Phase 1) so the perceive-before-act,
 * loop-detection, and verify-before-finish invariants are enforced across the whole
 * tool set for this run.
 *
 * The [client] must already be connected to a transport.
 */
internal suspend fun buildMcpToolRegistry(
    client: Client,
    extraPostHooks: List<PostToolHook> = emptyList(),
    /**
     * Run-scoped pre-hooks appended after [ActionGuard] and before the destructive-action
     * confirmation — today the completion gate. After the guard because the guard's checks are
     * free and its denials are the cheaper ones; before the confirmation because there is no
     * point interrupting the user about a call that is going to be refused anyway.
     */
    extraPreHooks: List<PreToolHook> = emptyList(),
    /**
     * T12 — bridges Confirm decisions to the HITL surface (AskUserBroker in the
     * real run). Default auto-allow keeps non-agent callers and tests unchanged.
     */
    confirm: suspend (String) -> Boolean = { true },
    /** T12 — future Settings toggle seam; on by default. */
    confirmDestructiveEnabled: () -> Boolean = { true },
    /** Where each tool call reports name/duration/success; the registry is the single wiring point. */
    telemetry: ToolTelemetrySink = FirebaseToolTelemetrySink.shared,
    /** Package name → installed app label, for the status strip's sentences. */
    appLabel: (String) -> String? = { null },
    /** The status strip's headline — the plan step in progress; null before a plan exists. */
    headline: suspend () -> String? = { null },
): GatedToolRegistry {
    // The server registers for two audiences; the agent is only one of them. A tool that exists
    // to explain the device to a stranger costs this lane tokens on every request and adds a
    // candidate the model must weigh every turn. See [AgentLaneTools] for the bar an exclusion
    // has to clear — it is "can never be the right move here", not "rarely used".
    val sdkTools = client.listTools()?.tools.orEmpty()
        .filter { com.aura.mcp.server.AgentLaneTools.availableToAgent(it.name) }
    val guard = ActionGuard()
    // ActionGuard is one hook in the chain (both Pre and Post). Sub-project #4
    // appends a LearningsWriteHook via [extraPostHooks].
    // E6 — hooks are registered ONCE; the chain routes lanes. A hook that also
    // implements PostToolFailureHook gets isError results on its failure method.
    // StateFeedHook publishes "what the agent just did" to AuraStateStore, which is what lets the
    // conversation plane answer follow-ups from fact instead of guessing.
    // StatusNarrationHook turns each call into the sentence the on-screen status strip shows.
    // Presentation only — it never blocks, rewrites or inspects anything a guard depends on,
    // which is why it can sit last and why a throw in it would be a bug rather than a risk.
    val allPost = listOf(
        guard,
        com.aura.aura_ui.agent.mcpbridge.hooks.StateFeedHook(),
        com.aura.aura_ui.agent.mcpbridge.hooks.StatusNarrationHook(appLabel = appLabel, headline = headline),
        // Records what AURA looked up and what it was told. Both routes — web_search and the
        // browser fallback — so the log does not go quiet on a device with no Tavily key.
        com.aura.aura_ui.agent.mcpbridge.hooks.ResearchLogHook(),
    ) + extraPostHooks
    val chain = ToolHookChain(
        // ArgSanityHook first: cheapest check, and a certainly-invalid call should
        // never consume ActionGuard's loop/grounding budget. ConfirmDestructiveHook
        // last: only calls that survive every Deny are worth interrupting the user.
        pre = listOf(
            com.aura.aura_ui.agent.mcpbridge.hooks.ArgSanityHook(),
            guard,
        ) + extraPreHooks + listOf(
            com.aura.aura_ui.agent.mcpbridge.hooks.ConfirmDestructiveHook(confirmDestructiveEnabled),
        ),
        post = allPost,
        failure = allPost.filterIsInstance<com.aura.aura_ui.agent.mcpbridge.hooks.PostToolFailureHook>(),
    )
    val ctx = HookContext(confirm = confirm)
    val registry = ToolRegistry {
        sdkTools.forEach { sdkTool ->
            tool(McpTool(client, McpToolSchemaParser.parse(sdkTool), chain, ctx, ToolMetaTable.metaFor(sdkTool.name), telemetry = telemetry))
        }
    }
    return GatedToolRegistry(registry, guard, chain)
}
