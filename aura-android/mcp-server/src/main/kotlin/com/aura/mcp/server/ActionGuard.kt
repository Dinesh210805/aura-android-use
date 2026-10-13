package com.aura.mcp.server

import com.aura.mcp.tools.SystemIntentPlanner
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Refuses tool calls that real device traces show are wasted turns: acting on an unseen screen,
 * repeating a gesture that did nothing, finishing without looking, and retrying a tool that is
 * waiting on the user.
 *
 * Runs at the `scopedTool` chokepoint, so every agent gets it: the on-device agent and every PC
 * client. Models ignore prompt doctrine often enough that these rules have to live below them.
 *
 * Rules, checked in this order by [refusalFor]:
 *  - **user_fixable**: the tool already returned `resource_required` with `fixable_by=user`.
 *  - **perceive_first**: a gesture in [GESTURE_REQUIRES_GROUNDING] on a screen not read since it
 *    last changed.
 *  - **loop**: the same gesture with the same args on the same screen signature, already run
 *    [MAX_SAME_SCREEN_ACTION] times. A repeat that changes the screen ("+" to increment) changes
 *    the signature, so it is not flagged.
 *  - **confirm_before_finish**: `end_session` after the screen changed with no look and no
 *    `post_action_observation`. Capped at [MAX_FINISH_BLOCKS] so it can never deadlock.
 *
 * - Contract: one instance per server; state is about the one physical screen, so it is shared by
 *   every client multiplexed onto that server. Thread-safe. [reset] starts a new task.
 * - Fails: open. An unparseable result only weakens loop detection (raw-text signature).
 * - Change together: the tool-name sets below must track `McpToolScopes` (`ActionGuardListsTest`).
 */
class ActionGuard {

    /** A refused call: [kind] is the stable error suffix, [message] is what the model reads. */
    data class Refusal(val kind: String, val message: String)

    private val mutex = Mutex()

    /** Has the current screen been read since it last changed? */
    private var groundedSinceLastChange = false

    /**
     * Did the latest change carry a settled `post_action_observation`? That counts as having seen
     * the result for the finish rule. It does not ground som-targeted gestures: it is a summary,
     * not a som map.
     */
    private var observedSinceLastChange = false

    /** True once any screen-changing tool succeeded; info-only tasks never arm the finish rule. */
    private var anyScreenChange = false

    /** Stable fingerprint of the last read screen; the screen half of a loop key. */
    private var lastScreenSignature = 0

    private val recentActionKeys = ArrayDeque<String>()

    private var finishBlocks = 0

    /** Tool name → the hint it gave when it reported a gap only the user can close. */
    private val userFixableGaps = mutableMapOf<String, String>()

    /** Counters for the run log. */
    @Volatile var blindBlocks: Int = 0
        private set
    @Volatile var loopBlocks: Int = 0
        private set

    /** The reason to refuse [toolName] with [argsJson], or null to let it run. */
    suspend fun refusalFor(toolName: String, argsJson: String): Refusal? = mutex.withLock {
        userFixableGaps[toolName]?.let { hint ->
            return Refusal(
                "user_fixable",
                "Blocked: $toolName already said it cannot work until the user sets something up, " +
                    "so it will fail the same way again. Tell the user what is needed, then carry on " +
                    "another way or finish: $hint",
            )
        }
        if (toolName in GESTURE_REQUIRES_GROUNDING && !groundedSinceLastChange) {
            blindBlocks++
            return Refusal("perceive_first", PERCEIVE_FIRST_MESSAGE)
        }
        if (toolName in GESTURE_REQUIRES_GROUNDING) {
            val key = loopKey(toolName, argsJson)
            if (recentActionKeys.count { it == key } >= MAX_SAME_SCREEN_ACTION) {
                loopBlocks++
                return Refusal("loop", LOOP_MESSAGE)
            }
        }
        if (toolName == "end_session" && anyScreenChange &&
            !groundedSinceLastChange && !observedSinceLastChange &&
            finishBlocks < MAX_FINISH_BLOCKS
        ) {
            finishBlocks++
            return Refusal("confirm_before_finish", CONFIRM_BEFORE_FINISH_MESSAGE)
        }
        null
    }

    /** Update state from a call that ran: success moves screen state, an error may record a gap. */
    suspend fun recordAfter(toolName: String, argsJson: String, result: CallToolResult) {
        val texts = result.content.filterIsInstance<TextContent>().map { it.text }
        if (result.isError == true) {
            userFixableHint(texts)?.let { hint -> mutex.withLock { userFixableGaps[toolName] = hint } }
            return
        }
        recordAfter(toolName, argsJson, success = true, screenText = texts.joinToString("\n"))
    }

    /** Text-level form of [recordAfter]; [screenText] is the result's text parts joined by newlines. */
    internal suspend fun recordAfter(toolName: String, argsJson: String, success: Boolean, screenText: String) {
        if (!success) return
        // A silent system_intent (alarm/timer) moves no pixels. The action is in the args, so it
        // cannot be listed in NON_VISUAL_TOOLS by name.
        if (isSilentIntent(toolName, argsJson)) return
        mutex.withLock {
            when (toolName) {
                in GROUNDING_TOOLS -> {
                    groundedSinceLastChange = true
                    lastScreenSignature = screenSignature(screenText)
                }
                in NON_VISUAL_TOOLS -> Unit
                in SCREEN_CHANGING_TOOLS -> {
                    // A settled observation that says nothing changed keeps the last look valid:
                    // re-reading an identical screen before the next tap is a wasted turn.
                    if (!provedUnchanged(screenText)) groundedSinceLastChange = false
                    observedSinceLastChange = screenText.contains(OBSERVATION_MARKER)
                    anyScreenChange = true
                    if (toolName in GESTURE_REQUIRES_GROUNDING) pushActionKey(loopKey(toolName, argsJson))
                }
                else -> Unit
            }
        }
    }

    /** Forget everything; the next call starts a new task. */
    suspend fun reset() = mutex.withLock {
        groundedSinceLastChange = false
        observedSinceLastChange = false
        anyScreenChange = false
        lastScreenSignature = 0
        recentActionKeys.clear()
        finishBlocks = 0
        userFixableGaps.clear()
    }

    internal suspend fun noteUserFixableGap(toolName: String, hint: String) = mutex.withLock {
        userFixableGaps[toolName] = hint
    }

    /**
     * The hint from a `resource_required` refusal the user must fix, or null.
     * An unknown `fixable_by` counts as the user's: guessing "agent" invites a retry loop.
     */
    private fun userFixableHint(texts: List<String>): String? = texts.asSequence()
        .mapNotNull { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        .firstOrNull { it["error"]?.jsonPrimitive?.contentOrNull == ResourceRequired.ERROR_CODE }
        ?.takeIf { it["fixable_by"]?.jsonPrimitive?.contentOrNull != ResourceRequired.Fixer.AGENT.id }
        ?.let { it["hint"]?.jsonPrimitive?.contentOrNull.orEmpty() }

    /** True when the result's observation says, with normal confidence, that nothing changed. */
    private fun provedUnchanged(screenText: String): Boolean {
        val obs = screenText.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && OBSERVATION_MARKER in it }
            .lastOrNull()
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject["post_action_observation"] }.getOrNull() }
            as? JsonObject ?: return false
        val changed = (obs["screen_changed"] as? JsonPrimitive)?.booleanOrNull ?: return false
        val lowConfidence = obs["screen_changed_confidence"]?.jsonPrimitive?.contentOrNull == "low"
        return !changed && !lowConfidence
    }

    private fun isSilentIntent(toolName: String, argsJson: String): Boolean {
        if (toolName != "system_intent") return false
        val action = runCatching {
            Json.parseToJsonElement(argsJson).jsonObject["action"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return SystemIntentPlanner.isSilent(action)
    }

    /**
     * Fingerprint a read screen from its deterministic parts only: the accessibility elements'
     * geometry and label. CV boxes, timings and som_id numbering vary between two reads of one
     * still screen, so hashing them would never match and no loop would ever be caught.
     * `read_screen`'s text grid lands in the fallback, minus its `SCREEN` header whose IDLE/BUSY
     * flips on a still screen.
     */
    private fun screenSignature(screenText: String): Int {
        val stable = runCatching { stableTreeDigest(screenText) }.getOrNull()
        return stable?.hashCode()
            ?: screenText.lineSequence().filterNot { it.startsWith("SCREEN ") }.joinToString("\n").hashCode()
    }

    /**
     * Geometry + name from one element entry. Two shapes exist:
     * `perceive_screen` is `[center_x, center_y, name, flags]`, `read_screen` is
     * `[x1, y1, x2, y2, label, flags, cls]`. Flags are dropped (`f` focused flips on a still
     * screen), and so is class (the geometry already captures it).
     */
    private fun stableFieldsOf(entry: JsonArray): String {
        fun at(i: Int) = (entry.getOrNull(i) as? JsonPrimitive)?.content.orEmpty()
        return if (entry.size >= 7) {
            listOf(at(0), at(1), at(2), at(3), at(4)).joinToString("|")
        } else {
            listOf(at(0), at(1), at(2)).joinToString("|")
        }
    }

    /**
     * The ui_tree half of a perception payload as a comparable string, or null on a vision-only
     * screen. The payload is the last JSON line of a multi-part result; tree elements come first
     * in `e` and `ui_tree_count` gives the split (owned by `PerceiveScreenTool`).
     */
    private fun stableTreeDigest(screenText: String): String? {
        val payload = lastJsonObject(screenText) ?: return null
        val elements = payload["e"] as? JsonArray ?: return null
        val treeCount = (payload["ui_tree_count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        if (treeCount <= 0) return null
        return elements.take(treeCount)
            .mapNotNull { it as? JsonArray }
            .map { entry -> stableFieldsOf(entry) }
            .sorted()
            .joinToString(";")
            .ifBlank { null }
    }

    private fun lastJsonObject(text: String): JsonObject? = text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("{") && it.endsWith("}") }
        .lastOrNull()
        ?.let { line -> runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() }

    private fun loopKey(toolName: String, argsJson: String): String =
        "$toolName:$argsJson@$lastScreenSignature"

    private fun pushActionKey(key: String) {
        recentActionKeys.addLast(key)
        while (recentActionKeys.size > ACTION_WINDOW) recentActionKeys.removeFirst()
    }

    companion object {
        /** Tools that read the screen with targetable element info. */
        val GROUNDING_TOOLS = setOf("perceive_screen", "get_screenshot", "read_screen")

        /**
         * Gestures aimed at a specific element; meaningless on an unread screen.
         * Why `type_text` is absent: it types into the field the prior (gated) tap focused, and
         * gating it forced a re-read between "tap search box" and "type query" on every search.
         */
        val GESTURE_REQUIRES_GROUNDING = setOf("tap", "swipe")

        /** Tools that change what is on screen, making the last read stale. */
        val SCREEN_CHANGING_TOOLS = setOf(
            "tap", "swipe", "type_text", "scroll",
            "launch_app", "open_deeplink", "open_file",
            "press_key", "press_enter",
            "wait_for", "request_screen_capture_permission",
            "system_intent", "notification_action",
        )

        /**
         * Tools that change device state but no pixels: audio, media, the notification shade, and
         * the browser (an offscreen WebView). Checked before [SCREEN_CHANGING_TOOLS].
         */
        val NON_VISUAL_TOOLS = setOf(
            "volume", "media_control", "dismiss_notification",
            "browser_open", "browser_read", "browser_act", "browser_close",
            "browser_find", "browser_wait", "browser_screenshot", "browser_extract",
            "browser_upload", "browser_tabs", "browser_handoff",
        )

        /** JSON key the server appends to observed WRITE results (see [PostActionObservation]). */
        const val OBSERVATION_MARKER = "\"post_action_observation\""

        const val MAX_SAME_SCREEN_ACTION = 2
        const val ACTION_WINDOW = 8
        const val MAX_FINISH_BLOCKS = 2

        const val PERCEIVE_FIRST_MESSAGE =
            "Blocked: the screen changed since you last looked, so that som_id may now point at " +
                "something else. Use the screen from your last action's result, or call " +
                "read_screen, then target by som_id."

        const val LOOP_MESSAGE =
            "Blocked: you already did exactly this on this screen and nothing changed. Try " +
                "something different: scroll to reveal the target, go back and take another " +
                "path, pick another element, or ask the user."

        const val CONFIRM_BEFORE_FINISH_MESSAGE =
            "Not yet: your last action changed the screen and you have not seen the result. " +
                "Call read_screen, check the goal really is done, then end. If it is not, end " +
                "with outcome failure and say what happened."
    }
}
