package com.aura.mcp.server

import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.cache.ScreenActivity
import com.aura.mcp.tools.ScreenSignature

/**
 * E1 — harness-side settle: wait for the screen to stop moving after a WRITE gesture,
 * so the model never pays an LLM round trip (`wait_for`) just to let the UI catch up.
 *
 * **Signature is the authority; events only pace the wait.** The previous version
 * answered "did the screen change?" with `eventCount > 0`, which is an *activity*
 * detector: a Compose recomposition storm, a marquee, a blinking cursor or a spinner
 * tick all fire events on a screen that did not visibly change. That made
 * `screen_changed` almost always true — and it is reported straight to the model in
 * every post-action observation, so "did my tap do anything?" answered yes on a screen
 * that had not moved. Now the verdict comes from comparing a [ScreenSignature] before
 * and after; events decide only how long we are willing to keep looking.
 *
 * Three exits, cheapest first:
 *
 *  1. **Quiet start** — nothing relevant arrives within [Config.noChangeProbeMs] and
 *     the signature is unchanged. The overwhelmingly common "that tap did nothing"
 *     case now costs ~250 ms instead of the old fixed 600 ms.
 *  2. **Event quiescence** — a full [Config.quietMs] window with no relevant events.
 *     The normal transition path.
 *  3. **Signature stability** — events keep arriving but two consecutive samples hash
 *     identically, i.e. a perpetual animator (spinner, video, marquee). Exits in
 *     ~600 ms rather than burning the full [Config.maxMs] cap.
 *
 * Events from [Config.ignoredPackages] (the agent's own overlay — its status chip
 * animates on every tool call) are invisible throughout.
 *
 * The final tree snapshot is returned in [Outcome] so the caller does not re-fetch a
 * tree we just read: settle and post-action observation together now cost one
 * snapshot, not two.
 */
internal object ScreenSettle {

    data class Config(
        /**
         * Quiet period at the start that, combined with an unchanged signature, is
         * accepted as "nothing happened". Short on purpose: with the signature as
         * corroboration we no longer need a long window to be confident.
         */
        val noChangeProbeMs: Long = 250,
        /**
         * Probe window for actions that start slowly — launching an app, following a
         * deep link, firing a system intent. These can take several hundred ms before
         * the FIRST event or pixel appears, and the signature cannot rescue us there:
         * a transition that has not begun yet looks exactly like one that never will.
         * Kept at the old fixed wait so the shortened default cannot regress the paths
         * where a premature "nothing happened" is most likely and most damaging.
         */
        val slowStartProbeMs: Long = 600,
        /** Quiet window: this long with zero relevant events = settled. */
        val quietMs: Long = 300,
        /** Hard cap on the total settle wait (bounds perpetual animators). */
        val maxMs: Long = 1_500,
        /** Event sources that never count as screen activity (own overlay). */
        val ignoredPackages: Set<String> = emptySet(),
    )

    data class Outcome(
        /** False only when the cap cut a still-moving screen short. */
        val settled: Boolean,
        /**
         * Whether the screen actually changed — signature comparison, not event
         * presence. Falls back to event activity only when the tree is blind.
         */
        val screenChanged: Boolean,
        /** Relevant (non-ignored) events observed while settling. Diagnostic only. */
        val eventCount: Int,
        /** STRUCTURAL/SEMANTIC events seen — a control flipping or a pane moving. */
        val meaningfulEventCount: Int,
        val elapsedMs: Long,
        /** Post-settle signature, for the caller to record as the new baseline. */
        val signature: ScreenSignature.Signature,
        /** Tree JSON behind [signature], so the caller can summarize without re-reading. */
        val payloadJson: String,
        /**
         * True when the verdict came from raw event activity because the tree could
         * not describe this screen (WebView / Canvas / game). Lower confidence.
         */
        val degradedToEvents: Boolean,
    )

    private const val BACKLOG_MAX = 256
    private const val BURST_MAX = 64

    suspend fun await(
        bridge: UiTreeBridge,
        config: Config = Config(),
        preSignature: ScreenSignature.Signature? = null,
        nowMs: () -> Long = System::currentTimeMillis,
    ): Outcome {
        val start = nowMs()
        bridge.drainEvents(0, BACKLOG_MAX) // discard the pre-gesture backlog

        var relevantCount = 0
        var meaningfulCount = 0

        fun tally(events: List<com.aura.mcp.bridge.DeviceEvent>): Int {
            val relevant = events.filter { it.packageName !in config.ignoredPackages }
            relevantCount += relevant.size
            meaningfulCount += relevant.count { it.changeClass != ScreenActivity.Class.AMBIENT }
            return relevant.size
        }

        // `already` lets a caller hand back a snapshot it just took, so the common
        // paths never read the tree twice.
        suspend fun finish(
            settled: Boolean,
            already: Pair<String, ScreenSignature.Signature>? = null,
        ): Outcome {
            val (payload, sig) = already ?: run {
                val snap = bridge.snapshot()
                val p = if (snap.ok) snap.payloadJson else "{}"
                p to ScreenSignature.of(p)
            }
            // A blind tree cannot answer "did it change" — it stays constant while a
            // WebView or game surface moves freely. Degrade to the old event-presence
            // rule there and SAY SO, rather than reporting a confident false.
            // No baseline (first action of a session, before anything looked at the
            // screen) is as unanswerable as a blind tree — we have nothing to compare
            // against. Say so rather than reporting a confident "changed".
            val blind = sig.treeBlind || preSignature == null || preSignature.treeBlind
            val changed = if (blind) relevantCount > 0 else !sig.sameContentAs(preSignature)
            return Outcome(
                settled = settled,
                screenChanged = changed,
                eventCount = relevantCount,
                meaningfulEventCount = meaningfulCount,
                elapsedMs = nowMs() - start,
                signature = sig,
                payloadJson = payload,
                degradedToEvents = blind,
            )
        }

        // ── Exit 1: quiet start. Nothing relevant arrived in the probe window. ──
        var sawRelevant = false
        while (nowMs() - start < config.noChangeProbeMs) {
            val remaining = config.noChangeProbeMs - (nowMs() - start)
            val events = bridge.drainEvents(remaining, 1)
            if (events.isEmpty()) break
            if (tally(events) > 0) {
                sawRelevant = true
                break
            }
        }
        // Even with no events we still hash: a screen can change without emitting
        // anything we were listening for, and reporting a false "unchanged" would
        // hand the agent stale som_ids.
        if (!sawRelevant) return finish(settled = true)

        // ── Exits 2 and 3: events are flowing. Wait for quiescence, but give up on
        // waiting as soon as the screen proves it has stopped changing. ──
        var lastSample: ScreenSignature.Signature? = null
        var lastPayload: String? = null
        while (nowMs() - start < config.maxMs) {
            val window = minOf(config.quietMs, config.maxMs - (nowMs() - start))
            if (window <= 0) break
            val burst = bridge.drainEvents(window, BURST_MAX)
            if (tally(burst) == 0) return finish(settled = true) // Exit 2

            // Still noisy. Sample the signature; two identical consecutive samples
            // mean the noise is an animator, not a transition still in progress.
            val snap = bridge.snapshot()
            val payload = if (snap.ok) snap.payloadJson else "{}"
            val sample = ScreenSignature.of(payload)
            if (!sample.treeBlind && sample.sameContentAs(lastSample)) {
                return finish(settled = true, already = payload to sample) // Exit 3
            }
            lastSample = sample
            lastPayload = payload
        }
        return finish(settled = false, already = lastPayload?.let { it to lastSample!! })
    }
}
