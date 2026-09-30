package com.aura.aura_ui.services

/**
 * Decides whether AURA's own run controls have to get out of the way of a gesture it is
 * about to dispatch.
 *
 * ### The problem this narrows
 *
 * The bottom-centre run controls are a **touchable** window, and `dispatchGesture` injects
 * real MotionEvents — which go to the topmost window at those coordinates. A tap aimed at
 * something underneath the Pause button would be swallowed by AURA, and the agent would
 * read a "successful" gesture that did nothing. A tap landing on Cancel would end the
 * agent's own run.
 *
 * Hiding the controls for every gesture fixes that, but they are on screen for the whole
 * run, so they then blink out on every tap, swipe and scroll — including the moment the
 * user is reaching for Pause. This decides when the hide is actually necessary.
 *
 * ### Why the unknown cases hide anyway
 *
 * The failure this prevents is silent: a swallowed tap looks exactly like a tap the app
 * ignored, and the agent believes it landed. A wrong "no overlap" answer therefore
 * reintroduces the original bug in an intermittent, hard-to-diagnose form, while a wrong
 * "overlap" answer costs a 100 ms blink nobody will notice.
 *
 * The two are not remotely equal, so every case this cannot resolve with certainty —
 * unknown bounds, normalized coordinates, targets named by text rather than position,
 * direction swipes — resolves to [Decision.HIDE]. Only a plain, fully-resolved point that
 * is demonstrably clear of the controls skips it.
 */
object OverlayEvasion {

    enum class Decision {
        /** Take the controls off the glass before dispatching. */
        HIDE,

        /** The gesture cannot touch them; leave them alone and let the user keep using them. */
        LEAVE,
    }

    /** The controls' on-screen rectangle in real display pixels. */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
    }

    /**
     * @param bounds the controls' rectangle, or null when they are not on screen OR their
     *   position is not yet known. Null means HIDE — see the class KDoc; "not on screen"
     *   makes the hide a no-op anyway, so the conservative answer costs nothing there.
     * @param points every point the gesture will touch, in real display pixels. Empty means
     *   the caller could not resolve the path, which is a HIDE.
     * @param padPx grown around the controls before testing. A gesture that merely grazes
     *   the edge still risks landing on them once touch slop is applied, and the whole
     *   point of this class is to be certain before it says LEAVE.
     */
    fun decide(bounds: Bounds?, points: List<Pair<Int, Int>>, padPx: Int = 0): Decision {
        if (bounds == null) return Decision.HIDE
        if (points.isEmpty()) return Decision.HIDE

        val padded = Bounds(
            left = bounds.left - padPx,
            top = bounds.top - padPx,
            right = bounds.right + padPx,
            bottom = bounds.bottom + padPx,
        )
        return if (points.any { (x, y) -> padded.contains(x, y) }) Decision.HIDE else Decision.LEAVE
    }
}
