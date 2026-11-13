package com.aura.aura_ui.agentcore

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * How risky a connector tool is, from what the server declares and, for a tool that runs other
 * tools, from what each call asks for.
 *
 * - Contract: [of] reads MCP annotations: `readOnlyHint` true is [ToolRisk.READ];
 *   `destructiveHint` false is [ToolRisk.ACT]; anything else is [ToolRisk.COMMIT] (MCP's own
 *   default treats a non-read-only tool as destructive). With no annotations, a read-like name
 *   ([ConnectorPins.looksReadOnly]) is READ and the rest COMMIT.
 * - Composio's meta-tools: `COMPOSIO_MULTI_EXECUTE_TOOL` runs app tools named by slug
 *   (`GMAIL_SEND_EMAIL`). [forCall] rates each call by its slugs: all read verbs is READ, anything
 *   else COMMIT, so "send this email" gets the same approval as a Send tap. Code-running tools
 *   ([HIDDEN]) are never offered: they reach the user's accounts with no per-action check.
 * - Fails: closed. An unreadable call to a meta-tool is COMMIT.
 */
object ConnectorRisk {
    /** Tools that run arbitrary code on the provider's side; never listed. */
    val HIDDEN = setOf("COMPOSIO_REMOTE_WORKBENCH", "COMPOSIO_REMOTE_BASH_TOOL")

    /** Tools that run other tools named in their arguments. */
    private val META_EXECUTE = setOf("COMPOSIO_MULTI_EXECUTE_TOOL", "COMPOSIO_EXECUTE_TOOL")

    /** Composio tools that only look things up. `MANAGE_CONNECTIONS` starts a sign-in: an act. */
    private val META_READ = setOf("COMPOSIO_SEARCH_TOOLS", "COMPOSIO_GET_TOOL_SCHEMAS", "COMPOSIO_WAIT_FOR_CONNECTIONS")

    private val READ_VERBS = setOf("GET", "LIST", "SEARCH", "FETCH", "FIND", "READ", "DESCRIBE", "QUERY", "LOOKUP", "VIEW", "CHECK")

    fun of(tool: Tool): ToolRisk {
        if (tool.name in META_READ) return ToolRisk.READ
        if (tool.name == "COMPOSIO_MANAGE_CONNECTIONS") return ToolRisk.ACT
        if (tool.name in META_EXECUTE) return ToolRisk.COMMIT
        val a = tool.annotations
        return when {
            a?.readOnlyHint == true -> ToolRisk.READ
            a?.destructiveHint == false -> ToolRisk.ACT
            a != null && (a.readOnlyHint != null || a.destructiveHint != null) -> ToolRisk.COMMIT
            ConnectorPins.looksReadOnly(tool.name.lowercase()) -> ToolRisk.READ
            else -> ToolRisk.COMMIT
        }
    }

    /** The per-call risk for a tool that runs others, or null when [tool] has a fixed risk. */
    fun forCall(tool: String): ((JsonObject) -> ToolRisk)? = if (tool !in META_EXECUTE) null else { args ->
        val slugs = slugs(args)
        if (slugs.isNotEmpty() && slugs.all(::readsOnly)) ToolRisk.READ else ToolRisk.COMMIT
    }

    /** True when a slug's action words are all reads: `GMAIL_FETCH_EMAILS`, `GOOGLECALENDAR_LIST_EVENTS`. */
    internal fun readsOnly(slug: String): Boolean {
        val words = slug.uppercase().split('_').drop(1)
        return words.any { it in READ_VERBS } && words.none { it in WRITE_VERBS }
    }

    private val WRITE_VERBS = setOf(
        "SEND", "CREATE", "DELETE", "UPDATE", "PATCH", "REPLY", "FORWARD", "POST", "INSERT", "MOVE", "TRASH", "REMOVE",
        "ADD", "MODIFY", "SHARE", "UPLOAD", "EXECUTE", "RUN", "SET", "PUT", "WRITE", "EDIT", "ARCHIVE", "INVITE", "ACCEPT",
        "DECLINE", "CANCEL", "COPY", "RENAME", "MARK", "STAR", "LABEL", "SCHEDULE", "PUBLISH", "COMMENT", "DRAFT",
    )

    /** Every `tool_slug` value anywhere in the arguments. */
    internal fun slugs(args: JsonObject): List<String> {
        val out = mutableListOf<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) ->
                    if (k == "tool_slug" || k == "slug") (v as? JsonPrimitive)?.contentOrNull?.let(out::add) else walk(v)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(args)
        return out
    }
}
