package com.aura.aura_ui.accessibility.gesture

import kotlin.random.Random

/**
 * Pure, Android-free motion planner. It turns a gesture's endpoints and duration into
 * concrete touch geometry (jittered tap points, curved+velocity-profiled swipe segments)
 * so the resulting input reads as natural human motion rather than a constant-velocity
 * straight line — which is both a "bot" signature and a cause of misfired fling/drag
 * recognition on apps with velocity-based gesture detectors.
 *
 * Kept dependency-free (no [android.graphics.Path] / [android.accessibilityservice])
 * on purpose: [GestureBuilder] does the thin translation to the Android gesture API,
 * while all the math lives here and is unit-testable under plain JVM with a seeded
 * [Random]. Randomness is **bounded and seedable** — it exists to make motion register
 * naturally, not to defeat any app's analytics.
 */
object HumanizedMotion {

    /** Max positional jitter, in pixels, applied to tap / long-press points. */
    const val JITTER_PX = 2

    /** Number of segments a swipe/scroll stroke is split into for velocity shaping. */
    const val SEGMENT_COUNT = 8

    /** Fraction of the travel distance used as the perpendicular Bézier control offset. */
    private const val CURVE_OFFSET_RATIO = 0.06f

    /** Velocity shape across a stroke. */
    enum class Profile {
        /** Slow start, fast release — real fling velocity at ACTION_UP (swipe/scroll). */
        ACCELERATE,

        /** Slow at both ends — controlled drag / long-press move. */
        EASE_IN_OUT,
    }

    data class TapPlan(
        val x: Int,
        val y: Int,
        val durationMs: Long,
    )

    /** One straight leg of a swipe; the chain of legs approximates the curve. */
    data class Segment(
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val durationMs: Long,
    )

    data class StrokePlan(
        val segments: List<Segment>,
    )

    /**
     * Plan a tap/long-press point: small bounded jitter around the target and a
     * natural, slightly-randomized press duration (never below [floorMs]).
     */
    fun planTap(
        x: Int,
        y: Int,
        durationMs: Long,
        floorMs: Long,
        random: Random,
    ): TapPlan {
        val jx = x + random.nextInt(-JITTER_PX, JITTER_PX + 1)
        val jy = y + random.nextInt(-JITTER_PX, JITTER_PX + 1)
        // ±15% natural variation on the press duration, clamped to the floor.
        val base = durationMs.coerceAtLeast(floorMs)
        val jitterMs = (base * 0.15).toLong().coerceAtLeast(1L)
        val dur = (base + random.nextLong(-jitterMs, jitterMs + 1)).coerceAtLeast(floorMs)
        return TapPlan(jx, jy, dur)
    }

    /**
     * Plan a curved, velocity-profiled swipe from start to end over [durationMs].
     * Distance and total time are preserved; only the *shape* is humanized.
     */
    fun planStroke(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
        floorMs: Long,
        profile: Profile,
        random: Random,
    ): StrokePlan {
        val total = durationMs.coerceAtLeast(floorMs)
        val sx = startX.toFloat()
        val sy = startY.toFloat()
        val ex = endX.toFloat()
        val ey = endY.toFloat()

        val dx = ex - sx
        val dy = ey - sy
        val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        // Control point: midpoint pushed perpendicular to travel by a small, randomly
        // signed offset — a gentle arc rather than a dead-straight line.
        val mx = (sx + ex) / 2f
        val my = (sy + ey) / 2f
        val offsetMag = dist * CURVE_OFFSET_RATIO
        val sign = if (random.nextBoolean()) 1f else -1f
        val (perpX, perpY) = if (dist > 0f) {
            Pair(-dy / dist * offsetMag * sign, dx / dist * offsetMag * sign)
        } else {
            Pair(0f, 0f)
        }
        val cx = mx + perpX
        val cy = my + perpY

        val n = SEGMENT_COUNT
        val points = (0..n).map { i ->
            val t = i.toFloat() / n
            bezier(sx, cx, ex, t) to bezier(sy, cy, ey, t)
        }

        val weights = segmentWeights(n, profile)
        val weightSum = weights.sum()
        val segments = ArrayList<Segment>(n)
        var allocated = 0L
        for (i in 0 until n) {
            val (fx, fy) = points[i]
            val (tx, ty) = points[i + 1]
            // Give the last segment the remainder so the durations sum exactly to total.
            val segDur = if (i == n - 1) {
                (total - allocated).coerceAtLeast(1L)
            } else {
                (total * weights[i] / weightSum).toLong().coerceAtLeast(1L)
            }
            allocated += segDur
            segments.add(Segment(fx, fy, tx, ty, segDur))
        }
        return StrokePlan(segments)
    }

    /** Quadratic Bézier scalar interpolation for control triple (p0, p1, p2) at t. */
    private fun bezier(p0: Float, p1: Float, p2: Float, t: Float): Float {
        val u = 1f - t
        return u * u * p0 + 2f * u * t * p1 + t * t * p2
    }

    /**
     * Per-segment time weights. Higher weight = more time = slower over that (equal-length)
     * segment. ACCELERATE front-loads time (slow start, fast end → real release velocity);
     * EASE_IN_OUT is symmetric (slow ends, fast middle).
     */
    private fun segmentWeights(n: Int, profile: Profile): List<Double> =
        when (profile) {
            Profile.ACCELERATE ->
                // Linearly decreasing time: segment 0 slowest, segment n-1 fastest.
                (0 until n).map { (n - it).toDouble() }
            Profile.EASE_IN_OUT ->
                // Symmetric U — large at both ends, small in the middle.
                (0 until n).map {
                    val mid = (n - 1) / 2.0
                    1.0 + Math.abs(it - mid)
                }
        }
}
