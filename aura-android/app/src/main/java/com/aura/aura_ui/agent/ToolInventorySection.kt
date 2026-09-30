package com.aura.aura_ui.agent

import com.aura.mcp.server.McpToolCatalog

/**
 * Renders the system prompt's "your tools" section FROM the run's live tool registry, at the
 * moment the registry is final (local + remote proxy + skills + ledger + ask_user). A hardcoded
 * tool list in prompt text goes stale the day a tool is added or renamed; generating it means
 * the prompt's tool awareness is correct by construction, and the closing line gives the model
 * an anti-hallucination anchor: anything not listed does not exist.
 *
 * Names are de-duplicated and sorted so the rendered block is deterministic for a given
 * registry — keeping the prompt prefix stable (and cacheable) across turns of a run.
 *
 * With [DeferredTools], the tools whose schemas are not sent yet are listed apart, one line each
 * with a hint, so the model can choose by name and load them.
 */
object ToolInventorySection {

    private const val HINT_CAP = 90
    private val mcpHints = McpToolCatalog.entries.associate { it.name to it.description }

    /** One menu line's hint: the catalogue's short copy, else the first sentence of the schema's. */
    fun hint(name: String, description: String): String =
        mcpHints[name] ?: description.substringBefore(". ").substringBefore('\n').take(HINT_CAP)

    fun render(toolNames: List<String>, onDemand: Map<String, String> = emptyMap()): String {
        val names = toolNames.filter { it.isNotBlank() }.distinct().sorted()
        if (onDemand.isEmpty()) {
            return "# Your tools for THIS run\n" +
                "Exactly these ${names.size} tools are callable (generated from the live registry): " +
                names.joinToString(", ") + ".\n" +
                "A tool not in this list does not exist — never invent or guess a tool name."
        }
        val later = names.filter { it in onDemand }
        return "# Your tools for THIS run\n" +
            "Callable now: " + names.filter { it !in onDemand }.joinToString(", ") + ".\n" +
            "Load first — pick by name, pass every one this task needs to ${LoadToolsKoogTool.NAME} in " +
            "one call, then call them:\n" +
            later.joinToString("\n") { "- $it: ${onDemand.getValue(it)}" } + "\n" +
            "A tool in neither list does not exist — never invent or guess a tool name."
    }
}
