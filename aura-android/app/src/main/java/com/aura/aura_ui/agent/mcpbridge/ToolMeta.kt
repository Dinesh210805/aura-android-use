package com.aura.aura_ui.agent.mcpbridge

/**
 * Conservative behavioural metadata for a tool, consumed by hooks (e.g. a future
 * "confirm destructive actions" toggle) and by parallelism decisions. Defaults are
 * deliberately pessimistic: a tool is assumed to mutate state and to be unsafe to
 * run concurrently unless it declares otherwise.
 */
data class ToolMeta(
    val readOnly: Boolean = false,
    val destructive: Boolean = false,
    val concurrencySafe: Boolean = false,
    val maxResultChars: Int = 30_000,
)

/**
 * v1 source of [ToolMeta]: a hand-maintained override table keyed by tool name.
 *
 * The MCP Kotlin SDK is pinned at **0.8.3**, and [McpToolSchemaParser] reads only
 * `inputSchema`/`name`/`description` — it does NOT surface tool annotations
 * (`readOnlyHint`/`destructiveHint`). So we do not read those from the server here;
 * doing so is a later enhancement gated on an SDK upgrade.
 *
 * **Contextual destructiveness is intentionally NOT modelled here.** Tapping a "Send"
 * or "Delete" element is destructive while tapping "Cancel" is not — yet both are the
 * `tap` tool. That judgement depends on the *target*, which only the server-side
 * SensitivePolicy can see. This table captures only tool-level facts (read-only-ness).
 */
object ToolMetaTable {
    /** Perception/info tools: no state change, safe to read in parallel. */
    private val readOnlyTools = setOf(
        "perceive_screen", "read_screen", "get_screenshot", "get_annotated_screenshot",
        "get_device_status", "lookup_app", "omniparser_detect", "web_search", "validate_action",
    )

    /**
     * T12 — tool-LEVEL destructive facts only (see class KDoc for why contextual
     * destructiveness is out of scope). `notification_action` is the one tool whose
     * dispatch can IMMEDIATELY act on the world with no in-app confirmation step:
     * a direct reply sends a real message the moment it fires. Everything else
     * either opens a prefilled UI the user confirms in-app (system_intent,
     * open_deeplink) or is recoverable (dismiss_notification — excluded to avoid
     * confirm fatigue).
     */
    private val destructiveTools = setOf("notification_action")

    fun metaFor(toolName: String): ToolMeta {
        val ro = toolName in readOnlyTools
        return ToolMeta(readOnly = ro, concurrencySafe = ro, destructive = toolName in destructiveTools)
    }
}
