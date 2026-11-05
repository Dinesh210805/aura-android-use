package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/**
 * Parses `swipe`'s `strokes` argument into pixel finger paths, for drawing, signatures and
 * unlock patterns.
 *
 * - Contract: a stroke is a list of `[x, y]` points traced in order; one point is a dot. With a
 *   [BBox], each coordinate is a 0–1 fraction of that element's box, so a model can draw on a
 *   canvas it found by som_id without pixel arithmetic. Without one, coordinates are pixels.
 * - Fails: anything malformed returns [Parsed.Error] with a message the model can act on; no
 *   partial result is ever returned.
 * - Why the limits: Android rejects negative coordinates and a gesture longer than 60 s, and the
 *   bridge gives one tool call 120 s in total.
 */
internal object Strokes {

    const val MAX_STROKES = 20
    const val MAX_POINTS = 500
    const val MAX_TOTAL_MS = 60_000L

    sealed interface Parsed {
        data class Ok(val strokes: List<List<Pair<Int, Int>>>) : Parsed
        data class Error(val message: String) : Parsed
    }

    fun parse(raw: JsonElement?, box: BBox? = null): Parsed {
        val strokes = (raw as? JsonArray)?.takeIf { it.isNotEmpty() }
            ?: return Parsed.Error("strokes must be a non-empty list of strokes, each a list of [x, y] points.")
        if (strokes.size > MAX_STROKES) return Parsed.Error("At most $MAX_STROKES strokes per call; split the drawing.")
        val out = strokes.mapIndexed { i, stroke ->
            val points = (stroke as? JsonArray)?.takeIf { it.isNotEmpty() }
                ?: return Parsed.Error("stroke $i must be a non-empty list of [x, y] points.")
            if (points.size > MAX_POINTS) return Parsed.Error("stroke $i has more than $MAX_POINTS points.")
            points.mapIndexed { j, p ->
                point(p, box) ?: return Parsed.Error(
                    if (box != null) "stroke $i point $j must be [x, y] with both between 0 and 1 (fractions of the element)."
                    else "stroke $i point $j must be [x, y] in pixels, both 0 or more.",
                )
            }
        }
        return Parsed.Ok(out)
    }

    /** Null when the total run fits, otherwise the message to return. */
    fun checkTiming(strokeCount: Int, durationMs: Long, holdMs: Long): String? =
        if (strokeCount * (durationMs + holdMs) > MAX_TOTAL_MS) {
            "That drawing would take over ${MAX_TOTAL_MS / 1000} s. Use fewer strokes or a shorter duration_ms."
        } else {
            null
        }

    private fun point(raw: JsonElement, box: BBox?): Pair<Int, Int>? {
        val xy = (raw as? JsonArray)?.takeIf { it.size == 2 } ?: return null
        val x = (xy[0] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: return null
        val y = (xy[1] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: return null
        if (box == null) return if (x >= 0 && y >= 0) x.roundToInt() to y.roundToInt() else null
        if (x !in 0.0..1.0 || y !in 0.0..1.0) return null
        return (box.x1 + x * (box.x2 - box.x1)).roundToInt() to (box.y1 + y * (box.y2 - box.y1)).roundToInt()
    }
}
