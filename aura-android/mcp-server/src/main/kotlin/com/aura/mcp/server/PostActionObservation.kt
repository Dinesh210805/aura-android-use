package com.aura.mcp.server

import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.tools.SystemIntentPlanner
import com.aura.mcp.tools.UiTreeHeuristics
import com.aura.mcp.tools.stringArg
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * E2 — post-action observation bundling: every screen-mutating WRITE tool result
 * carries a compact **settled post-state** block, so the model's next call
 * verifies the previous action AND plans the next one in ONE round trip. The
 * perceive-to-verify call disappears from the common path.
 *
 * The bundle is text-only (`UiTreeHeuristics.summarize()` — foreground app,
 * element count, keyboard, loading, top labels): it deliberately does NOT
 * refresh the perception cache, so som_id grounding semantics are untouched —
 * som-targeted taps still require a real `perceive_screen` (P1 staleness gate
 * enforces this server-side).
 *
 * Wired centrally in [scopedTool] via [ServerSinks.observer] — no per-tool edits,
 * and any new WRITE tool gets settle+observe by being added to [OBSERVED_TOOLS].
 * Observation failures never fail the action itself.
 */
internal interface PostActionObserver {
    suspend fun observe(toolName: String): JsonObject?
}

/** Default no-op so unit tests and non-device hosts need no wiring. */
internal object NoOpPostActionObserver : PostActionObserver {
    override suspend fun observe(toolName: String): JsonObject? = null
}

internal object PostActionObservation {

    /**
     * Whether this CALL earns an observation — the tool name, then its arguments.
     *
     * The name alone was wrong for exactly one tool. `system_intent` is eight actions, six of
     * which open an activity worth settling for, and two ([SystemIntentPlanner.SILENT_ACTIONS])
     * which carry `EXTRA_SKIP_UI` and deliberately show nothing. Observing those produced
     * `screen_changed: false` on a perfectly successful alarm — and since the tool's own hint,
     * this bundle's hint and `end_session` all tell the model to verify from the observation,
     * three instructions pointed it at a payload that says the work did not happen. Traces showed
     * the consequence: the model abandons the direct route and drives the clock app's UI instead.
     */
    fun observes(toolName: String, arguments: JsonObject?): Boolean = when {
        toolName !in OBSERVED_TOOLS -> false
        toolName == "system_intent" -> !SystemIntentPlanner.isSilent(arguments?.stringArg("action"))
        else -> true
    }

    /**
     * WRITE tools that visibly mutate the screen and therefore settle+observe.
     * Volume/mute change no pixels; end_session/perceive/verify are not writes.
     */
    val OBSERVED_TOOLS: Set<String> = setOf(
        "tap", "swipe", "scroll",
        "type_text", "press_key", "press_enter",
        "launch_app", "open_deeplink",
        // system_intent launches real activities (dialer/composer/share sheet/
        // maps) — the observation confirms the confirmation UI actually opened.
        "system_intent",
        // open_file launches the file's viewer app — same shape as open_deeplink.
        "open_file",
    )

    /**
     * T4 — WRITE tools deliberately NOT observed, with the reason pinned here. The
     * snapshot test asserts WRITE = OBSERVED_TOOLS ∪ UNOBSERVED_WRITE_TOOLS exactly,
     * so a new WRITE tool must be placed in one of the two on purpose.
     *  - Audio-only: volume/mute change no pixels — nothing to settle or summarize.
     *  - media_control: transport control happens inside the playing app's
     *    session, usually with the screen elsewhere/off — nothing to observe.
     *  - notification_action / dismiss_notification: act on the status bar, not
     *    the visible screen (a direct reply never opens the app), so a settle
     *    wait would just burn ~1.5 s per call for an unchanged screen.
     */
    /**
     * Observed tools whose effect can take a while to become visible — process start,
     * activity resolution, an IPC round trip. A tap on an already-running screen shows
     * up in tens of ms; a cold app launch does not. Everything not listed here is
     * treated as fast-starting.
     */
    val SLOW_TO_START: Set<String> = setOf(
        "launch_app", "open_deeplink", "open_file", "system_intent",
        "press_key",
    )

    val UNOBSERVED_WRITE_TOOLS: Set<String> = setOf(
        "volume",
        "media_control", "notification_action", "dismiss_notification",
        // Browser — these already return the page they produced, which IS the
        // post-action observation and a far better one. A device-screen settle
        // would describe AURA's own UI (the browser surface is offscreen), i.e.
        // burn ~1.5 s to report something unrelated to what the tool just did.
        "browser_open", "browser_act", "browser_close", "browser_find", "browser_wait",
        "browser_upload",
        "browser_tabs",
        "browser_handoff",
    )

    fun observer(
        bridge: UiTreeBridge,
        config: ScreenSettle.Config,
        nowMs: () -> Long = System::currentTimeMillis,
        /**
         * The screen as of the last look, read BEFORE this action and advanced to the
         * post-settle screen after it. Rolling, so consecutive gestures in a chain each
         * compare against the step before them rather than against a stale perceive.
         */
        lastSeen: com.aura.mcp.cache.LastSeenScreen = com.aura.mcp.cache.LastSeenScreen(),
    ): PostActionObserver = object : PostActionObserver {
        override suspend fun observe(toolName: String): JsonObject? {
            if (toolName !in OBSERVED_TOOLS) return null
            return runCatching {
                // Actions that start slowly keep the longer probe: for them a quiet
                // first 250 ms means "hasn't begun", not "did nothing", and the
                // signature cannot tell those apart.
                val cfg = if (toolName in SLOW_TO_START) {
                    config.copy(noChangeProbeMs = config.slowStartProbeMs)
                } else {
                    config
                }
                val settle = ScreenSettle.await(bridge, cfg, lastSeen.get(), nowMs)
                // Asked before set(): once recorded, the landed screen is "the current one".
                val seenAgo = lastSeen.actionsSinceSeen(settle.signature)
                // Advance the baseline even on a low-confidence verdict: the next
                // action still deserves the freshest reference we have.
                lastSeen.set(settle.signature)
                // Settle already read the tree to reach its verdict — reuse that
                // snapshot instead of taking a second one a few ms later, which also
                // guarantees the summary describes the same instant as the verdict.
                val summary = UiTreeHeuristics.summarize(settle.payloadJson)
                // GP5 — the gesture may have LANDED on a sensitive screen (e.g.
                // icon-tap from the launcher). Never bundle that screen's text.
                val sensitive = SensitivePolicy.screenPackage(summary.foregroundApp) is
                    SensitivePolicy.Decision.Block
                if (sensitive) {
                    buildJsonObject {
                        put("settled", settle.settled)
                        put("settle_ms", settle.elapsedMs)
                        put("screen_changed", settle.screenChanged)
                        put("foreground_app", summary.foregroundApp)
                        put("sensitive_foreground", true)
                        put(
                            "hint",
                            "The foreground app is a banking/payment/authenticator app — " +
                                "its screen content is withheld and further taps or reads " +
                                "inside it will be blocked. Use press_key back or home to " +
                                "leave, and ask the user to complete this step themselves.",
                        )
                    }
                } else {
                    buildJsonObject {
                        put("settled", settle.settled)
                        put("settle_ms", settle.elapsedMs)
                        // Signature comparison, not event presence: a recomposition
                        // storm, marquee or spinner no longer reads as "it worked".
                        put("screen_changed", settle.screenChanged)
                        // Only when the tree could not describe the screen and the
                        // verdict fell back to raw event activity (WebView, games).
                        if (settle.degradedToEvents) put("screen_changed_confidence", "low")
                        put("foreground_app", summary.foregroundApp)
                        put("element_count", summary.elementCount)
                        put("keyboard_visible", summary.keyboardVisible)
                        put("loading_indicator_present", summary.loadingIndicatorPresent)
                        putJsonArray("top_labels") {
                            summary.topLabels.forEach { add(JsonPrimitive(it)) }
                        }
                        seenAgo?.let { put("seen_before", seenBeforeNote(it)) }
                        put(
                            "hint",
                            hintFor(
                                toolName,
                                changed = settle.screenChanged || settle.degradedToEvents,
                                loading = summary.loadingIndicatorPresent,
                            ),
                        )
                    }
                }
            }.getOrNull() // observation must never fail the action it rides on
        }
    }

    /** Scroll tools, whose whole purpose is to bring NEW content into view. */
    private val SCROLL_TOOLS: Set<String> = setOf(
        "scroll", "swipe",
    )

    /**
     * What to do next, riding on every screen-mutating result: the freshest text in the
     * agent's context, and the one channel every MCP client is guaranteed to show.
     *
     * - Nothing changed (confident verdict): don't repeat the action; the last look and its
     *   som_ids are still valid ([ActionGuard] agrees, so no re-read is forced).
     * - Scroll: look again — the summary's few top labels cannot show what scrolled in.
     * - Still loading: `wait_for` is justified now.
     * - Otherwise: verify from the summary; don't look again just to check.
     *
     * Why `read_screen`, never `perceive_screen`: a settled tree read costs no screenshot
     * or CV pass, so it is the right re-look to point at.
     * Change together: `ActionGuard.provedUnchanged` must agree on what "unchanged" means.
     */
    internal fun hintFor(toolName: String, changed: Boolean = true, loading: Boolean = false): String =
        when {
            // A confident "nothing changed" means the action did nothing, and the last look is
            // still the screen. ActionGuard keeps that look valid, so the som_ids still work.
            !changed && toolName in SCROLL_TOOLS ->
                "Nothing moved: this list ends in that direction. Earlier som_ids are still valid; " +
                    "read_screen only if you need more than the labels above."
            !changed ->
                "Nothing changed on screen — do not repeat this action. If the target has flag d " +
                    "it is disabled; otherwise pick another element, scroll to it, or try tap with style long. " +
                    "Earlier som_ids are still valid; no need to read_screen again."
            toolName in SCROLL_TOOLS ->
                "Only the top labels are listed here — call read_screen before deciding the item is " +
                    "not there. Earlier som_ids are stale."
            loading ->
                "Screen after the action, but a loading indicator is still visible: wait_for the " +
                    "element you need before acting on it. Earlier som_ids are stale; read_screen " +
                    "gives current ones."
            else ->
                "Screen after the action, settled. Verify the action from this — do not call wait_for " +
                    "or look again just to see what happened. Earlier som_ids are stale; read_screen " +
                    "gives current ones."
        }

    /**
     * Said only when an action lands on an EARLIER screen — the one signal that separates
     * "navigating" from "going in circles", which the agent cannot see from one step back.
     * A hint, not a block: coming back on purpose (press_key back to a list) is normal.
     */
    internal fun seenBeforeNote(actionsAgo: Long): String {
        val ago = if (actionsAgo == 1L) "1 action ago" else "$actionsAgo actions ago"
        return "Same screen as $ago. If you did not mean to come back here, you may be going " +
            "in circles — try a different path."
    }

    /** Appends the bundle as a second text block, preserving the tool's own result. */
    fun appendTo(result: CallToolResult, observation: JsonObject): CallToolResult =
        CallToolResult(
            content = result.content + TextContent(
                buildJsonObject { put("post_action_observation", observation) }.toString(),
            ),
            isError = result.isError,
        )
}
