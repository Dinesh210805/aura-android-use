package com.aura.aura_ui.agent.mcpbridge

import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import com.aura.aura_ui.agent.mcpbridge.hooks.PostToolFailureHook
import com.aura.aura_ui.agent.mcpbridge.hooks.PostToolHook
import com.aura.aura_ui.agent.mcpbridge.hooks.PreToolDecision
import com.aura.aura_ui.agent.mcpbridge.hooks.PreToolHook
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.aura.mcp.tools.SystemIntentPlanner

/**
 * Phase 1 reliability core — the runtime guard that sits at the single tool-call
 * chokepoint ([McpTool.execute]) and turns three of the agent's most common
 * degenerate behaviours from prompt *suggestions* into hard, self-correcting
 * invariants. Grounded in `docs/agent-learnings.md` Part IV (real device traces).
 *
 * One instance is shared across every tool in a run's registry (see
 * [buildMcpToolRegistry]); state is per-run. A ~17B free-tier model ignores the
 * system prompt's doctrine often enough that enforcement has to live below the
 * model, where it cannot be "forgotten".
 *
 * Three guards, all expressed as "should this call proceed, given the screen?":
 *
 *  - **1a perceive-before-act.** A coordinate-targeted gesture
 *    ([GESTURE_REQUIRES_GROUNDING]) on a screen not perceived since it last
 *    changed is refused — that is the blind-tap → miss → retry cascade that
 *    caused the worst logged run (15 LLM calls for a 5-step task).
 *  - **1c loop / stuck detection.** The *same* gesture landing on the *same*
 *    screen signature ≥ [MAX_SAME_SCREEN_ACTION] times means the action does
 *    nothing — refuse it and push the model toward a different approach. Because
 *    1a forces a perception between gestures, we can hash each perception's
 *    element text and only flag a repeat when the screen genuinely did not
 *    change — so legitimate repeats (e.g. tapping "+" to increment) are NOT
 *    flagged, because they change the screen and therefore its signature.
 *  - **1e faithful completion.** `end_session` while the screen is stale (a
 *    gesture happened with no confirming perception) is refused: confirm the
 *    goal before claiming success. Pure-conversation tasks that never touched
 *    the screen are unaffected, and the block is capped so the agent can never
 *    deadlock against it.
 *
 * A blocked call returns a normal (error) tool result the model reads and
 * re-plans from — it is never a thrown exception, so the Koog loop continues.
 */
internal class ActionGuard : PreToolHook, PostToolHook, PostToolFailureHook {

    private val mutex = Mutex()

    /** Has the current screen been perceived since it last changed? Starts false. */
    private var groundedSinceLastChange = false

    /**
     * Did the latest screen change carry a settled `post_action_observation`?
     * E2 bundles a post-state summary (foreground_app, top_labels…) onto every
     * screen-mutating WRITE tool — that IS verification for 1e purposes, so
     * finishing on it must not force a redundant perceive_screen (the WhatsApp
     * trace: doctrine said "read the observation", 1e said "perceive anyway",
     * costing +2 LLM calls per run). It is a summary, not a som map, so it
     * deliberately does NOT ground som-targeted gestures (1a unchanged).
     */
    private var observedSinceLastChange = false

    /** True once any screen-changing tool has succeeded — gates 1e for info-only tasks. */
    private var anyScreenChange = false

    /** Hash of the most recently perceived screen's element text (1c screen signature). */
    private var lastScreenSignature = 0

    /** Recent executed gesture loop-keys (action @ screen). Bounded window. */
    private val recentActionKeys = ArrayDeque<String>()

    /** How many times end_session has been blocked this run (deadlock cap for 1e). */
    private var finishBlocks = 0

    /**
     * Tools that already reported a gap only the **user** can close, with the hint they gave.
     *
     * A missing Tavily key or a disabled AURA keyboard is not transient: no sequence of tool
     * calls acquires one. Left un-tracked, the model's default reaction to an error — retry —
     * repeats a call that cannot succeed, which is the same shape as the control-lock finding
     * (five identical calls into a refusal that read as temporary). The second attempt is refused
     * with the tool's own next action, so the turn is spent telling the user instead.
     *
     * Agent-fixable gaps are deliberately NOT tracked: `request_screen_capture_permission` exists
     * precisely so `get_screenshot` can be retried, and blocking the retry would break the repair
     * path the hint just recommended.
     */
    private val userFixableGaps = mutableMapOf<String, String>()

    /** Run-level counters surfaced for logging / the eval trace. */
    @Volatile var blindBlocks: Int = 0
        private set
    @Volatile var loopBlocks: Int = 0
        private set

    /**
     * Returns a model-readable reason to refuse [toolName] with [argsJson], or null
     * to allow it. Checks perceive-before-act (1a), loop detection (1c), and
     * verify-before-finish (1e), in that order.
     */
    suspend fun blockReasonFor(toolName: String, argsJson: String): String? = mutex.withLock {
        // 1f — this tool already said it is missing something only the user can supply. Calling
        // it again cannot change that; the useful move is to say so out loud. Checked first
        // because it is the cheapest and most certain verdict of the four.
        userFixableGaps[toolName]?.let { hint ->
            return "Blocked: $toolName already said it cannot work until the user sets something " +
                "up, so it will fail the same way again. Tell the user what is needed, then carry " +
                "on another way or finish: $hint"
        }
        // 1a — coordinate-targeted gesture on an unperceived screen.
        if (toolName in GESTURE_REQUIRES_GROUNDING && !groundedSinceLastChange) {
            blindBlocks++
            return PERCEIVE_FIRST_MESSAGE
        }
        // 1c — same gesture on the same screen, repeated past the threshold.
        if (toolName in GESTURE_REQUIRES_GROUNDING) {
            val key = loopKey(toolName, argsJson)
            if (recentActionKeys.count { it == key } >= MAX_SAME_SCREEN_ACTION) {
                loopBlocks++
                return LOOP_MESSAGE
            }
        }
        // 1e — finishing on a stale screen after having acted. A post-action
        // observation on the latest change counts as confirmation (E2).
        if (toolName == "end_session" && anyScreenChange &&
            !groundedSinceLastChange && !observedSinceLastChange &&
            finishBlocks < MAX_FINISH_BLOCKS
        ) {
            finishBlocks++
            return CONFIRM_BEFORE_FINISH_MESSAGE
        }
        null
    }

    /**
     * Update grounding/loop state after a tool ran. Only successful calls move
     * state. [screenText] is the tool's text result, used to fingerprint the
     * screen after a grounding (perception) call.
     */
    suspend fun recordAfter(toolName: String, argsJson: String, success: Boolean, screenText: String) {
        if (!success) return
        // A SKIP_UI system_intent (set_alarm/set_timer) moves no pixels, so it belongs with
        // [NON_VISUAL_TOOLS] rather than with the activity-launching half of its own tool — and
        // that branch is a deliberate no-op, so returning here IS that branch. It cannot be a
        // list entry because the classification lives in the ARGS, and [ActionGuardListsTest]
        // rightly insists every listed name is a real tool.
        //
        // Getting this wrong is worse than it looks: the server no longer observes these, so the
        // 1e finish gate would never see an observation and "set an alarm, then finish" would
        // deadlock against a look at a screen that never changed.
        if (isSilentIntent(toolName, argsJson)) return
        mutex.withLock {
            when (toolName) {
                in GROUNDING_TOOLS -> {
                    groundedSinceLastChange = true
                    lastScreenSignature = screenSignature(screenText)
                }
                in NON_VISUAL_TOOLS -> {
                    // Audio/notification-shade tools change no pixels: they must
                    // not stale grounding or arm the finish gate ("pause music"
                    // then end_session needs no perceive_screen).
                }
                in SCREEN_CHANGING_TOOLS -> {
                    groundedSinceLastChange = false
                    observedSinceLastChange = screenText.contains(OBSERVATION_MARKER)
                    anyScreenChange = true
                    if (toolName in GESTURE_REQUIRES_GROUNDING) pushActionKey(loopKey(toolName, argsJson))
                }
                // read-only / info tools: state unchanged
            }
        }
    }

    /**
     * True when this call is a `system_intent` whose action shows nothing
     * ([SystemIntentPlanner.SILENT_ACTIONS]). The action lives in the args, so the classification
     * cannot be made from the tool name the way every other rule here is.
     */
    private fun isSilentIntent(toolName: String, argsJson: String): Boolean {
        if (toolName != "system_intent") return false
        val action = runCatching {
            Json.parseToJsonElement(argsJson).jsonObject["action"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return SystemIntentPlanner.isSilent(action)
    }

    /**
     * Fingerprint a perceived screen using only its **stable** parts.
     *
     * Hashing the whole perceive_screen payload was wrong: the same screen
     * re-perceived produces different `omniparser_count`, different red-box
     * elements and different `timing_ms` every time, because the CV tier and
     * YOLO output vary run-to-run. Three views of one Amazon home screen
     * therefore produced three different signatures, so the identical
     * `tap som_id=24` that kept re-opening the address modal was never
     * recognised as a loop and the agent burned its whole budget on it.
     *
     * Only the accessibility `ui_tree` elements are deterministic, so the
     * signature keys on their label + geometry. `som_id` is excluded on
     * purpose: numbering shifts when a different count of CV elements is
     * interleaved, which would reintroduce the same instability.
     *
     * Falls back to the raw text hash when the payload has no parseable
     * ui_tree elements (other grounding tools, plain-text results, tests).
     */
    private fun screenSignature(screenText: String): Int {
        val stable = runCatching { stableTreeDigest(screenText) }.getOrNull()
        // read_screen's grid is text, not JSON, so it lands here. Its `SCREEN …` header carries
        // IDLE/BUSY, which flips between two looks at one still screen — hashed whole, the same
        // screen never matched and no loop was ever caught (ScreenPayload.stablePart documents
        // this; nothing applied it). Since A6 reads the screen after every gesture, this hash IS
        // the loop key's screen half.
        return stable?.hashCode()
            ?: screenText.lineSequence().filterNot { it.startsWith("SCREEN ") }.joinToString("\n").hashCode()
    }

    /**
     * Geometry + name from ONE element entry, whichever grounding tool produced it.
     *
     * There are two positional shapes in play since 2026-08-18, and the difference is
     * not cosmetic — reading a `read_screen` entry with `perceive_screen`'s offsets
     * silently yields `[x1, y1, x2]`, a digest that ignores the label entirely and
     * calls two differently-labelled rows at the same position one screen:
     *
     *  - `perceive_screen` → `[center_x, center_y, name, flags]`   (4 fields)
     *  - `read_screen`     → `[x1, y1, x2, y2, label, flags, cls]` (7 fields)
     *
     * Flags are dropped from both: `perceive_screen`'s carry `f` (focused), which flips
     * on an unchanged screen. Class is dropped too — it is a function of the layout the
     * geometry already captures.
     */
    private fun stableFieldsOf(entry: JsonArray): String {
        fun at(i: Int) = (entry.getOrNull(i) as? JsonPrimitive)?.content.orEmpty()
        // The bounds count is what separates the shapes: 4 numbers means read_screen.
        return if (entry.size >= 7) {
            listOf(at(0), at(1), at(2), at(3), at(4)).joinToString("|")
        } else {
            listOf(at(0), at(1), at(2)).joinToString("|")
        }
    }

    /**
     * The deterministic half of a `perceive_screen` result, as a comparable string.
     *
     * Two shape facts this depends on, both owned by `PerceiveScreenTool`:
     *
     *  1. The result is SEVERAL content blocks — an optional loading warning, a
     *     one-line human label, then the JSON payload — which [onPostTool] joins
     *     with newlines. So the text handed to us is NOT a JSON document, and
     *     parsing it whole always throws. We take the last line that is a JSON
     *     object instead. This is what silently broke: the throw was swallowed and
     *     every perceive fell through to `screenText.hashCode()` — the unstable
     *     whole-payload hash this function exists to avoid. Worse, that human
     *     label quotes the caller's `description`, so the SAME screen hashed
     *     differently just because the agent phrased its intent differently.
     *  2. Elements arrive as `e`, a positional array `[cx, cy, name, flags]`, with
     *     every ui_tree element FIRST (`mergeElements` returns `tree + extra`) and
     *     `ui_tree_count` giving the split. There is no per-element `source` field
     *     any more, so the count IS how we tell fact from vision guess.
     *
     * Only geometry + name are folded in. `flags` is deliberately excluded: it
     * carries `f` (focused), which flips on an unchanged screen and would make
     * every re-perceive look like a new one — reintroducing the exact instability
     * described above.
     *
     * Returns null on a vision-only screen (`ui_tree_count == 0`): nothing there is
     * deterministic, so claiming a stable signature would be a lie.
     */
    private fun stableTreeDigest(screenText: String): String? {
        val payload = lastJsonObject(screenText) ?: return null
        val elements = payload["e"] as? JsonArray ?: return null
        val treeCount = (payload["ui_tree_count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        if (treeCount <= 0) return null

        return elements.take(treeCount)
            .mapNotNull { it as? JsonArray }
            .map { entry -> stableFieldsOf(entry) }
            .sorted() // element order is not guaranteed stable across perceives
            .joinToString(";")
            .ifBlank { null }
    }

    /**
     * The last line of [text] that parses as a JSON object — the payload block of a
     * multi-part tool result. Scanning from the end rather than the start because
     * the payload is always emitted last, after any prose.
     */
    private fun lastJsonObject(text: String): JsonObject? = text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("{") && it.endsWith("}") }
        .lastOrNull()
        ?.let { line -> runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() }

    /** action @ current-screen signature — the unit of "have I done this here before?". */
    private fun loopKey(toolName: String, argsJson: String): String =
        "$toolName:$argsJson@$lastScreenSignature"

    private fun pushActionKey(key: String) {
        recentActionKeys.addLast(key)
        while (recentActionKeys.size > ACTION_WINDOW) recentActionKeys.removeFirst()
    }

    // ── Hook adapters ─────────────────────────────────────────────────────────
    // ActionGuard is one PreToolHook + PostToolHook in the ToolHookChain. These
    // adapters delegate to the unchanged blockReasonFor/recordAfter logic above,
    // so all behaviour (and ActionGuardTest) is preserved.

    override suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext): PreToolDecision =
        blockReasonFor(toolName, args.toString())
            ?.let { PreToolDecision.Deny(it) }
            ?: PreToolDecision.Proceed

    override suspend fun onPostTool(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
        val screenText = result.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }
        recordAfter(toolName, args.toString(), success = result.isError != true, screenText = screenText)
    }

    /**
     * E6 failure lane — where a resource gap actually arrives.
     *
     * This must live here rather than in [onPostTool]. [ToolHookChain] routes an `isError` result
     * to this method *instead of* the success lane, and a `resource_required` refusal is always an
     * error result — so reading the gap in [onPostTool] made rule 1f unreachable, which is exactly
     * how it was first written and why `ActionGuardResourceGapTest` alone could not catch it: that
     * test calls [noteUserFixableGap] directly and never exercises the wiring.
     */
    override suspend fun onPostToolFailure(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
        ResourceGap.from(result)
            ?.takeIf { it.fixableBy == ResourceGap.Fixer.USER }
            ?.let { gap -> noteUserFixableGap(toolName, gap.hint) }
    }

    /** Remember that [toolName] needs something only the user can supply — see [userFixableGaps]. */
    internal suspend fun noteUserFixableGap(toolName: String, hint: String) = mutex.withLock {
        userFixableGaps[toolName] = hint
    }

    companion object {
        /** Perception tools that ground the screen with targetable element info. */
        val GROUNDING_TOOLS = setOf("perceive_screen", "get_screenshot", "read_screen")

        /**
         * Gestures that target a specific on-screen element *by coordinate* — these
         * are the ones meaningless on an unperceived screen, so they are gated.
         *
         * `type_text` is deliberately NOT here: it types into the field a prior tap
         * focused, it carries no coordinates, and that prior tap was itself gated.
         * Gating it forced a re-perceive between every tap-to-focus and its type,
         * which livelocked the canonical "tap search box → type query" flow (the tap
         * marks the screen stale, so the type was always blocked). The tap is the
         * blind action worth refusing; the type that follows it is not.
         */
        val GESTURE_REQUIRES_GROUNDING = setOf(
            "tap", "double_tap", "long_press", "swipe",
        )

        /**
         * Tools that mutate screen/device state, marking the perceived screen stale.
         */
        val SCREEN_CHANGING_TOOLS = setOf(
            "tap", "double_tap", "long_press", "swipe", "type_text",
            "scroll_up", "scroll_down", "scroll_left", "scroll_right",
            "scroll_to",
            "launch_app", "open_deeplink", "open_file", "open_recent_apps",
            "press_back", "press_enter", "press_home",
            "wait_for", "request_screen_capture_permission",
            "connect_device",
            // Assistant plane — system_intent launches real activities and a
            // notification content action can open its app.
            "system_intent", "notification_action",
        )

        /**
         * Tools that change device state but no pixels (audio, media transport,
         * status-bar shade). They neither stale grounding nor arm the 1e finish
         * gate — demanding a perceive_screen after "pause the music" before
         * end_session was pure waste. Checked BEFORE [SCREEN_CHANGING_TOOLS].
         */
        val NON_VISUAL_TOOLS = setOf(
            "mute", "volume_up", "volume_down", "media_control", "dismiss_notification",
            // Browser — the scratch engine is an offscreen WebView, so nothing the
            // user can see changes. Treating these as screen-changing would stale
            // the agent's grounding on every page read and force a pointless
            // perceive_screen before it could finish ("summarise this article"
            // would end up screenshotting AURA's own UI to prove it was done).
            "browser_open", "browser_read", "browser_act", "browser_close",
            "browser_find", "browser_wait", "browser_screenshot", "browser_extract",
            "browser_upload",
            "browser_tabs",
            "browser_handoff",
            // NOTE: a SKIP_UI system_intent (set_alarm/set_timer) belongs here too but cannot be
            // listed — it is one action of a tool, not a tool. See [isSilentIntent].
        )

        /**
         * Marker for an E2 post-action observation riding on a WRITE tool's
         * result text (server appends `{"post_action_observation":{…}}`).
         * String-matched client-side; the JSON key is the cross-module contract.
         */
        const val OBSERVATION_MARKER = "\"post_action_observation\""

        /** Refuse a repeated gesture once it has already run this many times on the same screen. */
        const val MAX_SAME_SCREEN_ACTION = 2

        /** How many recent executed gesture keys to remember for loop detection. */
        const val ACTION_WINDOW = 8

        /** Never block end_session more than this many times — avoids a finish deadlock. */
        const val MAX_FINISH_BLOCKS = 2

        const val PERCEIVE_FIRST_MESSAGE =
            "Blocked: the screen changed since you last looked, so that som_id may now point at " +
                "something else. Use the screen from your last action's result, or call " +
                "read_screen, then target by som_id."

        const val LOOP_MESSAGE =
            "Blocked: you already did exactly this on this screen and nothing changed. Try " +
                "something different: scroll to reveal the target, go back and take another " +
                "path, pick another element, or ask_user."

        const val CONFIRM_BEFORE_FINISH_MESSAGE =
            "Not yet: your last action changed the screen and you have not seen the result. " +
                "Call read_screen, check the goal really is done, then end. If it is not, end " +
                "with outcome failure and say what happened."
    }
}
