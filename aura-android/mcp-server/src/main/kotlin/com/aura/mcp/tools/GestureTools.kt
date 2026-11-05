package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.bridge.ScrollDirection
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.cache.LastSeenScreen
import com.aura.mcp.cache.PerceptionCache
import com.aura.mcp.cache.ScreenActivity
import com.aura.mcp.server.ScreenSettle
import com.aura.mcp.server.SensitivePolicy
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * Movement gestures: `swipe` and `scroll`, thin parameter-parsing shims over [DeviceBridge].
 *
 * - Contract: `scroll`'s result `action` names the direction (`scroll_down`, …); the status
 *   strip and run logs read it.
 */
internal fun Server.registerGestureTools(
    bridge: DeviceBridge,
    perceptionCache: PerceptionCache,
    uiTreeBridge: UiTreeBridge? = null,
    settleConfig: ScreenSettle.Config = ScreenSettle.Config(),
    lastSeen: LastSeenScreen = LastSeenScreen(),
) {
    registerSwipe(bridge, perceptionCache)
    registerScroll(bridge, perceptionCache, uiTreeBridge, settleConfig, lastSeen)
}

private fun Server.registerSwipe(bridge: DeviceBridge, perceptionCache: PerceptionCache) {
    scopedTool(
        name = "swipe",
        description = "Swipe across an element: pass som_id and direction (up, down, left, right) — " +
            "for sliders, carousels and swipe-to-act controls. The direction is where the finger " +
            "moves. hold_ms presses first, which makes it a drag (reorder, move an icon). To draw, " +
            "sign or trace a pattern, pass strokes with som_id: each point is [x, y] as 0–1 " +
            "fractions of that element. x1,y1,x2,y2 or pixel strokes are only for callers with " +
            "no screen reading.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "som_id" to numberSchema("Element to swipe across or draw in, from the latest screen"),
                    "direction" to stringSchema("up | down | left | right — where the finger moves"),
                    "strokes" to strokesSchema(),
                    "hold_ms" to numberSchema("Press this long before moving: a drag (default 0)"),
                    "x1" to numberSchema("Pixel form only: start X"),
                    "y1" to numberSchema("Pixel form only: start Y"),
                    "x2" to numberSchema("Pixel form only: end X"),
                    "y2" to numberSchema("Pixel form only: end Y"),
                    "duration_ms" to numberSchema("Length of each stroke in milliseconds (default 300; 600 with strokes)"),
                )
            ),
        ),
    ) { request ->
        val args = request.arguments
        val hold = (args?.intArg("hold_ms") ?: 0).coerceAtLeast(0)
        val somId = args?.intArg("som_id")
        args?.get("strokes")?.let { raw ->
            return@scopedTool swipeStrokes(bridge, perceptionCache, raw, somId, args.intArg("duration_ms") ?: 600, hold)
        }
        val dur = args?.intArg("duration_ms") ?: 300
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
        val landed = if (hold > 0) {
            bridge.performStrokes(listOf(listOf(points.x1 to points.y1, points.x2 to points.y2)), dur.toLong(), hold.toLong())
        } else {
            bridge.performSwipe(points.x1, points.y1, points.x2, points.y2, dur.toLong())
        }
        jsonOk(
            landed,
            buildMap {
                put("action", if (hold > 0) "drag" else "swipe")
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

/** `swipe` with `strokes`: resolve the element (if any), parse, then trace every stroke. */
private suspend fun swipeStrokes(
    bridge: DeviceBridge,
    perceptionCache: PerceptionCache,
    raw: JsonElement,
    somId: Int?,
    durationMs: Int,
    holdMs: Int,
): CallToolResult {
    val box = somId?.let {
        when (val res = perceptionCache.resolveForGesture(it)) {
            is SomResolveOutcome.Error -> return res.result
            is SomResolveOutcome.Coords -> Unit
        }
        perceptionCache.boundsFor(it)
            ?: return errorResult("som_id $it has no bounds. Call read_screen and pick again.")
    }
    val strokes = when (val parsed = Strokes.parse(raw, box)) {
        is Strokes.Parsed.Error -> return errorResult(parsed.message)
        is Strokes.Parsed.Ok -> parsed.strokes
    }
    val dur = durationMs.coerceAtLeast(MIN_STROKE_MS).toLong()
    Strokes.checkTiming(strokes.size, dur, holdMs.toLong())?.let { return errorResult(it) }
    return jsonOk(
        bridge.performStrokes(strokes, dur, holdMs.toLong()),
        buildMap {
            put("action", "draw")
            somId?.let { put("som_id", it.toString()) }
            put("strokes", strokes.size.toString())
            put("points", strokes.sumOf { it.size }.toString())
            put("duration_ms", dur.toString())
        },
    )
}

/** Shorter than this and Android's own touch slop eats most of a short stroke. */
private const val MIN_STROKE_MS = 50

/** `[[[x, y], …], …]`: a list of strokes, each a list of points. */
private fun strokesSchema(): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("array"),
        "description" to JsonPrimitive(
            "Finger paths to trace, one after another, lifting between them: " +
                "[[[x, y], [x, y], …], …]. With som_id each x and y is 0–1 inside that element.",
        ),
        "items" to JsonObject(
            mapOf(
                "type" to JsonPrimitive("array"),
                "items" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "items" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                    ),
                ),
            ),
        ),
    ),
)

private fun Server.registerScroll(
    bridge: DeviceBridge,
    perceptionCache: PerceptionCache,
    uiTreeBridge: UiTreeBridge?,
    settleConfig: ScreenSettle.Config,
    lastSeen: LastSeenScreen,
) {
    scopedTool(
        name = "scroll",
        description = "Scroll by half a screen: direction up, down, left or right. Pass som_id to scroll " +
            "inside that element — a list, carousel or picker; omit it to scroll the main screen. Returns " +
            "what came into view with current som_ids, so you can tap them without looking again, or " +
            "at_end when nothing moved.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "direction" to stringSchema("up | down | left | right"),
                    "som_id" to numberSchema("Optional: the element to scroll inside, from the latest screen"),
                ),
            ),
            required = listOf("direction"),
        ),
    ) { request ->
        val dir = ElementSpan.parse(request.arguments?.stringArg("direction"))
            ?: return@scopedTool errorResult("scroll needs direction: up, down, left or right")
        val action = "scroll_${dir.name.lowercase()}"
        val somId = request.arguments?.intArg("som_id")
        val live = uiTreeBridge?.let { LiveScreen.elements(it) }.orEmpty()
        val box = if (somId != null) {
            when (val res = perceptionCache.resolveForGesture(somId)) {
                is SomResolveOutcome.Error -> return@scopedTool res.result
                is SomResolveOutcome.Coords -> Unit
            }
            perceptionCache.boundsFor(somId)
                ?: return@scopedTool errorResult("som_id $somId has no bounds. Call read_screen and pick again.")
        } else {
            // "The main screen" is its biggest scrolling element. A fixed half-screen drag lands
            // wherever the middle of the screen is, which on a page with a map or a carousel there
            // pans that instead of the page.
            live.filter { it.scrollable && it.enabled && it.coveredBy == null }.maxByOrNull { it.bbox.area }?.bbox
        }
        val ok = if (box == null) {
            bridge.performScroll(ScrollDirection.valueOf(dir.name))
        } else {
            val p = ElementSpan.forScroll(box, dir)
            bridge.performSwipe(p.x1, p.y1, p.x2, p.y2, SCROLL_IN_ELEMENT_MS)
        }
        val extra = buildMap {
            put("action", action)
            somId?.let { put("som_id", it.toString()) }
        }
        if (!ok || uiTreeBridge == null) return@scopedTool jsonOk(ok, extra)
        val look = lookAfterScroll(uiTreeBridge, perceptionCache, settleConfig, lastSeen, live, box)
            ?: return@scopedTool jsonOk(true, extra)
        // The end of a list is a fact in the result, not a guess from the next screen.
        val verdict = extra + buildMap {
            put("moved", look.moved.toString())
            if (look.moved) {
                put("hint", "Below is what the scroll brought into view; its som_ids are current, so tap them directly.")
            } else {
                put("at_end", "true")
                put("hint", "Nothing moved: this is the end in that direction. Scroll the other way or pick another element.")
            }
        }
        val result = jsonOk(true, verdict)
        CallToolResult(content = result.content + listOfNotNull(look.text?.let { TextContent(it) }))
    }
}

/** What a scroll did: whether the content moved, and the screen after it ([text] is null on a withheld app). */
private class ScrollLook(val moved: Boolean, val text: String?)

/**
 * Settles after a scroll and looks once: decides whether it moved, lists what came into view
 * with fresh som_ids, and makes those som_ids the cache's current ones.
 *
 * - Contract: the look is the same kind `read_screen` makes, so a tap on a som_id from it
 *   resolves Fresh. Null when the settled tree is unreadable; the caller then reports the gesture only.
 * - Why the scroll looks for itself: the generic post-action summary lists only the largest
 *   labels, never what scrolled in, so every scroll cost a second call to `read_screen`.
 * - Fails: a sensitive (banking, authenticator) app gets the verdict but no screen text, the
 *   same rule `PostActionObservation` applies.
 */
private suspend fun lookAfterScroll(
    uiTreeBridge: UiTreeBridge,
    perceptionCache: PerceptionCache,
    settleConfig: ScreenSettle.Config,
    lastSeen: LastSeenScreen,
    before: List<DetectedElement>,
    box: BBox?,
): ScrollLook? {
    val settle = ScreenSettle.await(uiTreeBridge, settleConfig, lastSeen.get())
    val activityAtRead = ScreenActivity.meaningfulCount
    lastSeen.set(settle.signature)
    val after = UiTreeToElements.extract(settle.payloadJson, minElements = 1)
    if (after.isEmpty()) return null
    val moved = if (box != null && before.isNotEmpty()) {
        LiveScreen.contentOf(before, box) != LiveScreen.contentOf(after, box)
    } else {
        settle.screenChanged
    }
    val pkg = ScreenFrame.metaOf(settle.payloadJson).pkg
    if (SensitivePolicy.screenPackage(pkg) is SensitivePolicy.Decision.Block) return ScrollLook(moved, null)
    val payload = ScreenPayload.render(
        elements = after,
        pkg = pkg,
        idle = ReadScreen.idle(settle.settled, settle.eventCount),
        settled = settle.settled,
        offscreen = ScreenText.offscreen(settle.payloadJson),
        view = ScreenPayload.View.Revealed(before.map { it.label }.toSet()),
    ) ?: return ScrollLook(moved, null)
    perceptionCache.update(payload.elements, settle.signature, activityAtRead, pkg)
    return ScrollLook(moved, payload.text)
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
