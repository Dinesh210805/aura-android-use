package com.aura.aura_ui.agent.mcpbridge

/**
 * A tolerant reader for the **string rendering** of an MCP `CallToolResult`.
 *
 * ### Why this cannot be a JSON parser
 *
 * The rendering the agent loop actually hands us is not valid JSON. Each content part holds
 * the tool's own JSON as a *string*, and the inner quotes are never escaped:
 *
 * ```
 * {"content":[{"text":"{"success":false,"action":"press_enter"}", "type":"text"}], "isError":true}
 * ```
 *
 * A strict parse of that fails at the first inner `"`. Everything downstream then fell back to
 * "unknown shape": [ToolCallOutcome] reported **success for failed calls** (the run above logged
 * a green ✓ on an `isError:true` result), and the whole envelope — base64 screenshot included —
 * was stored verbatim in the trace, where it read as a wall of noise.
 *
 * So this scans instead of parsing. Structure-driven, not tool-driven: it keys off the
 * `content` / `text` / `data` / `isError` shape of the MCP protocol itself, which is why it
 * needs no per-tool knowledge and does not go stale when a tool's payload changes.
 *
 * Pure — unit-tested in `McpEnvelopeTextTest` against strings captured off a real device.
 */
internal object McpEnvelopeText {

    /** One content part: either the tool's text, or a binary blob we refuse to inline. */
    data class Part(val text: String, val isBinary: Boolean, val approxBytes: Int = 0)

    data class Envelope(val parts: List<Part>, val isError: Boolean?)

    /** Returns null when [raw] is not an MCP result envelope at all. */
    fun parse(raw: String): Envelope? {
        val s = raw.trim()
        if (!s.startsWith("{") || !s.contains("\"content\"")) return null

        val parts = mutableListOf<Part>()
        var i = s.indexOf("\"content\"")
        while (i >= 0 && i < s.length) {
            val textAt = s.indexOf(TEXT_KEY, i)
            val dataAt = s.indexOf(DATA_KEY, i)
            when {
                textAt < 0 && dataAt < 0 -> break
                dataAt < 0 || (textAt in 0 until dataAt) -> {
                    val start = textAt + TEXT_KEY.length
                    val end = findTextEnd(s, start)
                    parts += Part(s.substring(start, end), isBinary = false)
                    i = end
                }
                else -> {
                    val start = dataAt + DATA_KEY.length
                    // Base64 contains no `"`, so the next quote IS the end of the value —
                    // exact, unlike the text case. A truncated blob has no closing quote.
                    val end = s.indexOf('"', start).takeIf { it >= 0 } ?: s.length
                    parts += Part("", isBinary = true, approxBytes = (end - start) * 3 / 4)
                    i = end + 1
                }
            }
        }
        if (parts.isEmpty()) return null
        return Envelope(parts, isError = readIsError(s))
    }

    /**
     * Find where a `"text":"…"` value ends.
     *
     * The value's own quotes are unescaped, so the only reliable landmark is the sibling key
     * that follows it. When there is none — the payload was truncated mid-value — the rest of
     * the string is the value, because dropping it would lose real content.
     */
    private fun findTextEnd(s: String, start: Int): Int {
        val candidates = TEXT_TERMINATORS.mapNotNull { t -> s.indexOf(t, start).takeIf { it >= 0 } }
        return candidates.minOrNull() ?: s.length
    }

    private fun readIsError(s: String): Boolean? = when {
        s.contains("\"isError\":true") -> true
        s.contains("\"isError\": true") -> true
        s.contains("\"isError\":false") || s.contains("\"isError\": false") -> false
        else -> null
    }

    private const val TEXT_KEY = "\"text\":\""
    private const val DATA_KEY = "\"data\":\""

    /** What can legally follow a content part's text value in this rendering. */
    private val TEXT_TERMINATORS = listOf("\", \"type\"", "\",\"type\"", "\", \"mimeType\"", "\",\"mimeType\"")
}
