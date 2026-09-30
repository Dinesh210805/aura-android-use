package com.aura.aura_ui.accessibility.gesture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.aura.aura_ui.utils.AgentLogger

/**
 * The escape hatch for apps that ignore accessibility-injected touches.
 *
 * ## Why this exists (device evidence, 2026-08-16, OnePlus CPH2661 / ColorOS)
 *
 * Measured three ways at the *same* pixel (371, 2631 — Swiggy's "99 store" tab):
 *
 * | path                                    | result                          |
 * |-----------------------------------------|---------------------------------|
 * | [AccessibilityService.dispatchGesture]  | reports COMPLETED, nothing happens |
 * | `adb shell input tap` (shell UID)       | navigates                       |
 * | [AccessibilityNodeInfo.ACTION_CLICK]    | navigates                       |
 *
 * The same `dispatchGesture` call drives Settings correctly, so the injector is not
 * broken — Swiggy is discarding touches that carry
 * `MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT`, which the OS stamps on every event
 * `dispatchGesture` produces and which `input tap` does not carry. `ACTION_CLICK`
 * synthesizes **no MotionEvent at all**, so there is no flag to filter — which is why
 * it still works and why competing automation apps can tap Swiggy.
 *
 * `dispatchGesture`'s callback cannot detect any of this: `onCompleted` means "the OS
 * finished playing back the motion events", never "the app reacted". That is the
 * mechanism behind the false-success mode tracked as X1–X3 in
 * `docs/agent-review/REVIEW_LOG.md`, reproduced here one layer lower.
 *
 * ## Why gesture stays primary
 *
 * `ACTION_CLICK` invokes the view's click handler directly: no touch feedback, no
 * press state, and it targets whatever node claims `isClickable` — which is often a
 * container that is larger than the thing the user meant. Gestures remain more
 * faithful wherever they work, so this is a *fallback*, taken only when the gesture
 * demonstrably did nothing.
 */
internal object SemanticClicker {

    /**
     * Deepest clickable node whose on-screen bounds contain ([x], [y]), or null.
     *
     * Two rules here are load-bearing, both learned by measuring Swiggy:
     *
     * 1. **Never prune a subtree on its ancestor's bounds.** A parent's
     *    `getBoundsInScreen` does not reliably contain its children's — scroll
     *    containers and clipped rows report bounds that exclude visible descendants.
     *    A pruning walk made the "99 store" tab (which exists, is clickable, and does
     *    contain the point) invisible.
     * 2. **Walk every window, not just `rootInActiveWindow`.** Sheets, dialogs and nav
     *    bars routinely live in a sibling window.
     *
     * Deepest-wins because these layouts nest a clickable row inside a clickable
     * container, and the inner one is what a finger would have hit.
     */
    fun clickableNodeAt(service: AccessibilityService, x: Int, y: Int): AccessibilityNodeInfo? {
        var bestClickable: AccessibilityNodeInfo? = null
        var bestClickableDepth = -1
        var bestAny: AccessibilityNodeInfo? = null
        var bestAnyDepth = -1

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            // Recycled off-screen list items report inverted rects (left > right);
            // Rect.contains already rejects those, so no extra guard is needed.
            if (node.isEnabled && bounds.contains(x, y)) {
                if (node.isClickable && depth > bestClickableDepth) {
                    bestClickable = node
                    bestClickableDepth = depth
                }
                // Also remember the deepest node of ANY kind under the point. Requiring
                // isClickable here was too strict and silently disabled the fallback on
                // the element it was built for: Swiggy's search bar is reachable by
                // perceive_screen (the agent taps it by som_id) but is not flagged
                // clickable — it is a plain View with a touch listener, which is common in
                // React-Native and custom-drawn UIs. With no candidate at all, click()
                // never got the chance to climb to a clickable ancestor.
                if (depth > bestAnyDepth) {
                    bestAny = node
                    bestAnyDepth = depth
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i) ?: continue, depth + 1)
            }
        }

        val roots = buildList {
            runCatching { service.windows }.getOrNull()?.forEach { w ->
                runCatching { w.root }.getOrNull()?.let { add(it) }
            }
            runCatching { service.rootInActiveWindow }.getOrNull()?.let { add(it) }
        }
        roots.forEach { root -> runCatching { walk(root, 0) } }
        // Prefer a genuinely clickable node; otherwise hand back the deepest node under
        // the point and let [click] climb to whatever ancestor actually handles clicks.
        return bestClickable ?: bestAny
    }

    /**
     * `ACTION_CLICK` on [node], climbing to the nearest clickable ancestor first —
     * labels and icons are usually non-clickable children of the row that actually
     * handles the click.
     */
    fun click(node: AccessibilityNodeInfo): Boolean {
        // The caller resolved this node BEFORE dispatching and has been holding it across
        // the gesture plus the reaction wait, so it may no longer describe anything real.
        // That is not theoretical in the app this was built for: its tree hands back
        // recycled rows with inverted bounds (left > right). A node that will not refresh
        // is gone — report no fallback rather than clicking at a stale target.
        val alive = runCatching { node.refresh() }.getOrDefault(false)
        if (!alive) {
            AgentLogger.Auto.w("Semantic fallback skipped — node went stale before click")
            return false
        }

        var candidate: AccessibilityNodeInfo? = node
        var hops = 0
        while (candidate != null && !candidate.isClickable && hops < MAX_ANCESTOR_HOPS) {
            candidate = runCatching { candidate?.parent }.getOrNull()
            hops++
        }
        val target = candidate ?: node
        return runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrElse {
            AgentLogger.Auto.w("Semantic click threw: ${it.message}")
            false
        }
    }

    /** Guard against walking to the window root on a deeply nested non-clickable node. */
    private const val MAX_ANCESTOR_HOPS = 6
}

/**
 * Pure decision: after a gesture was dispatched, should we retry semantically?
 *
 * Kept free of Android types so the policy is unit-testable, which matters because
 * getting it wrong is expensive in both directions — falling back too eagerly
 * double-fires taps (an extra click on a working app), and never falling back leaves
 * gesture-filtering apps permanently broken while reporting success.
 */
internal object SemanticFallbackPolicy {

    /**
     * Deliberately does NOT take the gesture's dispatch result. "The OS refused the
     * gesture" and "the OS played it perfectly and the app ignored it" call for the
     * same remedy, and the second is the case that matters — it is the one that
     * reports success today. Keying on the dispatch result would skip the fallback in
     * exactly the situation it was built for.
     *
     * @param uiMutatedAfterDispatch any non-AURA accessibility event arrived after the
     *   gesture left — the app visibly reacted to something.
     * @param hasClickableNode a clickable node was found under the target point.
     */
    fun shouldFallBack(
        uiMutatedAfterDispatch: Boolean,
        hasClickableNode: Boolean,
    ): Boolean {
        // Nothing to fall back *to*.
        if (!hasClickableNode) return false
        // The app reacted — the gesture landed. Never double-fire.
        if (uiMutatedAfterDispatch) return false
        return true
    }
}
