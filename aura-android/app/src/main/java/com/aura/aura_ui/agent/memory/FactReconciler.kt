package com.aura.aura_ui.agent.memory

import android.util.Log
import org.json.JSONObject

/**
 * Decides how an automatically-extracted fact lands, the way Mem0 does: find the closest existing
 * memories, then let the model say ADD, UPDATE, DELETE or NONE.
 *
 * The summarizer used to call a blind `save`. That catches exact restatements (0.8 word overlap)
 * but nothing else: "is vegetarian" and "eats chicken now" share no words, so both were kept as
 * current truths. Here the model sees them side by side.
 *
 * Every failure (no brain configured, network, unparseable reply) falls back to that blind save, so
 * a fact is never lost to this step. No similar memory → no model call at all.
 */
class FactReconciler(
    private val memory: EncryptedMemoryService,
    private val llm: suspend (String) -> String?,
) {
    enum class Action { ADD, UPDATE, DELETE, NONE }

    data class Decision(val action: Action, val id: String?, val text: String?)

    suspend fun reconcile(fact: String, type: MemoryType = MemoryType.USER) {
        val clean = fact.trim()
        if (clean.isEmpty()) return
        val similar = memory.recall(clean, CANDIDATES).filter { it.type == type }
        if (similar.isEmpty()) {
            memory.save(type, clean)
            return
        }
        val decision = runCatching { llm(prompt(clean, similar))?.let(::parse) }.getOrNull()
        if (decision == null) {
            memory.save(type, clean)
            return
        }
        val target = similar.firstOrNull { it.id == decision.id }
        when (decision.action) {
            Action.ADD -> memory.save(type, decision.text ?: clean)
            // A reply naming an id we did not offer is a hallucinated target; store the fact plainly.
            Action.UPDATE ->
                if (target != null) {
                    memory.saveResolved(type, decision.text ?: clean, supersedesId = target.id)
                } else {
                    memory.save(type, clean)
                }
            Action.DELETE -> if (target != null) memory.endFact(target.id)
            Action.NONE -> Unit
        }
        Log.d(TAG, "fact reconciled: ${decision.action}")
    }

    companion object {
        private const val TAG = "FactReconciler"
        const val CANDIDATES = 5

        fun prompt(fact: String, existing: List<MemoryEntry>): String = buildString {
            appendLine("You keep a memory of facts about one user. A new fact was just learned.")
            appendLine("Existing memories (id: text):")
            existing.forEach { appendLine("- ${it.id}: ${it.text}") }
            appendLine("New fact: $fact")
            appendLine()
            appendLine("""Reply ONLY with JSON: {"action": "ADD"|"UPDATE"|"DELETE"|"NONE", "id": string, "text": string}""")
            appendLine("- ADD: new information none of the memories cover. text = the fact.")
            appendLine("- UPDATE: same topic as memory `id`, and the new fact changes or refines it. text = the up-to-date fact, one short sentence.")
            appendLine("- DELETE: the new fact says memory `id` is no longer true, with nothing new to store.")
            append("- NONE: already known.")
        }

        fun parse(raw: String): Decision? {
            val body = raw.substringAfter("```json", raw).substringBefore("```").trim()
            val start = body.indexOf('{')
            val end = body.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching {
                val o = JSONObject(body.substring(start, end + 1))
                val action = Action.valueOf(o.getString("action").trim().uppercase())
                Decision(
                    action,
                    o.optString("id").trim().takeIf { it.isNotEmpty() },
                    o.optString("text").trim().takeIf { it.isNotEmpty() },
                )
            }.getOrNull()
        }
    }
}
