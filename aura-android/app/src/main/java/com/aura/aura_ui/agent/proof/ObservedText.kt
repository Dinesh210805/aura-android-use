package com.aura.aura_ui.agent.proof

import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import com.aura.aura_ui.agent.mcpbridge.hooks.PostToolHook
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject

/**
 * Everything this run has actually READ, kept so a claim can be checked against it.
 *
 * ### Why a corpus rather than trusting the model
 *
 * `record_finding` asks the agent to quote what it saw. A quote the harness cannot find in text
 * the run genuinely observed is not evidence — it is the model writing what it expects the screen
 * to say, which is exactly the failure the Reddit rows in `AGENT_RUN_EVAL_2026-09-14.md` recorded
 * ("reviewed over ten posts" after reading two screens). So every read result lands here first,
 * and the tool checks against this.
 *
 * ### Why it cannot simply keep everything
 *
 * A long run reads megabytes. The corpus is a ring capped at [MAX_CHARS]; the oldest chunk is
 * dropped when it overflows. A finding recorded promptly always verifies, and one quoted from a
 * screen read thousands of characters ago may not — which is the right bias: proof is collected as
 * you go, not reconstructed at the end.
 *
 * Text is normalized once on the way in (lowercased, whitespace collapsed) so matching is
 * insensitive to the line breaks and column padding perception output is full of.
 */
class ObservedText(private val maxChars: Int = MAX_CHARS) : PostToolHook {

    /**
     * Tools whose output is something the agent LOOKED at.
     *
     * Deliberately not "every tool": a tool result like `set_plan`'s confirmation or a learned
     * hint is harness prose, and admitting it would let the model quote the harness back to
     * itself as proof of what is on the screen.
     */
    val readTools: Set<String> = setOf(
        "read_screen", "perceive_screen", "get_screenshot", "verify_action", "get_ui_tree",
        "get_notifications", "get_device_status", "read_clipboard", "open_file", "list_files",
        "web_search", "search_contacts", "get_calendar_events", "get_messages",
        // What is playing, from Android itself: the one read that proves "the song is playing"
        // when the player's own screen is ambiguous.
        "get_media_sessions",
    )

    /** Any `browser_*` tool counts too — the page IS the thing being read. */
    private fun isRead(toolName: String): Boolean =
        toolName in readTools || toolName.startsWith("browser_")

    private val chunks = ArrayDeque<String>()
    private var totalChars = 0

    /** How many read-results have landed. Findings stamp this so proof keeps its reading order. */
    @Volatile
    var reads: Int = 0
        private set

    /**
     * A screen-changing action succeeded and nothing has been read since, so the newest read
     * describes the screen BEFORE it. A step claimed now would be judged on a stale screen — which
     * is what happens when the model sends `tap` and `mark_step done` in one turn.
     */
    @Volatile
    var changedSinceRead: Boolean = false
        private set

    @Synchronized
    fun record(text: String) {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return
        changedSinceRead = false
        chunks.addLast(normalized)
        totalChars += normalized.length
        reads++
        while (totalChars > maxChars && chunks.size > 1) {
            totalChars -= chunks.removeFirst().length
        }
    }

    /** True when [quote] appears in something this run read. Short quotes never count. */
    @Synchronized
    fun contains(quote: String): Boolean {
        val needle = normalize(quote)
        if (needle.length < MIN_QUOTE_CHARS) return false
        return chunks.any { it.contains(needle) }
    }

    @Synchronized
    fun isEmpty(): Boolean = chunks.isEmpty()

    /**
     * The tail of what was read, for the judge's "last screens" section. Newest chunks first so
     * a small budget spends itself on the most recent screen rather than the first one.
     */
    @Synchronized
    fun recentText(chunkCount: Int, maxCharsOut: Int): String =
        chunks.takeLast(chunkCount).reversed().joinToString("\n---\n").take(maxCharsOut)

    override suspend fun onPostTool(
        toolName: String,
        args: JsonObject,
        result: CallToolResult,
        ctx: HookContext,
    ) {
        if (result.isError == true) return
        if (!isRead(toolName)) {
            // media_control changes no pixels the auto-read would notice, but it changes what is
            // true — a "playing" claim right after it must be checked on a fresh read.
            if (toolName in com.aura.aura_ui.agent.strategy.ScreenAfterAction.TRIGGERS || toolName == "media_control") {
                changedSinceRead = true
            }
            return
        }
        val text = result.content.filterIsInstance<TextContent>().mapNotNull { it.text }.joinToString("\n")
        record(text)
    }

    companion object {
        /** ~4 screens of dense perception output; a few hundred KB of heap at worst. */
        const val MAX_CHARS = 120_000

        /**
         * Below this a "quote" matches half the screen by accident — "ok", "1", "the" — which
         * would make the check theatre rather than verification.
         */
        const val MIN_QUOTE_CHARS = 8

        fun normalize(text: String): String = text.lowercase().replace(WHITESPACE, " ").trim()

        private val WHITESPACE = Regex("\\s+")
    }
}
