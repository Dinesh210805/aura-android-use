package com.aura.mcp.cache

import java.util.concurrent.atomic.AtomicLong

/**
 * Layer 0 of screen-change detection — **passive invalidation**.
 *
 * The accessibility service already receives every event on the device. Classifying
 * each one and bumping a counter costs one atomic increment and no polling, which
 * makes this the cheapest change signal available: it catches screens that move with
 * NO tool call behind them (notification banner, splash→home, incoming call) — the
 * one thing [ScreenGeneration] structurally cannot see.
 *
 * **Events are a trigger, not an authority.** A bumped counter means "something may
 * have happened, go look"; only a [com.aura.mcp.tools.ScreenSignature] comparison can
 * say whether the screen is actually different. This split exists because
 * `CONTENT_CHANGE_TYPE_SUBTREE` carries two meanings on ONE bit — its own javadoc
 * reads "one or more content changes occurred in the subtree rooted at the source
 * node, **or** the subtree's structure changed when a node was added or removed" —
 * so no event filter can separate a list row rebinding from a dialog appearing.
 *
 * Process-global for the same reason [ScreenGeneration] is: the screen is genuinely
 * shared hardware, and an event caused by one client invalidates every client's view.
 */
object ScreenActivity {

    /** How much a single accessibility event says about the screen having moved. */
    enum class Class {
        /** The screen definitely changed — window/pane came or went. */
        STRUCTURAL,

        /** A control's state flipped (checked/enabled/expanded/selected). Strong
         * "your tap landed" evidence even when the layout is identical. */
        SEMANTIC,

        /** Everything else. May be a real change, may be a recomposition storm,
         * a marquee, a spinner tick, or a scroll. Never trusted on its own. */
        AMBIENT,
    }

    private val structural = AtomicLong(0)
    private val semantic = AtomicLong(0)

    /** Count of STRUCTURAL events seen since process start. */
    val structuralCount: Long get() = structural.get()

    /** Count of SEMANTIC events seen since process start. */
    val semanticCount: Long get() = semantic.get()

    /**
     * Single number a consumer can snapshot and later compare. Combining both
     * counters into one value keeps callers from having to store a pair and lets
     * "did anything meaningful happen" be a single `!=`.
     */
    val meaningfulCount: Long get() = structural.get() + semantic.get()

    /** Record one classified event. AMBIENT events deliberately bump nothing. */
    fun record(cls: Class) {
        when (cls) {
            Class.STRUCTURAL -> structural.incrementAndGet()
            Class.SEMANTIC -> semantic.incrementAndGet()
            Class.AMBIENT -> Unit
        }
    }

    /**
     * Classify a raw Android accessibility event by its `eventType` and
     * `contentChangeTypes` bitmasks.
     *
     * Pure and Android-free on purpose: it takes ints, so it unit-tests under plain
     * JVM `:mcp-server:test` and — more importantly — the platform constants are
     * inlined below as literals rather than referenced from `AccessibilityEvent`.
     * Public framework constant VALUES are frozen ABI and can never change, whereas
     * *referencing* a newer symbol (e.g. `CONTENT_CHANGE_TYPE_CHECKED`, added well
     * after our `minSdk`) would couple us to `compileSdk` and risk `NoSuchFieldError`
     * on older devices. Literals give us every signal on every Android version.
     *
     * Unknown/future event types fall through to [Class.AMBIENT] — fail-safe, since
     * treating an unrecognized event as "the screen definitely changed" would
     * needlessly invalidate caches.
     */
    fun classify(eventType: Int, contentChangeTypes: Int): Class {
        if (eventType == TYPE_WINDOW_STATE_CHANGED || eventType == TYPE_WINDOWS_CHANGED) {
            return Class.STRUCTURAL
        }
        if (contentChangeTypes and STRUCTURAL_CHANGE_MASK != 0) return Class.STRUCTURAL
        if (contentChangeTypes and SEMANTIC_CHANGE_MASK != 0) return Class.SEMANTIC
        if (eventType == TYPE_VIEW_SELECTED) return Class.SEMANTIC
        return Class.AMBIENT
    }

    // ── Frozen platform constant values (android.view.accessibility.AccessibilityEvent) ──
    // Verified against AOSP source. Values are public API and immutable by contract.

    private const val TYPE_VIEW_SELECTED = 1 shl 2
    private const val TYPE_WINDOW_STATE_CHANGED = 1 shl 5
    private const val TYPE_WINDOWS_CHANGED = 1 shl 22

    /** A pane appearing or disappearing is a real screen transition. */
    private const val STRUCTURAL_CHANGE_MASK =
        (1 shl 4) or // CONTENT_CHANGE_TYPE_PANE_APPEARED
            (1 shl 5) // CONTENT_CHANGE_TYPE_PANE_DISAPPEARED

    /**
     * A control reporting a new state. For an automation agent these are the single
     * best "the action worked" signals — a Wi-Fi row going checked is exactly the
     * confirmation a tap needs — so they must not be lumped in with animation noise.
     */
    private const val SEMANTIC_CHANGE_MASK =
        (1 shl 10) or // CONTENT_CHANGE_TYPE_CONTENT_INVALID
            (1 shl 11) or // CONTENT_CHANGE_TYPE_ERROR
            (1 shl 12) or // CONTENT_CHANGE_TYPE_ENABLED
            (1 shl 13) or // CONTENT_CHANGE_TYPE_CHECKED
            (1 shl 14) // CONTENT_CHANGE_TYPE_EXPANDED

    // Deliberately NOT listed anywhere above, i.e. AMBIENT:
    //   CONTENT_CHANGE_TYPE_SUBTREE (1)           — ambiguous by javadoc, see class doc
    //   CONTENT_CHANGE_TYPE_TEXT (1 shl 1)        — marquee, timers, mid-typing
    //   CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION (1 shl 2)
    //   CONTENT_CHANGE_TYPE_PANE_TITLE (1 shl 3)  — title-only edit, pane already there
    //   CONTENT_CHANGE_TYPE_STATE_DESCRIPTION (1 shl 6) — progress-bar ticks
    //   TYPE_VIEW_SCROLLED, TYPE_VIEW_TEXT_CHANGED, hover/focus events
}
