package com.aura.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The one line the user sees under AURA's cursor: what the current step is for, in the driver's words.
 *
 * Every WRITE-scoped tool takes an optional [ARG] string. The agent (AURA's own, or an MCP client on
 * a computer) writes it; [scopedTool] cleans it with [clean] and hands it to [sink] after every gate
 * has passed and before the tool runs, so the words appear while the cursor travels to its target.
 *
 * - Contract: [sink] is process-global, like `phaseSink`, so the in-process agent server and the
 *   remote WebRTC server feed the same pill. It is called on the dispatching coroutine and must not
 *   block. A missing or unusable status reaches the sink as null; the host writes its own line.
 * - Change together: the host's sink (`StepPill` in the app) and its fallback wording.
 */
object ToolStatus {
    const val ARG = "status"

    /** Kept short: it is added to every WRITE tool, so each word here is paid on every request. */
    private const val DESCRIPTION = "Shown to the user: what this step is for, ~6 words"

    const val MAX_CHARS = 48

    @Volatile
    var sink: (tool: String, status: String?, args: JsonObject?) -> Unit = { _, _, _ -> }

    /** [schema] with the optional [ARG] property added; unchanged when the tool already declares one. */
    fun withStatus(schema: ToolSchema): ToolSchema {
        val props = schema.properties ?: JsonObject(emptyMap())
        if (props.containsKey(ARG)) return schema
        val prop = JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive(DESCRIPTION)))
        return ToolSchema(properties = JsonObject(props + (ARG to prop)), required = schema.required)
    }

    /** The [ARG] value of [args], cleaned; null when absent or unusable. */
    fun of(args: JsonObject?): String? = clean((args?.get(ARG) as? JsonPrimitive)?.contentOrNull)

    /**
     * A status fit for the screen, or null.
     * - Why null for ids and packages: the line is for a person; `som_42` or `com.spotify.music`
     *   means the model wrote for itself, and the host's own wording is better than that.
     */
    fun clean(raw: String?): String? {
        val text = raw?.replace(CONTROL, " ")?.replace(SPACES, " ")?.trim()?.trimEnd('.')
        if (text.isNullOrBlank() || JARGON.containsMatchIn(text)) return null
        if (text.length <= MAX_CHARS) return text
        val cut = text.take(MAX_CHARS - 1)
        val atWord = cut.substringBeforeLast(' ').takeIf { it.length >= MAX_CHARS / 2 } ?: cut
        return atWord.trimEnd(',', ';', ':', ' ') + "…"
    }

    private val CONTROL = Regex("[\\p{Cntrl}]")
    private val SPACES = Regex("\\s+")
    private val JARGON = Regex("_|\\bsom\\b|\\b[a-z]{2,}\\.[a-z0-9]+\\.[a-z0-9.]+\\b|[{}<>\\[\\]]")
}
