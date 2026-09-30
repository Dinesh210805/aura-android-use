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
        "tap", "double_tap", "long_press", "swipe",
        "scroll_to", "scroll_up", "scroll_down", "scroll_left", "scroll_right",
        "type_text", "press_home", "press_back", "press_enter", "open_recent_apps",
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
        "press_home", "press_back", "open_recent_apps",
    )

    val UNOBSERVED_WRITE_TOOLS: Set<String> = setOf(
        "volume_up", "volume_down", "mute",
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
                                "inside it will be blocked. Use press_back or press_home to " +
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
                        put("hint", hintFor(toolName))
                    }
                }
            }.getOrNull() // observation must never fail the action it rides on
        }
    }

    /** Scroll tools, whose whole purpose is to bring NEW content into view. */
    private val SCROLL_TOOLS: Set<String> = setOf(
        "scroll_up", "scroll_down", "scroll_left", "scroll_right",
        "scroll_to", "swipe",
    )

    /**
     * The instruction riding on every screen-mutating result — the freshest, last, and
     * therefore highest-priority text in the whole context.
     *
     * It used to be one blanket line telling the agent not to look again after any
     * action. That is right for `tap` (the summary below already says what happened)
     * and actively wrong for a scroll, whose ENTIRE PURPOSE is to reveal content the
     * summary's few top labels cannot convey. Traces showed the agent following it
     * literally: scroll, be told not to look, conclude nothing was there, stop. This is
     * finding O-1, and it is why the two cases now get different text.
     *
     * Both branches now name `read_screen` rather than `perceive_screen`. The old
     * discouragement was largely a cost argument — a re-look meant a screenshot, a CV
     * pass and an image in context. A settled tree read costs none of that, so "do not
     * look" can go back to being advice about *redundancy* instead of about expense.
     */
    internal fun hintFor(toolName: String): String = if (toolName in SCROLL_TOOLS) {
        "Only the top labels are listed here — call read_screen before deciding the item is " +
            "not there. Earlier som_ids are stale."
    } else {
        "Screen after the action, settled. Verify the action from this — do not call wait_for " +
            "or look again just to see what happened. Earlier som_ids are stale; read_screen " +
            "gives current ones."
    }

    /**
     * Said only when an action lands on an EARLIER screen — the one signal that separates
     * "navigating" from "going in circles", which the agent cannot see from one step back.
     * A hint, not a block: coming back on purpose (press_back to a list) is normal.
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
