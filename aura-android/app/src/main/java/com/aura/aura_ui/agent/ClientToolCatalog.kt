package com.aura.aura_ui.agent

/**
 * The always-on **client-side** agent tools — the ones attached to every run in
 * [AuraAgent] that never reach the MCP server (so they aren't in the server's
 * [com.aura.mcp.server.McpToolCatalog]) and have no READ/WRITE device scope.
 * They control the conversation/plan, not the device:
 *  - `ask_user` — HITL clarifying questions (attachAskUserTool, always on).
 *  - `set_plan` / `mark_step` — ledger bookkeeping (attachLedgerTools, always on).
 *  - `recall_memory` / `save_memory` / `forget_memory` — the agent-lane memory tools
 *    (attachMemoryTools, always on since 2026-08-30).
 *
 * The Tools screen renders these alongside the device tools under an "AGENT" tag,
 * so its list matches the agent's actual callable set. Add an always-on client
 * tool → add one line here and it appears (see [AuraAgent] tool attachment).
 */
data class ClientToolInfo(val name: String, val description: String)

object ClientToolCatalog {
    val entries: List<ClientToolInfo> = listOf(
        ClientToolInfo(
            "ask_user",
            "Ask you one clarifying question and wait — with tap-to-answer options or free text.",
        ),
        ClientToolInfo(
            "set_plan",
            "Declare a short multi-step plan for a task so progress is visible and resumable.",
        ),
        ClientToolInfo(
            "mark_step",
            "Mark a plan step done, in progress, or skipped as the task proceeds.",
        ),
        ClientToolInfo(
            "recall_memory",
            "Look up something you told AURA earlier that isn't already in its context.",
        ),
        ClientToolInfo(
            "save_memory",
            "Remember one durable fact or preference you just stated about yourself.",
        ),
        ClientToolInfo(
            "forget_memory",
            "Delete one remembered fact when you ask AURA to forget it.",
        ),
        ClientToolInfo(
            "set_reminder",
            "Set a reminder that rings as a notification at the time you ask.",
        ),
    )
}
