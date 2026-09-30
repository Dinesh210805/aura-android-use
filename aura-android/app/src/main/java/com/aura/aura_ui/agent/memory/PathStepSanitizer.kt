package com.aura.aura_ui.agent.memory

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns one tool call into a STRUCTURAL step descriptor -- tool + nav label, never typed content.
 * Read-only/grounding tools contribute no step (they are not part of the path). Typed-text tools
 * record only the fact a field was filled (`<redacted>`), never the value. Labels are scrubbed.
 * Tool-name-agnostic: it binds on categories, not a frozen tool list.
 */
object PathStepSanitizer {
    private val READ_ONLY = setOf(
        "perceive_screen", "get_screenshot", "read_screen", "list_skills", "use_skill", "list_mcp_tools",
        // Ledger plan tools are client-side bookkeeping, not device actions — never a path step.
        // (Defense-in-depth: they bypass the MCP hook chain anyway.)
        "set_plan", "mark_step",
        // Assistant plane — pure inspection, never part of a nav path.
        "read_notifications", "get_media_sessions",
        // Contacts — the queried name is PII and the result is a phone number;
        // neither may ever enter learnings.
        "resolve_contact",
        // Files — a search query names the user's documents; never a path step.
        "find_files",
        // HITL — clarifying questions and the user's answers are run-local
        // (goal-specific, often PII), never a navigation fact.
        "ask_user",
        // MCP-lane grounding/verification tools (spec 2026-07-17): the external
        // dispatch stream carries the FULL tool surface, not just agent tools —
        // none of these move the UI, so none may enter a learned path.
        "get_device_status", "verify_action", "validate_action", "wait_for",
        "lookup_app", "list_app_deeplinks", "resolve_deeplink", "echo",
        "web_search", "watch_device_events", "request_screen_capture_permission",
        "get_usage_guide",
    )
    private val TYPED = setOf("type_text", "input_text", "set_text", "enter_text")

    /**
     * Assistant-plane action tools: the cross-run signal is WHICH verb ran
     * (`share_text`, `Reply`, `pause`), NEVER the free-text payload — a reply
     * or SMS body must not enter learnings even PII-scrubbed. Maps tool →
     * the single arg that names the verb.
     */
    private val VERB_ARG = mapOf(
        "system_intent" to "action",
        "notification_action" to "action",
        "media_control" to "command",
        "dismiss_notification" to null, // key is run-local noise — bare tool name suffices
        "open_file" to null, // uri/display name is the user's file — bare tool name only
    )

    fun sanitize(toolName: String, args: JsonObject): String? {
        if (toolName in READ_ONLY) return null
        if (toolName in TYPED) return "$toolName(<redacted>)"
        val neutral = labelOf(toolName, args) ?: return toolName
        return "$toolName \"$neutral\""
    }

    /**
     * Just the neutralized nav label of a step (no tool name), with the same classification as
     * [sanitize]: null for read-only tools, `<redacted>` for typed text. Used by the run ledger,
     * which stores tool and label as separate fields.
     */
    fun labelOf(toolName: String, args: JsonObject): String? {
        if (toolName in READ_ONLY) return null
        if (toolName in TYPED) return "<redacted>"
        if (toolName in VERB_ARG) {
            return VERB_ARG[toolName]?.let { neutralizeLabel(str(args, it)) }
        }
        // som_id/element_id are perception-run-local — replaying them into a future
        // run is noise at best, a mis-tap at worst. The LABEL is the cross-run signal.
        return neutralizeLabel(str(args, "label") ?: str(args, "text") ?: str(args, "description"))
    }

    /**
     * M1: a `label`/`text` arg is attacker-controllable (a webpage/app button label is attacker
     * text) and this step is replayed into future prompts. Reduce it to a short, single-line,
     * markup-free nav token so it can never carry an instruction across runs:
     *  1. PII-scrub (emails/amounts/numbers/handles),
     *  2. collapse all whitespace/newlines to single spaces (kills multi-line injected blocks),
     *  3. drop breakout/markup characters (`<>{}[]` backticks, quotes, pipes, backslashes),
     *  4. cap at [MAX_LABEL_CHARS] (a nav label is short; a paragraph is an attack).
     * Returns null if nothing safe survives.
     */
    fun neutralizeLabel(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // Strip breakout chars BEFORE the PII scrub: the scrub's own placeholders
        // (`<email>`, `<number>`, …) are trusted output and must survive; anything
        // angle-bracketed the attacker wrote must not.
        val stripped = UNSAFE_CHARS.replace(raw, "")
        val singleLine = WHITESPACE.replace(stripped, " ").trim()
        val safe = PiiFirewall.scrub(singleLine).trim()
        if (safe.isBlank()) return null
        return if (safe.length > MAX_LABEL_CHARS) safe.take(MAX_LABEL_CHARS).trim() + "…" else safe
    }

    private fun str(args: JsonObject, key: String): String? =
        args[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private const val MAX_LABEL_CHARS = 40
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Characters that either let attacker text break out of the quoted label context in the
     * rendered prompt, or read as structure/markup/code the model might act on. A legitimate nav
     * label ("Liked Songs", "Play Online", "Settings & privacy") uses none of them.
     */
    private val UNSAFE_CHARS = Regex("""[<>{}\[\]`"|\\]""")
}
