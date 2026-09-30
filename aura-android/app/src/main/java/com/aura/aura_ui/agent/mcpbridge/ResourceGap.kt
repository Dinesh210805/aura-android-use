package com.aura.aura_ui.agent.mcpbridge

/**
 * Reads a `resource_required` refusal back off a tool result.
 *
 * ### What the server promised
 *
 * A tool that needs something nobody gave it returns `error: "resource_required"` plus a stable
 * `resource` id, a `fixable_by` of `agent` or `user`, and a `hint` naming the next action. See
 * `com.aura.mcp.server.ResourceRequired` for why `fixable_by` is the load-bearing field.
 *
 * ### Why this scans instead of parsing
 *
 * The rendering that reaches the agent loop on device is **not valid JSON** — each content part
 * holds the tool's own JSON as a string with unescaped inner quotes, so a strict parse fails at
 * the first inner `"`. That is not a hypothetical: [ToolCallOutcome.decodeEncodedResult]'s strict
 * lane silently never matched on device, and every failed call was logged as a green ✓ until
 * [McpEnvelopeText]'s scanning lane was added. Repeating that mistake here would mean a
 * user-fixable gap never reaching the user — failing exactly the way it already failed once.
 *
 * So detection goes through [McpEnvelopeText], and the tests are built from real device envelope
 * strings rather than hand-written JSON.
 *
 * Pure — unit-tested in `ResourceGapTest`.
 */
internal object ResourceGap {

    /** Who can supply what is missing. Mirrors `ResourceRequired.Fixer` on the server side. */
    enum class Fixer { AGENT, USER }

    /**
     * @param resource stable id (`tavily_api_key`, `screen_capture`, `aura_keyboard`).
     * @param fixableBy who can supply it.
     * @param hint the next action, verbatim from the tool — a sentence to act on, or for
     *   [Fixer.USER] a sentence to say to the person.
     */
    data class Gap(val resource: String, val fixableBy: Fixer, val hint: String)

    /**
     * The gap this result reports, or null when it is not a `resource_required` refusal.
     *
     * Accepts the typed envelope, its JSON rendering, and the unescaped on-device rendering
     * alike, because [McpEnvelopeText] keys off the protocol's `content`/`text` shape rather than
     * any tool's payload.
     */
    fun from(toolResult: Any?): Gap? {
        val raw = toolResult?.toString().orEmpty()
        if (ERROR_CODE !in raw) return null

        // Prefer the envelope's text parts; fall back to the whole string for a result rendered
        // some other way. Either way the field scan below is what actually reads the payload.
        val body = McpEnvelopeText.parse(raw)
            ?.parts
            ?.filterNot { it.isBinary }
            ?.joinToString("\n") { it.text }
            ?.takeIf { ERROR_CODE in it }
            ?: raw

        val resource = field(body, "resource") ?: return null
        val fixer = when (field(body, "fixable_by")) {
            "agent" -> Fixer.AGENT
            "user" -> Fixer.USER
            // An unrecognised value is treated as user-fixable on purpose. The two failure modes
            // are not symmetric: guessing AGENT invites a retry loop against something no tool
            // can supply, while guessing USER at worst surfaces one honest sentence.
            else -> Fixer.USER
        }
        val hint = field(body, "hint").orEmpty()
        return Gap(resource = resource, fixableBy = fixer, hint = hint)
    }

    /** True when the model must stop calling this tool and tell the user instead. */
    fun isTerminal(toolResult: Any?): Boolean = from(toolResult)?.fixableBy == Fixer.USER

    /**
     * Pull `"name":"value"` out of the payload.
     *
     * Values here are server-authored ids and hints — no nested quotes — so the first closing
     * quote genuinely ends the value. That is the difference from [McpEnvelopeText]'s harder job
     * of finding the end of a value that itself contains unescaped quotes.
     */
    private fun field(body: String, name: String): String? {
        val key = "\"$name\""
        val at = body.indexOf(key).takeIf { it >= 0 } ?: return null
        val colon = body.indexOf(':', at + key.length).takeIf { it >= 0 } ?: return null
        val open = body.indexOf('"', colon + 1).takeIf { it >= 0 } ?: return null
        val close = body.indexOf('"', open + 1).takeIf { it >= 0 } ?: return null
        return body.substring(open + 1, close).takeIf { it.isNotBlank() }
    }

    private const val ERROR_CODE = "resource_required"
}
