package com.aura.aura_ui.uistream

import com.aura.mcp.cache.ScreenActivity
import java.util.concurrent.atomic.AtomicLong

/**
 * "Is the screen standing still right now?" — the half of [UiStreamServer] a consumer
 * actually gates on.
 *
 * The stream is deliberately continuous: a viewer wants every frame, including the
 * blurry ones mid-animation. An *agent* wants the opposite — it must act only on a
 * screen that has stopped moving, because a tree read taken mid-transition is a
 * partial tree that looks complete. Rather than make the stream lumpy for both, every
 * frame carries an honest [idle] flag and the consumer decides.
 *
 * ### Why ANY event, not just meaningful ones
 *
 * [ScreenActivity.classify] exists to answer "did the screen CHANGE?", and correctly
 * treats a marquee, a spinner tick or a recomposition storm as AMBIENT — noise, not
 * change. Idleness is the opposite question: "is anything moving?" A spinner is
 * precisely the thing that must read as NOT idle, so [onEvent] stamps [lastEventMs]
 * for every class. The meaningful counter is kept alongside it because "the screen is
 * still, and something real happened since you last looked" is the exact condition an
 * agent wants to wake on.
 *
 * Process-global, like [ScreenActivity], for the same reason: there is one screen.
 * Lock-free — [onEvent] runs on the accessibility service's event thread and must
 * never block it.
 */
object ScreenIdle {

    /**
     * Default quiet window. Matches `AgentContextConfig.settleQuietMs`, and the two
     * should stay equal: an agent that settles on 350 ms of quiet and a stream that
     * calls 200 ms "idle" would disagree about the same instant.
     */
    const val DEFAULT_QUIET_MS = 350L

    private val lastEvent = AtomicLong(0)
    private val lastMeaningful = AtomicLong(0)

    /** Wall-clock ms of the most recent accessibility event of ANY class. */
    val lastEventMs: Long get() = lastEvent.get()

    /** Wall-clock ms of the most recent STRUCTURAL or SEMANTIC event. */
    val lastMeaningfulMs: Long get() = lastMeaningful.get()

    /**
     * Record one classified event. Called from `AccessibilityEventBuffer.record`,
     * which has already dropped events from our own package — the overlay animates on
     * every tool call and would otherwise keep the screen permanently "busy".
     */
    fun onEvent(cls: ScreenActivity.Class, nowMs: Long) {
        lastEvent.set(nowMs)
        if (cls != ScreenActivity.Class.AMBIENT) lastMeaningful.set(nowMs)
    }

    /** How long the screen has been quiet, in ms. Large on a freshly started process. */
    fun quietForMs(nowMs: Long): Long = nowMs - lastEvent.get()

    /** True when nothing has fired for [quietMs]. */
    fun isIdle(nowMs: Long, quietMs: Long = DEFAULT_QUIET_MS): Boolean =
        quietForMs(nowMs) >= quietMs

    /** Test seam: forget all history, as if the process had just started. */
    fun resetForTest() {
        lastEvent.set(0)
        lastMeaningful.set(0)
    }
}
