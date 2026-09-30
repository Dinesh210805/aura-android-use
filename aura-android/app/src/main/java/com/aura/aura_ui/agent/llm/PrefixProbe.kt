package com.aura.aura_ui.agent.llm

import org.json.JSONObject

/**
 * A2 diagnostic — where does this request stop matching the previous one?
 *
 * Provider prompt caches reuse the longest prefix shared with an earlier request. Measured
 * 2026-09-14: cached tokens plateau at ~16.1k on 18–20k prompts and even shrink call to call,
 * which none of the strategy's known rewrites (perception eviction, ledger re-append, screenshot
 * swap) explains by code reading. So measure it on the wire: the first differing message between
 * consecutive request bodies IS the cache boundary.
 *
 * Output lands on the trace's LLM call, e.g.
 * `msg 7/19 (tool) differs @412: "…old…" → "…new…"; tools same`.
 */
object PrefixProbe {

    private const val SNIPPET = 60

    fun describe(previousBody: String?, body: String): String? = runCatching {
        if (previousBody == null) return null
        val prev = JSONObject(previousBody)
        val cur = JSONObject(body)
        val tools = if (prev.opt("tools")?.toString() == cur.opt("tools")?.toString()) "tools same" else "TOOLS CHANGED"
        val a = prev.optJSONArray("messages")
        val b = cur.optJSONArray("messages") ?: return null
        val aLen = a?.length() ?: 0
        val first = (0 until minOf(aLen, b.length())).firstOrNull { a!!.get(it).toString() != b.get(it).toString() }
        when {
            first != null -> {
                val old = a!!.get(first).toString()
                val new = b.get(first).toString()
                val at = old.commonPrefixWith(new).length
                val role = b.optJSONObject(first)?.optString("role") ?: "?"
                "msg $first/${b.length()} ($role) differs @$at: \"${old.around(at)}\" → \"${new.around(at)}\"; $tools"
            }
            b.length() < aLen -> "history shrank ${aLen}→${b.length()}; $tools"
            else -> "append-only (${aLen}→${b.length()} msgs); $tools"
        }
    }.getOrNull()

    private fun String.around(at: Int) = substring(at, minOf(length, at + SNIPPET)).replace("\"", "'")
}
