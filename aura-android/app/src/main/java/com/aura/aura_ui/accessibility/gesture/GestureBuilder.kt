package com.aura.aura_ui.accessibility.gesture

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlin.random.Random

/**
 * Translates a resolved gesture into an Android [GestureDescription].
 *
 * When [humanize] is true (the default) the motion is shaped by [HumanizedMotion]:
 * taps carry a hair of positional jitter, swipes follow a gentle curve with a natural
 * velocity profile (accelerate-to-release for flings). This makes input register the
 * way real touches do — the previous dead-straight, constant-velocity line was both a
 * "bot" signature and a cause of misfired fling/drag recognition. Setting
 * [humanize] = false reproduces the original single-straight-stroke behaviour exactly;
 * [RetryExecutor] uses it as a fallback if the OS rejects a segmented stroke.
 *
 * The [random] source is injectable so tests are deterministic; production uses
 * [Random.Default].
 */
class GestureBuilder(private val random: Random = Random.Default) {

    private companion object {
        const val TAP_FLOOR_MS = 50L
        const val SWIPE_FLOOR_MS = 100L
        const val LONG_PRESS_TRAVEL_FLOOR_MS = 50L
        const val LONG_PRESS_HOLD_FLOOR_MS = 400L
    }

    fun buildTap(
        x: Int,
        y: Int,
        durationMs: Long,
        humanize: Boolean = true,
    ): GestureDescription {
        val (px, py, duration) = if (humanize) {
            val plan = HumanizedMotion.planTap(x, y, durationMs, TAP_FLOOR_MS, random)
            Triple(plan.x, plan.y, plan.durationMs)
        } else {
            Triple(x, y, durationMs.coerceAtLeast(TAP_FLOOR_MS))
        }
        val path = Path().apply { moveTo(px.toFloat(), py.toFloat()) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
    }

    fun buildLongPress(
        x: Int,
        y: Int,
        durationMs: Long,
        holdMs: Long,
        humanize: Boolean = true,
    ): GestureDescription {
        val travel = durationMs.coerceAtLeast(LONG_PRESS_TRAVEL_FLOOR_MS)
        val hold = holdMs.coerceAtLeast(LONG_PRESS_HOLD_FLOOR_MS)
        val (px, py) = if (humanize) {
            val plan = HumanizedMotion.planTap(x, y, travel, LONG_PRESS_TRAVEL_FLOOR_MS, random)
            plan.x to plan.y
        } else {
            x to y
        }
        val path = Path().apply { moveTo(px.toFloat(), py.toFloat()) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, travel + hold))
            .build()
    }

    fun buildSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
        humanize: Boolean = true,
        profile: HumanizedMotion.Profile = HumanizedMotion.Profile.ACCELERATE,
    ): GestureDescription {
        if (!humanize) {
            val duration = durationMs.coerceAtLeast(SWIPE_FLOOR_MS)
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            return GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()
        }

        // ONE stroke tracing the curved path (a polyline through the sampled Bézier
        // points). Critically this is a SINGLE StrokeDescription — NOT a continued-stroke
        // chain. Continued strokes (willContinue=true) must be dispatched as SEPARATE
        // sequential gestures; cramming them into one GestureDescription leaves the OS
        // waiting for a follow-up dispatch that never comes, so the final ACTION_UP is
        // never emitted and the injected finger stays DOWN — freezing the target app
        // (confirmed on Swiggy: a stuck deviceId=-1 pointer, last event MOVE not UP). A
        // single stroke always emits a clean DOWN…UP, so it can never strand the pointer.
        // We keep the curved trajectory; velocity is uniform (as it was before humanization).
        val plan = HumanizedMotion.planStroke(
            startX, startY, endX, endY, durationMs, SWIPE_FLOOR_MS, profile, random,
        )
        val path = Path().apply {
            val first = plan.segments.first()
            moveTo(first.fromX, first.fromY)
            plan.segments.forEach { lineTo(it.toX, it.toY) }
        }
        val duration = plan.segments.sumOf { it.durationMs }.coerceAtLeast(SWIPE_FLOOR_MS)
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
    }

    fun build(
        command: GestureCommand,
        start: ResolvedCoordinate,
        end: ResolvedCoordinate?,
        humanize: Boolean = true,
    ): GestureDescription {
        return when (command.gestureType) {
            GestureType.TAP -> buildTap(start.x, start.y, command.options.durationMs, humanize)
            GestureType.LONG_PRESS ->
                buildLongPress(
                    start.x,
                    start.y,
                    command.options.durationMs,
                    command.options.holdMs,
                    humanize,
                )
            GestureType.SWIPE, GestureType.SCROLL -> {
                val endCoord = end ?: start
                buildSwipe(start.x, start.y, endCoord.x, endCoord.y, command.options.durationMs, humanize)
            }
        }
    }
}
