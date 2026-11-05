package com.aura.aura_ui.accessibility.gesture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aura.aura_ui.utils.AgentLogger
import com.aura.mcp.bridge.BBox
import com.aura.mcp.tools.TouchTarget

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
     * Topmost clickable node whose on-screen bounds contain ([x], [y]), or null.
     *
     * Three rules here are load-bearing; the first two were learned by measuring Swiggy:
     *
     * 1. **Never prune a subtree on its ancestor's bounds.** A parent's
     *    `getBoundsInScreen` does not reliably contain its children's — scroll
     *    containers and clipped rows report bounds that exclude visible descendants.
     *    A pruning walk made the "99 store" tab (which exists, is clickable, and does
     *    contain the point) invisible.
     * 2. **Walk every window, not just `rootInActiveWindow`.** Sheets, dialogs and nav
     *    bars routinely live in a sibling window.
     *
     * 3. **Topmost, not deepest.** Android gives a touch to the view painted last at that
     *    point, and depth is not paint order: a floating button sits shallow in the tree
     *    over a deep list row, and a dialog's buttons are shallower than the app behind
     *    them. Deepest-wins clicked the hidden row. Walking windows by ascending layer,
     *    each in drawing order, puts the nodes in paint order; [TouchTarget.pick] takes the
     *    last one under the point. A clickable row inside a clickable container still
     *    resolves to the inner one, because a child is painted after its parent.
     *
     * AURA's own windows are skipped: its pause button must never take the agent's tap.
     */
    fun clickableNodeAt(service: AccessibilityService, x: Int, y: Int): AccessibilityNodeInfo? {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val candidates = mutableListOf<TouchTarget.Candidate>()
        val ownPackage = service.packageName

        fun walk(node: AccessibilityNodeInfo) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            nodes += node
            candidates += TouchTarget.Candidate(
                // Recycled off-screen list items report inverted rects (left > right); the
                // containment test in TouchTarget rejects those, so no extra guard is needed.
                box = BBox(bounds.left, bounds.top, bounds.right, bounds.bottom),
                clickable = node.isClickable,
                enabled = node.isEnabled,
                own = node.packageName?.toString() == ownPackage,
            )
            (0 until node.childCount)
                .mapNotNull { i -> node.getChild(i)?.let { child -> Triple(child, child.drawingOrder, i) } }
                .sortedWith(compareBy({ it.second }, { it.third }))
                .forEach { (child, _, _) -> walk(child) }
        }

        val windows = runCatching { service.windows }.getOrNull().orEmpty().sortedBy { it.layer }
        val roots = windows.mapNotNull { w -> runCatching { w.root }.getOrNull() }
            .ifEmpty { listOfNotNull(runCatching { service.rootInActiveWindow }.getOrNull()) }
        roots.forEach { root -> runCatching { walk(root) } }
        // Prefer a genuinely clickable node. Otherwise the topmost node of ANY kind: Swiggy's
        // search bar is a plain View with a touch listener, not flagged clickable, and
        // requiring the flag disabled the fallback on the element it was built for. [click]
        // climbs from there to whatever ancestor actually handles clicks.
        return TouchTarget.pick(candidates, x, y)?.let { nodes[it] }
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
    /**
     * Whether an accessibility event of [eventType] shows the app reacted to a tap.
     *
     * Contract: false for input echoes (click, focus, hover, touch start/end), true for
     * everything else (repaints, navigation, scrolling, text, selection).
     * Why: an echo only says the touch arrived. The Files app (DocumentsUI) emits
     * `TYPE_VIEW_CLICKED` for an accessibility-injected tap and then ignores it, so
     * counting the echo skipped the fallback on every tap and nothing ever opened. A tap
     * that does something also repaints, so dropping echoes does not hide real reactions.
     */
    fun isReaction(eventType: Int): Boolean = eventType !in INPUT_ECHOES

    /**
     * Whether [eventType] means a window opened or closed, which counts as a tap's reaction
     * whichever app it came from.
     *
     * Why: a tap that opens another app's screen (Settings' search bar opens
     * `com.google.android.settings.intelligence`) emits its events from that other
     * package, so a check limited to the tapped app's package would miss it and fire a
     * second, semantic click on top of a tap that worked.
     */
    fun isNavigation(eventType: Int): Boolean =
        eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

    private val INPUT_ECHOES = setOf(
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        AccessibilityEvent.TYPE_VIEW_FOCUSED,
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT,
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
        AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
        AccessibilityEvent.TYPE_GESTURE_DETECTION_END,
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
    )

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
