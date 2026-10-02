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
 * A [ToolRegistry] paired with the [ToolHookChain] its tools dispatch through. The chain is exposed so every OTHER call path into device
 * tools (remote MCP proxy, future attachments) reuses the SAME chain instance —
 * building a second chain silently drops `extraPostHooks` (T5: learnings steps
 * executed via `use_mcp_tool` never reached the trail).
 */
internal data class GatedToolRegistry(
    val registry: ToolRegistry,
    val chain: ToolHookChain,
)

/**
 * Builds a Koog [ToolRegistry] from a connected MCP [Client] by discovering the
 * server's tools and wrapping each as an [McpTool]. This is the 0.8.3-tailored
 * stand-in for Koog's `McpToolRegistryProvider.fromClient` (whose module is
 * JVM-only + beta and targets SDK 0.11.1).
 *
 * Perceive-before-act, loop detection and verify-before-finish are NOT here: the server's
 * `com.aura.mcp.server.ActionGuard` enforces them at `scopedTool`, so PC clients get them too.
 *
 * The [client] must already be connected to a transport.
 */
internal suspend fun buildMcpToolRegistry(
    client: Client,
    extraPostHooks: List<PostToolHook> = emptyList(),
    /**
     * Run-scoped pre-hooks appended after the argument check and before the destructive-action
     * confirmation — today the completion gate. Before the confirmation because there is no
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
    val sdkTools = client.listTools()?.tools.orEmpty()
    // Sub-project #4 appends a LearningsWriteHook via [extraPostHooks].
    // E6 — hooks are registered ONCE; the chain routes lanes. A hook that also
    // implements PostToolFailureHook gets isError results on its failure method.
    // StateFeedHook publishes "what the agent just did" to AuraStateStore, which is what lets the
    // conversation plane answer follow-ups from fact instead of guessing.
    // StatusNarrationHook turns each call into the sentence the on-screen status strip shows.
    // Presentation only — it never blocks, rewrites or inspects anything a guard depends on,
    // which is why it can sit last and why a throw in it would be a bug rather than a risk.
    val allPost = listOf(
        com.aura.aura_ui.agent.mcpbridge.hooks.StateFeedHook(),
        com.aura.aura_ui.agent.mcpbridge.hooks.StatusNarrationHook(appLabel = appLabel, headline = headline),
        // Records what AURA looked up and what it was told. Both routes — web_search and the
        // browser fallback — so the log does not go quiet on a device with no Tavily key.
        com.aura.aura_ui.agent.mcpbridge.hooks.ResearchLogHook(),
    ) + extraPostHooks
    val chain = ToolHookChain(
        // ArgSanityHook first: cheapest check. ConfirmDestructiveHook last: only calls
        // that survive every Deny are worth interrupting the user.
        pre = listOf(
            com.aura.aura_ui.agent.mcpbridge.hooks.ArgSanityHook(),
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
    return GatedToolRegistry(registry, chain)
}
