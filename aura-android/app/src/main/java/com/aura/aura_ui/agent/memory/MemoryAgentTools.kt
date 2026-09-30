package com.aura.aura_ui.agent.memory

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId

/**
 * The load-bearing logic behind the agent lane's `recall_memory` / `save_memory` / `forget_memory`
 * tools (the Koog glue in [MemoryKoogTools] is thin, compile-verified adaptation — this class is
 * the unit-tested part, mirroring `LedgerPlanTools`).
 *
 * Three tools, not four: **update collapses into save**, because a save that names a `subject` or
 * a `supersedes` id already replaces in place ([EncryptedMemoryService.saveResolved]). A separate
 * `update_memory` would be a fourth descriptor in every prompt for behaviour save already has.
 *
 * Client-side only: these never reach the MCP server, so an external MCP client can never read or
 * rewrite the user's memory.
 */
class MemoryAgentTools(
    private val memory: EncryptedMemoryService,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Keyword recall over everything, including the recall-only types (episodes, task log) that
     * are deliberately kept out of the injected block. Ids come back because they are what
     * `forget_memory` and `save_memory(supersedes=…)` address.
     *
     * A query naming a time ("yesterday", "this week") also returns the history rows from that
     * span, newest first — no diary line contains the word "yesterday", so keywords alone miss it.
     * History rows carry their date so a past task never reads as something happening now.
     */
    suspend fun recall(args: JsonObject): CallToolResult {
        val query = str(args, "query")
            ?: return err("'query' is required — a few keywords describing what to look for.")
        val explicitLimit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, MAX_RECALL)
        val window = HistoryMemory.windowOf(query, clock(), zone)
        val limit = explicitLimit ?: if (window != null) MAX_RECALL else DEFAULT_RECALL
        val inWindow = window?.let { span ->
            memory.allEntries()
                .filter { it.type in HistoryMemory.HISTORY_TYPES && it.createdAtEpochMs in span }
                .sortedByDescending { it.createdAtEpochMs }
        }.orEmpty()
        val current = (inWindow + memory.recall(query, limit)).distinctBy { it.id }.take(limit)
        // Facts that stopped being true fill any room left, keyword-matched and labelled as the past.
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        val ended = memory.endedEntries()
            .filter { e -> terms.any { it in e.text.lowercase() } }
            .take((limit - current.size).coerceAtLeast(0))
        val hits = current + ended
        if (hits.isEmpty()) return ok("Nothing remembered about \"$query\".")
        return ok(
            buildString {
                append("Remembered (id · type · text):\n")
                hits.forEach {
                    val type = it.type.name.lowercase()
                    val stamp = when {
                        it.endedAtEpochMs > 0 -> " · NO LONGER TRUE since ${HistoryMemory.stamp(it.endedAtEpochMs, zone)}"
                        it.type in HistoryMemory.HISTORY_TYPES -> " · ${HistoryMemory.stamp(it.createdAtEpochMs, zone)}"
                        else -> ""
                    }
                    append("${it.id} · $type$stamp · ${it.text}\n")
                }
            }.trimEnd(),
        )
    }

    /**
     * Save, correct, or reinforce one fact. The result names which of the three happened —
     * a model told only "saved" has no way to learn that its correction landed.
     */
    suspend fun save(args: JsonObject): CallToolResult {
        val text = str(args, "text")
            ?: return err("'text' is required — the fact to remember, in one short sentence.")
        val rawType = str(args, "type")
        val type = rawType
            ?.let { runCatching { MemoryType.valueOf(it.trim().uppercase()) }.getOrNull() }
            ?: MemoryType.USER
        if (type !in MODEL_WRITABLE_TYPES) {
            return err(
                "Cannot write '${type.name.lowercase()}' — you may only save: " +
                    MODEL_WRITABLE_TYPES.joinToString(", ") { it.name.lowercase() } + ".",
            )
        }
        val subject = str(args, "subject").orEmpty()
        val supersedes = str(args, "supersedes")
        // A named id that no longer exists means the model is working from a stale recall. Saying
        // so beats silently appending a duplicate of the row it meant to replace.
        if (supersedes != null && memory.allEntries().none { it.id == supersedes }) {
            return err("No memory with id '$supersedes' — call recall_memory again for current ids.")
        }

        val outcome = memory.saveResolved(type, text, subject = subject, supersedesId = supersedes)
        val msg = when (outcome.kind) {
            EncryptedMemoryService.SaveKind.ADDED -> "Saved: \"${outcome.entry.text}\""
            EncryptedMemoryService.SaveKind.REINFORCED ->
                "Already knew that — confirmed again (${outcome.entry.confirmations}×)."
            EncryptedMemoryService.SaveKind.REPLACED ->
                "Updated: \"${outcome.previousText}\" → \"${outcome.entry.text}\""
        }
        return ok("$msg (id ${outcome.entry.id})")
    }

    /**
     * Delete one memory by id. A pinned entry is refused: the pin is the single explicit signal
     * the user has given about their own memory, and a model must not be able to overrule it.
     */
    suspend fun forget(args: JsonObject): CallToolResult {
        val id = str(args, "id") ?: return err("'id' is required — get it from recall_memory.")
        val entry = memory.allEntries().firstOrNull { it.id == id }
            ?: return err("No memory with id '$id'.")
        if (entry.pinned) {
            return err("That memory is pinned by the user — only they can remove it, in Settings → Memory.")
        }
        memory.delete(id)
        return ok("Forgotten: \"${entry.text}\"")
    }

    /**
     * Schedule a reminder that rings as a notification ([ReminderScheduler]). The agent is never
     * told the clock, so it passes time the way people say it — "in 20 minutes", "at 18:00",
     * "tomorrow at 9" — and the result echoes the absolute time it resolved to, so a wrong guess
     * is visible in the trace and in what AURA says back.
     */
    suspend fun remind(args: JsonObject): CallToolResult {
        val text = str(args, "text") ?: return err("'text' is required — what to remind them about.")
        val due = HistoryMemory.dueTime(
            nowEpochMs = clock(),
            inMinutes = args["in_minutes"]?.jsonPrimitive?.intOrNull,
            at = str(args, "at"),
            daysFromNow = args["days_from_now"]?.jsonPrimitive?.intOrNull ?: 0,
            zone = zone,
        ) ?: return err("Give either 'in_minutes' (a positive number) or 'at' as 24-hour HH:mm.")
        val daily = args["repeat_daily"]?.jsonPrimitive?.booleanOrNull == true
        val c = memory.remind(text, due, repeatDaily = daily)
        val kind = if (daily) "Daily reminder set, first" else "Reminder set for"
        return ok("$kind ${HistoryMemory.stamp(c.dueAtEpochMs, zone)}: \"${c.text}\" (id ${c.id})")
    }

    private fun str(args: JsonObject, key: String): String? =
        args[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = false)

    private fun err(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)

    companion object {
        const val DEFAULT_RECALL = 5
        const val MAX_RECALL = 15
    }
}
