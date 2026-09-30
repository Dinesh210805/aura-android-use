package com.aura.mcp.cache

/**
 * Tiered perception — the small stateful part of the tier decision.
 *
 * **The model decides.** Tree-only is the default; when the model's target has no box,
 * or no box of its OWN (the box spans several items, or sits on a parent row), it calls
 * `perceive_screen(detail="full")`. The server does not try to predict that from a
 * learned per-screen trust score — it used to, and the score could only ever be inferred
 * from indirect evidence (did a tap change the screen?) about a question the model can
 * simply answer by looking.
 *
 * What is left here is the ONE escalation the model structurally cannot self-report:
 * that it is looking AGAIN at the very same screen it was just served cheap. Everything
 * else stateless lives in [com.aura.mcp.tools.TreeSufficiency].
 *
 * Two volatile scalars, no scoring, no per-screen map — so no lock: the access-ordered
 * LRU that made concurrent reads hazardous is gone with the score it held.
 */
class PerceptionEscalation {
    enum class Tier { CHEAP, FULL }

    @Volatile
    private var lastServedTier: Tier? = null

    /** Layout hash of the screen we last served, for repeat detection. */
    @Volatile
    private var lastServedLayout: Long? = null

    /**
     * Looking AGAIN at the very same screen we just served the cheap tier for.
     *
     * The model does not re-perceive for fun: it re-perceives because the last view did
     * not let it act. Serving the identical tree-only boxes a second time can never be
     * the answer, so this upgrades to the full pass — the cheap tier's automatic escape
     * hatch, and the only one that does not require the model to remember
     * `detail="full"`.
     *
     * Keyed on the LAYOUT hash — structure, bounds and interactivity, with text and
     * toggle state deliberately excluded. The content lane was tried first and is too
     * brittle to carry this: it folds in every node's label, so a playback position, a
     * countdown, a relative timestamp or an auto-advancing carousel makes two reads of
     * the SAME screen hash differently. Under content equality the escape hatch
     * therefore never fired on live screens — precisely the ones most likely to need
     * it. Layout equality asks the question we actually mean: *is the model looking at
     * the same screen it just failed to act on?*
     *
     * The trade is one extra CV pass when a re-perceive follows a pure data refresh
     * (same structure, new rows). That is the cheap error direction — ~1.5 s against a
     * wasted turn — and matches the bias stated in [com.aura.mcp.tools.TreeSufficiency].
     * A scroll is NOT caught by this: bounds live in the layout lane, so a scrolled
     * screen reads as a different layout and is correctly served cheap.
     *
     * Deliberately does NOT fire after a FULL view: there, more pixels are not the
     * missing ingredient, and `CvMemo` answers the repeat from cache with an explicit
     * "nothing has changed" instead.
     */
    fun repeatOfCheapView(layoutHash: Long?): Boolean =
        lastServedTier == Tier.CHEAP &&
            layoutHash != null &&
            layoutHash == lastServedLayout

    /**
     * Record what tier a perceive actually served and what that screen's layout hashed
     * to. Pass null for [layoutHash] on a tree-blind screen — its hash is constant while
     * the screen moves, so repeat detection must not use it.
     */
    fun onServed(tier: Tier, layoutHash: Long? = null) {
        lastServedTier = tier
        lastServedLayout = layoutHash
    }

    companion object {
        const val REASON_REPEAT = "repeat_same_screen"
    }
}
