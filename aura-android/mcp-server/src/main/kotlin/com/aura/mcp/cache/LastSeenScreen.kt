package com.aura.mcp.cache

import com.aura.mcp.tools.ScreenSignature

/**
 * The screen as of the last time the server LOOKED at it — by either route: a
 * `perceive_screen`, or the settle that follows a WRITE gesture.
 *
 * Exists because "did my action change the screen?" must be answered against the most
 * recent observation, not against the last perceive. A chain like
 * `launch_app → type_text → press_enter` runs with no perceive between steps (that is
 * the entire point of bundling post-action observations), so anchoring to the
 * perception cache would compare step 3 against a screen from two actions ago and
 * report `screen_changed = true` for a step that did nothing.
 *
 * Deliberately NOT folded into [PerceptionCache]: that class's signature must stay
 * pinned to the screen its som_id coordinates describe. Advancing it on every gesture
 * would make stale coordinates look fresh — the exact failure the staleness gate
 * exists to prevent.
 *
 * ### Screen history
 *
 * Also remembers the last [capacity] distinct screens it was shown, so a gesture that
 * lands back on an earlier screen can say so ("same screen as 3 actions ago"). The agent
 * previously knew only the screen right before its action, so press_back → tap → back
 * loops looked like progress. Borrowed from Artemis's "unchanged since step N" /
 * "resembles step N" hints, built on the tree signature instead of screenshot pixels.
 *
 * Matched on **layout**, not content: the same inbox with a new unread count, a ticking
 * timestamp or another row selected is still the screen the agent was on. Exact content
 * would almost never match on a real app, which would make the history dead weight.
 * Distance is counted in [ScreenGeneration] bumps — one per dispatched WRITE action.
 */
internal class LastSeenScreen(
    private val generation: () -> Long = { ScreenGeneration.current },
    private val capacity: Int = 8,
) {

    @Volatile
    private var signature: ScreenSignature.Signature? = null

    /** Distinct recent screens, oldest first, each with the generation it was last on view. */
    private val history = ArrayDeque<Pair<ScreenSignature.Signature, Long>>()

    /** Null until the server has looked at the screen even once this session. */
    fun get(): ScreenSignature.Signature? = signature

    fun set(signature: ScreenSignature.Signature?) {
        this.signature = signature
        // A blind signature can never match anything (sameLayoutAs refuses it) — storing
        // it would only push real screens out of the window.
        if (signature == null || signature.treeBlind) return
        synchronized(history) {
            // Looking at a screen again (read_screen twice, a tap that did nothing, coming
            // back to it) moves its one entry to the front instead of stacking copies, so
            // the ring holds distinct screens and "N actions ago" means actions, not looks.
            history.removeAll { it.first.sameLayoutAs(signature) }
            history.addLast(signature to generation())
            while (history.size > capacity) history.removeFirst()
        }
    }

    /**
     * How many actions ago [current] was last on view, when it is an EARLIER screen coming
     * back — null when it is new, or when it is simply the screen the agent was already on
     * (that is "unchanged", which `screen_changed` reports). Call before [set].
     */
    fun actionsSinceSeen(current: ScreenSignature.Signature): Long? = synchronized(history) {
        if (history.lastOrNull()?.first?.sameLayoutAs(current) == true) return null
        history.lastOrNull { it.first.sameLayoutAs(current) }
            ?.let { (_, seenAt) -> (generation() - seenAt).takeIf { it in 1..MAX_ACTIONS_AGO } }
    }

    private companion object {
        /**
         * [ScreenGeneration] is never reset, so an entry can outlive its agent run. A match
         * further back than a real loop is a stale slot, not circling — stay silent.
         */
        const val MAX_ACTIONS_AGO = 12L
    }
}
