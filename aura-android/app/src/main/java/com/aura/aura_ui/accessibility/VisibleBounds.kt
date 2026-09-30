package com.aura.aura_ui.accessibility

/**
 * Does a node's rect describe something the user can actually see?
 *
 * `AccessibilityNodeInfo.getBoundsInScreen` reports the rect CLIPPED to the visible
 * parent, not the node's layout rect. A row that has been scrolled out of its
 * viewport therefore does not arrive with its real off-viewport position — it
 * arrives COLLAPSED to a zero-area sliver on the viewport's edge.
 *
 * That is the whole mechanism behind the ghosts this filter removes. Nothing in
 * AURA clamps bounds (grep for `coerce`/`min`/`max` in this package — there is
 * none); `left == right == screenWidth + 1` is Android's own clipped rect for a
 * carousel card sitting off to the right.
 *
 * ### Why the test is geometry and never "does it have text"
 *
 * These collapsed nodes are *labeled* — they carry the real strings from the card
 * that scrolled away ("Snapdragon 8 Elite processor", "Min. 55% off"). That makes
 * them look precisely like the content a tree filter must preserve, and it is why
 * the walk used to admit them: the rule was "keep it if it has text and no size,
 * and borrow the parent's rect." When the parent is collapsed too — which is always
 * the case for a scrolled-away card, because the whole subtree is clipped together —
 * the borrow silently fails and the node lands in the payload with the garbage rect.
 *
 * A visible label with a broken rect and an invisible label are indistinguishable by
 * text. Only the rect tells them apart.
 *
 * ### Why no ancestor walk
 *
 * Tempting fix: when the parent's rect is unusable, keep climbing until a usable one
 * turns up. It is wrong here. For a scrolled-away carousel card every ancestor is
 * collapsed right up to the carousel *viewport*, which has a perfectly good on-screen
 * rect — so the climb would re-attach off-screen text to the visible carousel and the
 * model would read "Samsung Starting Rs XX,XX9" as being where the user can see it.
 * A ghost at a plausible location is worse than no ghost.
 */
object VisibleBounds {

    /**
     * True when [left]..[right] x [top]..[bottom] encloses a non-empty area that
     * overlaps the screen.
     *
     * @param screenWidth Screen width in px, or 0 when unknown. [ScreenGeometry]
     *   returns 0 if the display cannot be read; the off-screen half of the test is
     *   then skipped rather than rejecting every node and blanking the tree. The
     *   empty-rect half always applies — it needs no screen size.
     */
    fun isVisible(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        // Empty or inverted. Note `right - left` can be NEGATIVE, so this must be a
        // strict `>` against left/top — a `>= 0` width check lets inverted rects pass.
        if (right <= left || bottom <= top) return false

        if (screenWidth <= 0 || screenHeight <= 0) return true

        // Must overlap the screen at all. Half-off-screen still counts: the visible
        // part is real and tappable.
        return right > 0 && bottom > 0 && left < screenWidth && top < screenHeight
    }
}
