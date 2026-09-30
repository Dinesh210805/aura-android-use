package com.aura.aura_ui.accessibility.gesture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.aura.aura_ui.utils.AgentLogger

class CoordinateResolver(private val service: AccessibilityService) {
    // Real display bounds, NOT resources.displayMetrics: dispatchGesture() and
    // getBoundsInScreen() both address the whole display, and displayMetrics can
    // exclude system-decor insets on API 30+ (varies per OEM / nav mode). Using
    // the smaller value silently clamped bottom-of-screen taps onto the wrong
    // element. See ScreenGeometry for the full rationale.
    private val size = com.aura.aura_ui.accessibility.ScreenGeometry.realSizePx(service)
    val screenWidth: Int = size.first
    val screenHeight: Int = size.second

    fun resolve(target: GestureTarget): ResolvedCoordinate? {
        return when (target) {
            is GestureTarget.Coordinates -> resolveCoordinates(target)
            is GestureTarget.UIElement -> resolveUIElement(target)
            is GestureTarget.Direction -> null
        }
    }

    fun resolveDirection(target: GestureTarget.Direction): Pair<ResolvedCoordinate, ResolvedCoordinate> {
        val centerX = screenWidth / 2
        val centerY = screenHeight / 2
        val distanceX = (screenWidth * target.distanceRatio).toInt()
        val distanceY = (screenHeight * target.distanceRatio).toInt()

        // Viewport convention: the direction names what the caller wants to SEE
        // next (where the content scrolls toward), NOT which way the finger drags.
        // scroll_down reveals content further DOWN a list, which requires dragging
        // the finger UP. This matches the scroll_* tool docs ("scroll the screen
        // down by 50% of the viewport").
        // Callers needing the raw finger-drag primitive use the `swipe` tool.
        // Do NOT "simplify" by aligning start/end with the finger direction —
        // that reintroduces the inverted-scroll bug.
        return when (target.direction) {
            SwipeDirection.UP ->
                // Reveal content above → drag finger downward.
                Pair(
                    ResolvedCoordinate(centerX, centerY - distanceY / 2),
                    ResolvedCoordinate(centerX, centerY + distanceY / 2),
                )
            SwipeDirection.DOWN ->
                // Reveal content below → drag finger upward.
                Pair(
                    ResolvedCoordinate(centerX, centerY + distanceY / 2),
                    ResolvedCoordinate(centerX, centerY - distanceY / 2),
                )
            SwipeDirection.LEFT ->
                // Reveal content to the left → drag finger rightward.
                Pair(
                    ResolvedCoordinate(centerX - distanceX / 2, centerY),
                    ResolvedCoordinate(centerX + distanceX / 2, centerY),
                )
            SwipeDirection.RIGHT ->
                // Reveal content to the right → drag finger leftward.
                Pair(
                    ResolvedCoordinate(centerX + distanceX / 2, centerY),
                    ResolvedCoordinate(centerX - distanceX / 2, centerY),
                )
        }
    }

    fun isWithinBounds(
        x: Int,
        y: Int,
    ): Boolean {
        return x in 0..screenWidth && y in 0..screenHeight
    }

    private fun resolveCoordinates(target: GestureTarget.Coordinates): ResolvedCoordinate {
        val x: Int
        val y: Int
        if (target.normalized) {
            x = (target.x * screenWidth).toInt().coerceIn(0, screenWidth)
            y = (target.y * screenHeight).toInt().coerceIn(0, screenHeight)
        } else {
            x = target.x.toInt().coerceIn(0, screenWidth)
            y = target.y.toInt().coerceIn(0, screenHeight)
        }
        return ResolvedCoordinate(x, y)
    }

    private fun resolveUIElement(target: GestureTarget.UIElement): ResolvedCoordinate? {
        val rootNode =
            service.rootInActiveWindow ?: run {
                AgentLogger.Auto.w("No active window for UI element search")
                return null
            }

        val matches = mutableListOf<AccessibilityNodeInfo>()
        try {
            searchNodes(rootNode, target, matches, 0)

            if (matches.isEmpty()) {
                AgentLogger.Auto.w(
                    "UI element not found",
                    mapOf(
                        "text" to (target.text ?: ""),
                        "resourceId" to (target.resourceId ?: ""),
                        "contentDesc" to (target.contentDesc ?: ""),
                    ),
                )
                return null
            }

            val targetIndex = target.index.coerceIn(0, matches.size - 1)
            val node = matches[targetIndex]
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            return ResolvedCoordinate(
                x = bounds.centerX(),
                y = bounds.centerY(),
                confidence = 1.0f,
                elementText = node.text?.toString(),
                bounds = bounds,
            )
        } finally {
            matches.forEach {
                @Suppress("DEPRECATION")
                it.recycle()
            }
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    private fun searchNodes(
        node: AccessibilityNodeInfo,
        target: GestureTarget.UIElement,
        matches: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > 25) return

        if (matchesTarget(node, target)) {
            matches.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchNodes(child, target, matches, depth + 1)
            @Suppress("DEPRECATION")
            child.recycle()
        }
    }

    private fun matchesTarget(
        node: AccessibilityNodeInfo,
        target: GestureTarget.UIElement,
    ): Boolean {
        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val nodeId = node.viewIdResourceName?.lowercase() ?: ""

        target.text?.let { text ->
            if (nodeText.contains(text.lowercase())) return true
        }
        target.contentDesc?.let { desc ->
            if (nodeDesc.contains(desc.lowercase())) return true
        }
        target.resourceId?.let { id ->
            if (nodeId.contains(id.lowercase())) return true
        }
        return false
    }
}
