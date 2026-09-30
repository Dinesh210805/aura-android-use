package com.aura.aura_ui.agent.mcpbridge

import android.os.SystemClock
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import com.aura.aura_ui.agent.mcpbridge.telemetry.FirebaseToolTelemetrySink
import com.aura.aura_ui.agent.mcpbridge.telemetry.ToolTelemetrySink
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.toKoogJSONElement
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import ai.koog.serialization.typeToken
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import com.aura.aura_ui.agent.mcpbridge.hooks.ToolHookChain
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Koog [Tool] that invokes an MCP tool through the MCP SDK [Client].
 *
 * Adapted from Koog's `ai.koog.agents.mcp.McpTool` (Apache-2.0, JetBrains/koog),
 * which is JVM-only and targets MCP SDK 0.11.1. This version is tailored to MCP
 * SDK **0.8.3** (matched to our `:mcp-server`): the only SDK-version-specific
 * difference is [execute], where 0.8.3's `Client.callTool(name, arguments)` takes
 * a `Map<String, Any?>` (a kotlinx [JsonObject] satisfies it). The serialization
 * round-trip uses Koog-core types (`ai.koog.serialization`), which are version-
 * matched to Koog 1.0.0 and independent of the MCP SDK version.
 */
@OptIn(InternalAgentsApi::class)
internal class McpTool(
    private val mcpClient: Client,
    descriptor: ToolDescriptor,
    /** Shared per-run hook chain (PreToolUse/PostToolUse gate; ActionGuard is one hook). */
    private val chain: ToolHookChain,
    /** Per-run hook context (bridges Confirm decisions to the HITL prompt). */
    private val ctx: HookContext,
    /** Per-tool behavioural metadata; [ToolMeta.maxResultChars] caps the text rendering. */
    private val meta: ToolMeta = ToolMeta(),
    metadata: Map<String, String> = emptyMap(),
    /** Where a completed tool call reports name/duration/success (Analytics + Crashlytics). */
    private val telemetry: ToolTelemetrySink = FirebaseToolTelemetrySink.shared,
) : Tool<JSONObject, CallToolResult?>(
    argsType = typeToken<JSONObject>(),
    resultType = typeToken<CallToolResult?>(),
    descriptor = descriptor,
    metadata = metadata,
) {
    private val json = Json.Default
    private val resultSerializer = CallToolResult.serializer().nullable

    override suspend fun execute(args: JSONObject): CallToolResult {
        // The gate (PreToolUse → dispatch → PostToolUse) lives in the shared
        // ToolHookChain; ActionGuard is one hook in it. A blocked or declined call
        // returns an error tool-result the model re-plans from — never a thrown
        // failure — so the Koog loop continues cleanly.
        val kt = args.toKotlinxJsonObject()
        return dispatchWithTelemetry(descriptor.name, telemetry) {
            chain.runGatedToolCall(descriptor.name, kt, ctx) { effective ->
                mcpClient.callTool(name = descriptor.name, arguments = effective)
            }
        }
    }

    override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): CallToolResult? =
        json.decodeFromJsonElement(resultSerializer, rawResult.toKotlinxJsonElement())

    override fun encodeResult(result: CallToolResult?, serializer: JSONSerializer): JSONElement =
        json.encodeToJsonElement(resultSerializer, result).toKoogJSONElement()

    /**
     * Render the tool result for the LLM. Errors are prefixed so the model can
     * recognize failure; `type`/`_meta` are stripped to save tokens.
     *
     * Non-text content (e.g. `perceive_screen`'s base64 PNG `ImageContent`) is
     * dropped from the `content` array here: a model cannot read base64 as text,
     * and a single screenshot blob (hundreds of KB) would overflow the context
     * window or be rejected by the provider. The annotated image instead reaches
     * a vision-capable model through a separate Koog `image()` attachment
     * (vision-extraction strategy node) — never through this text rendering.
     */
    override fun encodeResultToString(result: CallToolResult?, serializer: JSONSerializer): String {
        if (result?.isError == true) {
            val errorText = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            if (errorText.isNotBlank()) return "Error: $errorText"
            val fallback = json.encodeToJsonElement(resultSerializer, result).toKoogJSONElement()
            return "Error: ${serializer.encodeJSONElementToString(fallback)}"
        }
        val prepared: JsonElement = result?.let {
            val obj = json.encodeToJsonElement(resultSerializer, result).jsonObject
            val cleaned = obj
                .filterKeys { it !in setOf("type", "_meta") }
                .mapValues { (key, value) ->
                    if (key == "content" && value is JsonArray) value.keepTextContentOnly() else value
                }
            JsonObject(cleaned)
        } ?: JsonNull
        // Enforce the per-tool result cap so a pathologically large result (e.g. a huge
        // read_screen) can't blow the context window. The screenshot is already on the vision
        // channel; this only bounds the TEXT rendering.
        return capToolResultText(
            serializer.encodeJSONElementToString(prepared.toKoogJSONElement()),
            meta.maxResultChars,
        )
    }

    /** Keep only entries whose MCP content `type` is `"text"`; drop image/audio/blob. */
    private fun JsonArray.keepTextContentOnly(): JsonArray =
        JsonArray(filter { element -> (element as? JsonObject)?.contentType() == "text" })

    private fun JsonObject.contentType(): String? = this["type"]?.jsonPrimitive?.contentOrNull
}

/** Appended when a tool result is truncated to fit [ToolMeta.maxResultChars]. */
internal const val TRUNCATION_MARKER =
    " …[result truncated to fit context; narrow your query or call a more specific tool]"

/**
 * Cap an oversized tool-result text. Tries to cut on a clean closing-brace boundary near the
 * limit (so a JSON element list isn't split mid-object), otherwise hard-cuts at [maxChars],
 * then appends [TRUNCATION_MARKER]. Pure — unit-tested in `ToolResultBudgetTest`.
 */
internal fun capToolResultText(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val brace = text.lastIndexOf('}', maxChars)
    val cut = if (brace in (maxChars / 2)..maxChars) brace + 1 else maxChars
    return text.substring(0, cut) + TRUNCATION_MARKER
}

/**
 * Wraps a tool dispatch with timing + success/failure reporting to [telemetry].
 * Pure with respect to the dispatch lambda — unit-tested directly with a fake
 * sink and a fake dispatch, no MCP client or Firebase required.
 */
internal suspend fun dispatchWithTelemetry(
    toolName: String,
    telemetry: ToolTelemetrySink,
    dispatch: suspend () -> CallToolResult,
): CallToolResult {
    val start = SystemClock.elapsedRealtime()
    val result = dispatch()
    val durationMs = SystemClock.elapsedRealtime() - start
    telemetry.reportToolCall(toolName, durationMs, success = result.isError != true)
    return result
}
