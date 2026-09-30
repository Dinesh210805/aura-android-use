package com.aura.mcp.tools

import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.bridge.ScrollDirection
import com.aura.mcp.cache.PerceptionCache
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * Phase 3 — gesture-family tools: swipe, double_tap, long_press, scroll_*, scroll_to.
 *
 * All of these are thin parameter-parsing shims over [DeviceBridge] methods.
 */
internal fun Server.registerGestureTools(bridge: DeviceBridge, perceptionCache: PerceptionCache) {
    registerSwipe(bridge, perceptionCache)
    registerScrollTo(bridge)
    registerDoubleTap(bridge, perceptionCache)
    registerLongPress(bridge, perceptionCache)
    registerScrollDirectionalTools(bridge, perceptionCache)
}

private fun Server.registerSwipe(bridge: DeviceBridge, perceptionCache: PerceptionCache) {
    scopedTool(
        name = "swipe",
        description = "Swipe across an element: pass som_id and direction (up, down, left, right) — " +
            "for sliders, carousels and swipe-to-act controls. The direction is where the finger " +
            "moves. x1,y1,x2,y2 in pixels is only for callers with no screen reading.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "som_id" to numberSchema("Element to swipe across, from the latest screen"),
                    "direction" to stringSchema("up | down | left | right — where the finger moves"),
                    "x1" to numberSchema("Pixel form only: start X"),
                    "y1" to numberSchema("Pixel form only: start Y"),
                    "x2" to numberSchema("Pixel form only: end X"),
                    "y2" to numberSchema("Pixel form only: end Y"),
                    "duration_ms" to numberSchema("Gesture length in milliseconds (default 300)"),
                )
            ),
        ),
    ) { request ->
        val args = request.arguments
        val dur = args?.intArg("duration_ms") ?: 300
        val somId = args?.intArg("som_id")
        val points = if (somId != null) {
            val direction = ElementSpan.parse(args?.stringArg("direction"))
                ?: return@scopedTool errorResult("swipe with a som_id needs direction: up, down, left or right")
            when (val res = perceptionCache.resolveForGesture(somId)) {
                is SomResolveOutcome.Error -> return@scopedTool res.result
                is SomResolveOutcome.Coords -> Unit
            }
            val box = perceptionCache.boundsFor(somId)
                ?: return@scopedTool errorResult("som_id $somId has no bounds. Call read_screen and pick again.")
            ElementSpan.forSwipe(box, direction)
        } else {
            val x1 = args?.intArg("x1")
            val y1 = args?.intArg("y1")
            val x2 = args?.intArg("x2")
            val y2 = args?.intArg("y2")
            if (x1 == null || y1 == null || x2 == null || y2 == null) {
                return@scopedTool errorResult("swipe needs a som_id and a direction.")
            }
            ElementSpan.Points(x1, y1, x2, y2)
        }
        jsonOk(
            bridge.performSwipe(points.x1, points.y1, points.x2, points.y2, dur.toLong()),
            buildMap {
                put("action", "swipe")
                somId?.let { put("som_id", it.toString()) }
                put("x1", points.x1.toString())
                put("y1", points.y1.toString())
                put("x2", points.x2.toString())
                put("y2", points.y2.toString())
                put("duration_ms", dur.toString())
            },
        )
    }
}

private fun Server.registerScrollTo(bridge: DeviceBridge) {
    // scroll_to is functionally identical to swipe on the Python side — it lets
    // callers scroll inside a specific region rather than the whole screen.
    scopedTool(
        name = "scroll_to",
        description = "Scroll inside a specific region — same as swipe but expresses intent. " +
            "Pass start (x1, y1) and end (x2, y2) of the scroll gesture.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "x1" to numberSchema("Start X"),
                    "y1" to numberSchema("Start Y"),
                    "x2" to numberSchema("End X"),
                    "y2" to numberSchema("End Y"),
                    "duration_ms" to numberSchema("Gesture length (default 300)"),
                )
            ),
            required = listOf("x1", "y1", "x2", "y2"),
        ),
    ) { request ->
        val args = request.arguments
        val x1 = args?.intArg("x1")
        val y1 = args?.intArg("y1")
        val x2 = args?.intArg("x2")
        val y2 = args?.intArg("y2")
        val dur = args?.intArg("duration_ms") ?: 300
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            errorResult("scroll_to requires numeric x1, y1, x2, y2")
        } else {
            jsonOk(
                bridge.performSwipe(x1, y1, x2, y2, dur.toLong()),
                mapOf("action" to "scroll_to"),
            )
        }
    }
}

private fun Server.registerDoubleTap(bridge: DeviceBridge, perceptionCache: PerceptionCache) {
    scopedTool(
        name = "double_tap",
        description = "Double-tap an element by its som_id from the latest screen you were shown. The server " +
            "turns it into exact coordinates.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "som_id" to numberSchema("som_id from the latest screen"),
                )
            ),
            required = listOf("som_id"),
        ),
    ) { request ->
        val somId = request.arguments?.intArg("som_id")
        if (somId == null) {
            return@scopedTool errorResult("double_tap requires a numeric 'som_id' from the latest screen")
        }
        val (x, y) = when (val res = perceptionCache.resolveForGesture(somId)) {
            is SomResolveOutcome.Error -> return@scopedTool res.result
            is SomResolveOutcome.Coords -> res.x to res.y
        }
        jsonOk(
            bridge.performDoubleTap(x, y),
            mapOf("action" to "double_tap", "som_id" to somId.toString(), "x" to x.toString(), "y" to y.toString()),
        )
    }
}

private fun Server.registerLongPress(bridge: DeviceBridge, perceptionCache: PerceptionCache) {
    scopedTool(
        name = "long_press",
        description = "Press and hold an element by its som_id from the latest screen you were shown, for " +
            "duration_ms (default 1000). The server turns it into exact coordinates.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "som_id" to numberSchema("som_id from the latest screen"),
                    "duration_ms" to numberSchema("Hold duration in ms (default 1000)"),
                )
            ),
            required = listOf("som_id"),
        ),
    ) { request ->
        val args = request.arguments
        val somId = args?.intArg("som_id")
        val dur = args?.intArg("duration_ms") ?: 1000
        if (somId == null) {
            return@scopedTool errorResult("long_press requires a numeric 'som_id' from the latest screen")
        }
        val (x, y) = when (val res = perceptionCache.resolveForGesture(somId)) {
            is SomResolveOutcome.Error -> return@scopedTool res.result
            is SomResolveOutcome.Coords -> res.x to res.y
        }
        jsonOk(
            bridge.performLongPress(x, y, dur.toLong()),
            mapOf("action" to "long_press", "som_id" to somId.toString(), "x" to x.toString(), "y" to y.toString(), "duration_ms" to dur.toString()),
        )
    }
}

private fun Server.registerScrollDirectionalTools(bridge: DeviceBridge, perceptionCache: PerceptionCache) {
    for ((name, dir) in listOf(
        "scroll_up" to ElementSpan.Direction.UP,
        "scroll_down" to ElementSpan.Direction.DOWN,
        "scroll_left" to ElementSpan.Direction.LEFT,
        "scroll_right" to ElementSpan.Direction.RIGHT,
    )) {
        val word = dir.name.lowercase()
        scopedTool(
            name = name,
            description = "Scroll $word by half a screen. Pass som_id to scroll inside that element — a " +
                "list, carousel or picker; omit it to scroll the main screen. If nothing moved, try " +
                "the other direction or another element.",
            inputSchema = ToolSchema(
                properties = JsonObject(
                    mapOf("som_id" to numberSchema("Optional: the element to scroll inside, from the latest screen")),
                ),
            ),
        ) { request ->
            val somId = request.arguments?.intArg("som_id")
                ?: return@scopedTool jsonOk(bridge.performScroll(ScrollDirection.valueOf(dir.name)), mapOf("action" to name))
            when (val res = perceptionCache.resolveForGesture(somId)) {
                is SomResolveOutcome.Error -> return@scopedTool res.result
                is SomResolveOutcome.Coords -> Unit
            }
            val box = perceptionCache.boundsFor(somId)
                ?: return@scopedTool errorResult("som_id $somId has no bounds. Call read_screen and pick again.")
            val p = ElementSpan.forScroll(box, dir)
            jsonOk(
                bridge.performSwipe(p.x1, p.y1, p.x2, p.y2, SCROLL_IN_ELEMENT_MS),
                mapOf("action" to name, "som_id" to somId.toString()),
            )
        }
    }
}

/** Same length as a whole-screen scroll (`performScroll`), so the list settles rather than flings. */
private const val SCROLL_IN_ELEMENT_MS = 500L

// ── argument helpers (shared with other tool files) ─────────────────────────

/**
 * Read a numeric argument as an [Int]. The JSON schemas declare these as
 * `type: "number"`, and many MCP clients (LLMs especially) emit coordinates as
 * floats — e.g. `"x": 540.0` or `"x": 540.5`. A naive `toIntOrNull()` rejects
 * those, so a perfectly valid tap/swipe would fail with "requires integer".
 * We therefore accept an integer literal first, then fall back to parsing a
 * finite double and rounding to the nearest pixel.
 */
internal fun JsonObject.intArg(name: String): Int? {
    val raw = this[name]?.jsonPrimitive?.content ?: return null
    raw.toIntOrNull()?.let { return it }
    val d = raw.toDoubleOrNull() ?: return null
    return if (d.isFinite()) d.roundToInt() else null
}

internal fun JsonObject.stringArg(name: String): String? =
    this[name]?.jsonPrimitive?.content

internal fun errorResult(message: String): CallToolResult = CallToolResult(
    content = listOf(TextContent(message)),
    isError = true,
)
