package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.cache.PerceptionCache
import com.aura.mcp.server.ActionGuard
import com.aura.mcp.server.scopedTool
import com.aura.mcp.server.sinks
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Tools that save the model turns: `set_value` (a toggle or field to a state, idempotent),
 * `run_steps` (several actions in one call) and `clipboard`.
 */
internal fun Server.registerStepTools(
    bridge: DeviceBridge,
    perceptionCache: PerceptionCache,
    uiTreeBridge: UiTreeBridge,
) {
    registerSetValue(bridge, perceptionCache, uiTreeBridge)
    registerRunSteps()
    registerClipboard(bridge)
}

// ── set_value ───────────────────────────────────────────────────────────────

/**
 * Puts a toggle or a text field into a stated state, and says what it holds afterwards.
 *
 * - Contract: a toggle already in the asked state is left alone (no touch), so calling it twice
 *   is safe; otherwise one tap, then a read-back. A text field is typed into, then read back;
 *   a field holding anything other than the value is a failure.
 * - Fails: closed. A covered or stale som_id is refused before any touch (same rules as `tap`).
 * - ponytail: sliders and pickers are not handled (they need ACTION_SET_PROGRESS in `:app`); the
 *   tool says so rather than guessing a drag.
 */
private fun Server.registerSetValue(bridge: DeviceBridge, perceptionCache: PerceptionCache, uiTreeBridge: UiTreeBridge) {
    scopedTool(
        name = "set_value",
        description = "Set a toggle (switch, checkbox) to on or off, or a text field to exactly this text, then " +
            "confirm it. Safe to repeat: a toggle already in that state is not touched. Use it instead of " +
            "tap when you want a state rather than a press.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "som_id" to numberSchema("The toggle, its row, or the text field, from your latest look"),
                    "value" to stringSchema("on | off for a toggle; the full text for a field"),
                ),
            ),
            required = listOf("som_id", "value"),
        ),
    ) { request ->
        val somId = request.arguments?.intArg("som_id")
            ?: return@scopedTool errorResult("set_value requires a numeric 'som_id' from the latest screen")
        val value = request.arguments?.stringArg("value")
            ?: return@scopedTool errorResult("set_value requires 'value'")
        val coords = when (val res = perceptionCache.resolveForGesture(somId)) {
            is SomResolveOutcome.Error -> return@scopedTool res.result
            is SomResolveOutcome.Coords -> res
        }
        val seen = perceptionCache.previous().first
        val element = seen.firstOrNull { it.somId == somId }
            ?: return@scopedTool errorResult("som_id $somId is not on your latest look. Call read_screen again.")
        val toggle = toggleFor(element, seen)
        when {
            toggle != null -> setToggle(bridge, uiTreeBridge, somId, coords, toggle, value)
            element.editable -> setText(bridge, uiTreeBridge, somId, coords, element.bbox, value)
            else -> errorResult(
                "som_id $somId (\"${element.label}\") is neither a toggle nor a text field. " +
                    "Use tap for a button; sliders and pickers are not supported by set_value.",
            )
        }
    }
}

/** The toggle [element] controls: itself, or the one checkable element inside its row. */
private fun toggleFor(element: DetectedElement, seen: List<DetectedElement>): DetectedElement? {
    if (element.checked != null) return element
    return seen.filter { it.checked != null && it.bbox.inside(element.bbox) }.singleOrNull()
}

private fun BBox.inside(o: BBox) = x1 >= o.x1 && y1 >= o.y1 && x2 <= o.x2 && y2 <= o.y2

private fun parseOnOff(value: String): Boolean? = when (value.trim().lowercase()) {
    "on", "true", "1", "yes", "checked", "enabled", "enable" -> true
    "off", "false", "0", "no", "unchecked", "disabled", "disable" -> false
    else -> null
}

private suspend fun setToggle(
    bridge: DeviceBridge,
    uiTreeBridge: UiTreeBridge,
    somId: Int,
    at: SomResolveOutcome.Coords,
    toggle: DetectedElement,
    value: String,
): CallToolResult {
    val want = parseOnOff(value)
        ?: return errorResult("som_id $somId is a toggle: value must be on or off, not '$value'")
    val word = if (want) "on" else "off"
    // Decide from the switch as it is now, not as last seen: in a batch, or after the user flipped
    // it, the look is older than the state, and a row's fingerprint does not cover its switch.
    val current = LiveScreen.elements(uiTreeBridge).firstOrNull { it.checked != null && it.bbox == toggle.bbox }?.checked
        ?: toggle.checked
    if (current == want) {
        return jsonOk(true, mapOf("action" to "set_value", "som_id" to "$somId", "value" to word, "changed" to "false"))
    }
    if (!bridge.performTap(at.x, at.y)) return errorResult("The tap on som_id $somId did not land.")
    var now: Boolean? = null
    repeat(VERIFY_POLLS) { i ->
        if (i > 0) delay(VERIFY_POLL_MS)
        now = LiveScreen.elements(uiTreeBridge).firstOrNull { it.checked != null && it.bbox == toggle.bbox }?.checked
        if (now == want) {
            return jsonOk(true, mapOf("action" to "set_value", "som_id" to "$somId", "value" to word, "changed" to "true"))
        }
    }
    return errorResult(
        when (now) {
            null -> "Tapped som_id $somId, but the toggle is no longer on screen, so it could not be confirmed. read_screen to check."
            else -> "Tapped som_id $somId, but it is still ${if (now == true) "on" else "off"}. It may need a " +
                "prerequisite, ask for confirmation, or be disabled. read_screen to see why."
        },
    )
}

private suspend fun setText(
    bridge: DeviceBridge,
    uiTreeBridge: UiTreeBridge,
    somId: Int,
    at: SomResolveOutcome.Coords,
    box: BBox,
    value: String,
): CallToolResult {
    if (!bridge.typeTextAt(value, at.x, at.y)) return errorResult("No editable field at som_id $somId accepted the text.")
    val now = LiveScreen.fieldText(uiTreeBridge, box, value)
    return when {
        now == value || (now != null && LiveScreen.masked(now)) ->
            jsonOk(true, mapOf("action" to "set_value", "som_id" to "$somId", "value" to value))
        // Typed, but the field could not be found again: say so rather than claim it was checked.
        now == null -> jsonOk(
            true,
            mapOf(
                "action" to "set_value", "som_id" to "$somId", "value" to value, "confirmed" to "false",
                "hint" to "Typed, but the field could not be read back. Check it on the next screen.",
            ),
        )
        // A keyboard that appends, a field that cuts or formats: say exactly what is there.
        else -> errorResult(
            "som_id $somId now holds \"${now.take(120)}\", not \"$value\". The field cuts, formats or kept old " +
                "text; clear it or accept this value before continuing.",
        )
    }
}

private const val VERIFY_POLLS = 4
private const val VERIFY_POLL_MS = 150L

// ── run_steps ───────────────────────────────────────────────────────────────

/**
 * Runs up to [MAX_STEPS] actions in one call, each through its own registered (wrapped) handler.
 *
 * - Contract: steps run in order; the first refused or failed step stops the batch, and the
 *   result says how far it got. Every step passes every gate a direct call would: scope,
 *   SensitivePolicy, the foreground guard, the control lock, ActionGuard, audit. A step whose
 *   som_id's screen moved on is refused STALE like any tap, which is what stops a batch from
 *   acting on a screen it never saw.
 * - Fails: closed. A tool outside [STEP_TOOLS] (including `run_steps` itself) is refused before
 *   any step runs. The whole batch is capped at [BATCH_BUDGET_MS].
 * - Change together: `PathStepSanitizer` in `:app` must see the nested `steps[].args`.
 */
private fun Server.registerRunSteps() {
    val guard = sinks().guard
    scopedTool(
        name = "run_steps",
        description = "Do several actions in one call when you already know them all from the current screen, " +
            "e.g. type into a field then press_enter, or set two toggles. Each step is " +
            "{\"tool\": name, \"args\": {...}}; tools: ${STEP_TOOLS.joinToString()}. Steps run in order; the " +
            "first one that fails or is refused stops the rest. Not for anything that sends, pays, deletes or " +
            "needs you to look first.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "steps" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("array"),
                            "description" to JsonPrimitive("Up to $MAX_STEPS steps: {tool, args}"),
                            "items" to JsonObject(mapOf("type" to JsonPrimitive("object"))),
                        ),
                    ),
                ),
            ),
            required = listOf("steps"),
        ),
    ) { request ->
        val raw = request.arguments?.get("steps") as? JsonArray
            ?: return@scopedTool errorResult("run_steps needs 'steps': a list of {\"tool\": name, \"args\": {...}}")
        if (raw.isEmpty() || raw.size > MAX_STEPS) {
            return@scopedTool errorResult("run_steps takes 1 to $MAX_STEPS steps, not ${raw.size}")
        }
        val steps = raw.mapIndexed { i, s ->
            val o = s as? JsonObject ?: return@scopedTool errorResult("step ${i + 1} is not an object")
            val tool = (o["tool"] as? JsonPrimitive)?.content
                ?: return@scopedTool errorResult("step ${i + 1} has no 'tool'")
            if (tool !in STEP_TOOLS) {
                return@scopedTool errorResult("step ${i + 1}: '$tool' cannot run inside run_steps. Allowed: ${STEP_TOOLS.joinToString()}")
            }
            val args = o["args"] ?: JsonObject(emptyMap())
            if (args !is JsonObject) return@scopedTool errorResult("step ${i + 1}: 'args' must be an object")
            tool to args
        }
        val done = mutableListOf<Triple<String, Boolean, String>>()
        // The newest screen any step observed: a refused step has none, but the steps before it
        // changed the screen, and "fix that step from the screen below" needs that screen.
        var observation: List<TextContent> = emptyList()
        val batch = ActionGuard.BatchStep(startedGrounded = guard?.isGrounded() ?: true)
        val finished = withTimeoutOrNull(BATCH_BUDGET_MS) {
            withContext(batch) {
                for ((tool, args) in steps) {
                    val handler = tools[tool]?.handler
                    val result = handler?.invoke(CallToolRequest(CallToolRequestParams(name = tool, arguments = args)))
                        ?: errorResult("'$tool' is not available on this device")
                    val blocks = result.content.filterIsInstance<TextContent>().map { it.text.orEmpty() }
                    val ok = result.isError != true
                    done += Triple(tool, ok, blocks.firstOrNull().orEmpty().take(MAX_STEP_TEXT))
                    result.content.filterIsInstance<TextContent>().drop(1).takeIf { it.isNotEmpty() }?.let { observation = it }
                    if (!ok) break
                }
            }
        } != null
        val allOk = finished && done.size == steps.size && done.all { it.second }
        val payload = buildJsonObject {
            put("success", allOk)
            put("action", "run_steps")
            put("completed", done.count { it.second })
            put("of", steps.size)
            putJsonArray("steps") {
                done.forEachIndexed { i, (tool, ok, text) ->
                    add(buildJsonObject { put("step", i + 1); put("tool", tool); put("ok", ok); put("result", text) })
                }
            }
            when {
                !finished -> put("hint", "Stopped: the batch ran past ${BATCH_BUDGET_MS / 1000} s. read_screen and continue one step at a time.")
                !allOk -> put("hint", "Stopped at step ${done.size}; later steps did not run. Fix that step from the screen below, then continue.")
            }
        }
        CallToolResult(content = listOf(TextContent(payload.toString())) + observation, isError = !allOk)
    }
}

/** What a batch may contain: actions on the current screen. No looks, no batches, no app-level jumps. */
internal val STEP_TOOLS = listOf("tap", "type_text", "set_value", "press_enter", "press_key", "scroll", "swipe", "wait_for")

private const val MAX_STEPS = 8
private const val BATCH_BUDGET_MS = 60_000L
private const val MAX_STEP_TEXT = 300

// ── clipboard ───────────────────────────────────────────────────────────────

/**
 * Reads or writes the clipboard's text.
 *
 * - Fails: a refused read (Android 10+ restricts background reads) is an error that says so,
 *   never an empty clipboard.
 * - Why it exists: copying an address, code or link between apps by long-press → Copy → paste is
 *   four gestures that each can miss; this is one call.
 */
private fun Server.registerClipboard(bridge: DeviceBridge) {
    scopedTool(
        name = "clipboard",
        description = "Read the clipboard's text (action read), or put text on it (action write, with text) so " +
            "the user or a paste can use it.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "action" to stringSchema("read | write"),
                    "text" to stringSchema("For write: the text to copy"),
                ),
            ),
            required = listOf("action"),
        ),
    ) { request ->
        when (request.arguments?.stringArg("action")?.trim()?.lowercase()) {
            "read" -> bridge.readClipboard()
                ?.let { jsonOk(true, mapOf("action" to "clipboard_read", "text" to it.take(MAX_CLIP))) }
                ?: errorResult("The clipboard is empty, or Android does not let AURA read it from the background on this phone.")
            "write" -> {
                val text = request.arguments?.stringArg("text")
                    ?: return@scopedTool errorResult("clipboard write needs 'text'")
                jsonOk(bridge.writeClipboard(text), mapOf("action" to "clipboard_write", "length" to text.length.toString()))
            }
            else -> errorResult("clipboard action must be read or write")
        }
    }
}

private const val MAX_CLIP = 2_000
