package com.aura.mcp.cache

import com.aura.mcp.bridge.DetectedElement

/**
 * Keeps an element's som_id the same across looks at one screen.
 *
 * An element's identity is what it is, never where it is: its type, its label with digits
 * folded (`"3 items"` and `"4 items"` are one row), what it can do (tap, type, scroll), and which
 * occurrence of that identity it is in reading order (top to bottom, left to right), so two rows
 * both labelled "Reply" stay apart. Position and size are left out, so a card that slides, a
 * list that scrolls or a ticker that updates keeps its number.
 *
 * - Contract: [assign] returns one id per element of `body`, in the same order. A known identity
 *   keeps its id; a new one gets the next unused number; an id whose element has gone is never
 *   handed out again on this screen. The map resets (numbering by `body` order, which callers
 *   give largest-area first) when the app changes or when fewer than half the identities match
 *   the previous look: that is a new screen, not new content.
 * - Thread-safe. One instance per server, shared by every look that feeds `PerceptionCache`, so
 *   a `read_screen` and the scroll look after a swipe agree on numbers.
 * - Change together: `ScreenGrid.fit`, which numbers the grid with these ids.
 */
internal class SomIds {
    private var pkg: String? = null
    private val byKey = HashMap<String, Int>()
    private var lastKeys: Set<String> = emptySet()
    private var next = 1

    /** True when the last [assign] started a new screen (and so renumbered). */
    @Volatile
    var lastWasNewScreen: Boolean = true
        private set

    @Synchronized
    fun assign(pkg: String, body: List<DetectedElement>): IntArray {
        val keys = keysOf(body)
        val known = keys.count { it in lastKeys }
        val sameScreen = pkg == this.pkg && lastKeys.isNotEmpty() && known * 2 >= maxOf(keys.size, lastKeys.size)
        if (!sameScreen) {
            byKey.clear()
            next = 1
        }
        val ids = IntArray(keys.size) { i -> byKey.getOrPut(keys[i]) { next++ } }
        this.pkg = pkg
        lastKeys = keys.toSet()
        lastWasNewScreen = !sameScreen
        return ids
    }

    private fun keysOf(body: List<DetectedElement>): List<String> {
        val base = body.map { it.identity() }
        // Occurrence index in reading order, so duplicates are told apart by position in the
        // list rather than by coordinates.
        val keys = arrayOfNulls<String>(body.size)
        val seen = HashMap<String, Int>()
        body.indices.sortedWith(compareBy({ body[it].bbox.y1 }, { body[it].bbox.x1 })).forEach { i ->
            val n = seen.merge(base[i], 1, Int::plus) ?: 1
            keys[i] = if (n == 1) base[i] else "${base[i]}#$n"
        }
        return keys.map { it ?: "" }
    }

    internal companion object {
        private val DIGITS = Regex("\\d+")

        /**
         * What an element is, with no position: type, label with digits folded, and what it can do.
         * Change together: `PerceptionCache.resolve`, which follows a moved element by this key.
         */
        fun DetectedElement.identity(): String {
            val can = buildString {
                if (interactive) append('t')
                if (editable) append('e')
                if (scrollable) append('s')
                if (longClickable) append('l')
            }
            return "$elementType|${label.trim().replace(DIGITS, "#")}|$can"
        }
    }
}
