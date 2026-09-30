package com.aura.aura_ui.presentation.screens.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Applies [TraceVisibility] to a stored session before it is exported.
 *
 * ### Why the export needs this and the screen is not enough
 *
 * The screen decides what a user *looks at*; the export decides what *leaves the phone*. A trace
 * is shared with someone — that is the whole point of an export — so of the two surfaces this is
 * the one where a leak actually matters. `SessionJsonExporter` copies `metadata.json` through
 * verbatim, which is a deliberate property (an export can be diffed against the stored session),
 * and it means every internal block rides along unless something removes it here.
 *
 * ### Scope of the parse
 *
 * The exporter streams base64 images rather than assembling them, because a long run held twice in
 * memory is an OOM on a real handset. That constraint is about *images*. Metadata is text on the
 * order of hundreds of kilobytes, so parsing it is safe — and the images it references are still
 * streamed exactly as before.
 */
object TraceRedaction {

    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Top-level session keys that exist only to explain how AURA is built. */
    private val SESSION_INTERNALS = mapOf(
        "systemPrompt" to TraceVisibility.Facet.SYSTEM_PROMPT,
        "assembly" to TraceVisibility.Facet.ASSEMBLY,
        "budget" to TraceVisibility.Facet.BUDGET,
        "systemPromptFile" to TraceVisibility.Facet.SYSTEM_PROMPT,
    )

    /** Per-tool-invocation keys. `gridPayload`/`screenCanvas` are the character-grid pictures. */
    private val INVOCATION_INTERNALS = mapOf(
        "argsJson" to TraceVisibility.Facet.TOOL_IO,
        "outputSummary" to TraceVisibility.Facet.TOOL_IO,
        "gridPayload" to TraceVisibility.Facet.SCREEN_ASCII,
        "screenCanvas" to TraceVisibility.Facet.SCREEN_ASCII,
        "trail" to TraceVisibility.Facet.TOOL_IO,
        "rawArgsFile" to TraceVisibility.Facet.TOOL_IO,
        "rawResultFile" to TraceVisibility.Facet.TOOL_IO,
    )

    /**
     * Per-LLM-call keys. Only the prompt goes: `reasoning` is the thinking and `response` is the
     * decision, and both answer "why did it do that" — which is the question the trace is for.
     */
    private val LLM_INTERNALS = mapOf(
        "prompt" to TraceVisibility.Facet.PROMPT_DELTA,
        "rawRequestFile" to TraceVisibility.Facet.PROMPT_DELTA,
        "rawResponseFile" to TraceVisibility.Facet.PROMPT_DELTA,
    )

    /**
     * Strip everything [TraceVisibility] hides at this [debugMode], and return the JSON to write.
     *
     * Returns the input unchanged when nothing needs removing, and **also** when the input cannot
     * be parsed. That second case is deliberate for exactly one reason: an unparseable metadata
     * file is already a broken session, and failing the export outright would lose the evidence
     * someone is exporting *because* it is broken. It is safe because it can only happen in a
     * debug build — a release build's writer produced the file this reader is failing on, so a
     * parse failure there is a bug we would rather see than silently paper over.
     */
    fun redactMetadata(metadataJson: String, debugMode: Boolean): String {
        if (SESSION_INTERNALS.values.all { TraceVisibility.visible(it, debugMode) } &&
            INVOCATION_INTERNALS.values.all { TraceVisibility.visible(it, debugMode) } &&
            LLM_INTERNALS.values.all { TraceVisibility.visible(it, debugMode) }
        ) {
            return metadataJson
        }
        return runCatching {
            val root = lenient.parseToJsonElement(metadataJson).jsonObject
            json.encodeToString(JsonObject.serializer(), redact(root, debugMode))
        }.getOrDefault(metadataJson)
    }

    private fun redact(root: JsonObject, debugMode: Boolean): JsonObject = buildJsonObject {
        for ((key, value) in root) {
            val facet = SESSION_INTERNALS[key]
            if (facet != null && !TraceVisibility.visible(facet, debugMode)) continue
            when (key) {
                "invocations" -> put(key, scrub(value as? JsonArray, INVOCATION_INTERNALS, debugMode))
                "llmCalls" -> put(key, scrub(value as? JsonArray, LLM_INTERNALS, debugMode))
                else -> put(key, value)
            }
        }
    }

    /** Drop the hidden keys from every element of a list of objects, keeping order and count. */
    private fun scrub(
        array: JsonArray?,
        internals: Map<String, TraceVisibility.Facet>,
        debugMode: Boolean,
    ): JsonArray = JsonArray(
        array.orEmpty().map { element ->
            val obj = element as? JsonObject ?: return@map element
            buildJsonObject {
                for ((key, value) in obj) {
                    val facet = internals[key]
                    if (facet != null && !TraceVisibility.visible(facet, debugMode)) continue
                    put(key, value)
                }
            }
        },
    )
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
