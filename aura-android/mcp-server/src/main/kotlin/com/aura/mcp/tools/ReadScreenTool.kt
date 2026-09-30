package com.aura.mcp.tools

import com.aura.mcp.bridge.AnnotationGroup
import com.aura.mcp.bridge.CaptureResult
import com.aura.mcp.bridge.PerceptionBridge
import com.aura.mcp.bridge.ScreenshotBridge
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.cache.LastSeenScreen
import com.aura.mcp.cache.PerceptionCache
import com.aura.mcp.cache.ScreenActivity
import com.aura.mcp.server.ScreenSettle
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import android.util.Base64
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `read_screen` — the agent's default way to look at the screen.
 *
 * Replaces the retired `get_ui_tree`, which was cheap in latency and ruinous in
 * tokens (the fat tree shape, ~19k tokens for one Amazon screen) and was therefore
 * correctly steered away from by the doctrine — leaving no cheap perception path at
 * all. Everything the agent needed a look for that was not actually visual — what is
 * on this screen, did the list load, is there a Continue button — had to go through
 * `perceive_screen`: a screenshot, a YOLOv8 + OCR pass and an annotated PNG in the
 * context window.
 *
 * ### Settled, not snapshotted
 *
 * A tree read mid-transition is a partial tree that looks complete, which is the whole
 * reason the `uistream` module's `ScreenIdle` was written. So this waits, reusing
 * [ScreenSettle] rather than inventing a second idea of "quiet": three cheapest-first
 * exits (quiet start ~250 ms, event quiescence ~300 ms, and a signature-stability
 * escape for perpetual animators) under an unconditional 1.5 s cap.
 *
 * It cannot hang. The cap is unconditional, and the stability exit exists precisely so
 * a spinner or a playing video returns rather than burning it. On the common still
 * screen the call costs ~250 ms and exactly ONE tree read — [ScreenSettle.Outcome]
 * carries the snapshot it already took, so settling and reading are not two reads.
 *
 * ### It grounds taps
 *
 * The som_ids in the frame are written to [PerceptionCache], so `read_screen → tap 12`
 * resolves `Fresh`. Without that every tap would fall back to a full `perceive_screen`
 * and the tool would save nothing. The cache now has two producers sharing one som_id
 * namespace, last-writer-wins — correct, because both are honest captures of the same
 * screen, and `update()` swaps coordinates, signature, generation and activity as one
 * immutable unit so a resolve can never mix two captures.
 *
 * ### Image mode (on by default, Settings → Tools)
 *
 * When [annotateImage] says so, the grid is joined by a screenshot with the SAME
 * elements boxed in `perceive_screen`'s role colours — tree only, never OmniParser.
 * The boxes come from [ScreenPayload.Result.marked], the elements the grid itself
 * numbered, so a number on the picture is always the number on the grid. The image is
 * optional in the result: a refused capture (FLAG_SECURE apps, the a11y screenshot
 * throttle) returns the grid alone rather than failing the default look.
 */
internal fun Server.registerReadScreenTool(
    uiTreeBridge: UiTreeBridge,
    perceptionCache: PerceptionCache,
    settleConfig: ScreenSettle.Config = ScreenSettle.Config(),
    lastSeenScreen: LastSeenScreen = LastSeenScreen(),
    screenshotBridge: ScreenshotBridge? = null,
    perceptionBridge: PerceptionBridge? = null,
    annotateImage: () -> Boolean = { false },
) {
    scopedTool(
        name = "read_screen",
        // Budget: ≤260 tok (design Step 3). Was 520. What went: "THIS IS YOUR DEFAULT LOOK" and
        // its usage list, which `Doctrine.read_screen_is_default` states for the whole run, and
        // the never-invent-coordinates rule, which is `Doctrine.target_by_som_id`. What stayed is
        // everything about reading THIS payload, which has no other home.
        description = """
            Look at the screen without a screenshot. Waits for it to stop moving, then draws it as a character grid: each element is a box with its som_id and label inside. Read it like a picture — what contains what, what sits beside what, what comes first.

            Below the grid is a table of what you can act on:
              som    the number to target — tap by this
              in     som_id of the box that contains this one
              flg    flags, below
              label  shown only when it did not fit in its box

            FLAGS
              *  tap              e  text field — type_text, not tap
              S  scrolls          c  toggle, on
              l  long-press       o  toggle, off
              d  disabled — meet its prerequisite first
              -  nothing declared; usually still tappable, but prefer a flagged element

            N+k means som_id N plus k more elements with the same bounds (a row and its wrapper). Target N.

            The header says IDLE, or BUSY if the screen was still moving when the wait ran out.

            Use perceive_screen instead when the answer depends on how something looks (a colour, an image, an unlabelled icon), when this ends with ESCALATE, when your target is missing here, or when tapping what this offered did nothing.
            """.trimIndent(),
    ) { _ ->
        // Compare against the last time the server LOOKED, by either route — a perceive
        // or the settle after the previous gesture. Anchoring anywhere else would make
        // `screenChanged` describe a screen from two actions ago.
        val outcome = ScreenSettle.await(
            bridge = uiTreeBridge,
            config = settleConfig,
            preSignature = lastSeenScreen.get(),
        )
        // Sampled next to the read it describes. ScreenSettle can spend up to 1.5 s
        // getting here, and a counter sampled afterwards would swallow every event
        // fired inside that window — so a screen that moved mid-settle would resolve
        // Fresh and dispatch a tap at coordinates that no longer exist.
        val activityAtRead = ScreenActivity.meaningfulCount

        // minElements = 1: the escalation decision belongs to ReadScreen, which reports
        // the count to the agent. Letting the parser silently return an empty list at
        // the threshold would turn "thin screen" into "blank screen".
        val elements = UiTreeToElements.extract(outcome.payloadJson, minElements = 1)

        // The grid renumbers by area, so the som_ids the model reads off the picture
        // are NOT the tree order. Cache the renumbered elements — caching the input list
        // would make every tap(som_id) land on a different element than the one drawn.
        val payload = ScreenPayload.render(
            elements = elements,
            pkg = ScreenFrame.metaOf(outcome.payloadJson).pkg,
            idle = ReadScreen.idle(outcome.settled, outcome.eventCount),
            settled = outcome.settled,
            escalateReason = ReadScreen.escalateReason(
                elementCount = elements.size,
                treeBlind = outcome.signature.treeBlind,
            ),
            offscreen = ScreenText.offscreen(outcome.payloadJson),
        )

        perceptionCache.update(
            elements = payload?.elements ?: elements,
            signature = outcome.signature,
            activityAtCapture = activityAtRead,
        )
        // A read IS a look: the next gesture's change detection must compare against
        // this, not against whatever the previous settle saw.
        lastSeenScreen.set(outcome.signature)

        val image = payload?.marked
            ?.takeIf { it.isNotEmpty() && annotateImage() }
            ?.let { marked -> annotatedScreenshot(screenshotBridge, perceptionBridge, marked) }

        CallToolResult(
            content = listOfNotNull(
                TextContent(
                    payload?.text ?: ReadScreen.emptyScreenNotice(outcome.settled),
                ),
                image?.let { ImageContent(data = it, mimeType = "image/png") },
            ),
        )
    }
}

/**
 * The grid's numbered elements drawn on a fresh screenshot, or null when either bridge is
 * absent, the capture is refused or slow, or drawing fails. Null is never an error here —
 * the grid already answered the call.
 */
private suspend fun annotatedScreenshot(
    screenshotBridge: ScreenshotBridge?,
    perceptionBridge: PerceptionBridge?,
    marked: List<com.aura.mcp.bridge.DetectedElement>,
): String? {
    if (screenshotBridge == null || perceptionBridge == null) return null
    val shot = withTimeoutOrNull(IMAGE_CAPTURE_BUDGET_MS) { screenshotBridge.captureBase64Png() }
        as? CaptureResult.Success ?: return null
    val bytes = runCatching { Base64.decode(shot.base64Png, Base64.DEFAULT) }.getOrNull() ?: return null
    val groups = marked.groupBy(::roleColor).map { (color, els) -> AnnotationGroup(els, color) }
    return perceptionBridge.drawAnnotations(bytes, groups, IMAGE_MAX_LONG_SIDE_PX)
}

/** A capture slower than this is dropped; the grid goes out alone rather than late. */
private const val IMAGE_CAPTURE_BUDGET_MS = 1_500L

/**
 * The two judgements `read_screen` makes about a settled screen, kept pure so both are
 * testable without a `Server`, a bridge, or a device.
 */
internal object ReadScreen {

    /**
     * Whether the agent must reach for `perceive_screen` instead of acting on this.
     * Null when the tree described the screen well enough to act on.
     *
     * Two ways the tree can fail to describe a screen, and both must escalate:
     * it came back nearly empty, or it came back [treeBlind] — `validation_failed`,
     * meaning a WebView / Canvas / game surface where the tree stays constant while
     * the screen moves freely, so its contents say nothing about what is visible.
     *
     * The count is stated in the reason rather than kept internal: "2 elements" lets
     * the agent judge for itself whether that is plausibly the whole screen.
     */
    fun escalateReason(elementCount: Int, treeBlind: Boolean): String? = when {
        treeBlind ->
            "the accessibility tree cannot describe this screen (custom-rendered — " +
                "a web page, map, game or canvas); its contents are not what is visible"
        elementCount < MIN_UI_TREE_ELEMENTS ->
            "the tree describes only $elementCount element(s), too few to be this " +
                "whole screen — it is likely custom-rendered"
        else -> null
    }

    /**
     * Whether the screen was standing still when we looked.
     *
     * Distinct from `settled`, and the distinction is why both ride on the frame:
     * `settled` means the screen stopped moving before the cap, `idle` means it was
     * never moving at all. `idle=false, settled=true` is the honest description of
     * "a transition ran and this is the result after it finished".
     */
    fun idle(settled: Boolean, eventCount: Int): Boolean = settled && eventCount == 0

    /**
     * What to say when the tree came back with nothing drawable.
     *
     * An empty grid would read as "the screen is blank", which is almost never what
     * happened — a blank result means we could not see, and those are different enough
     * that acting on the wrong one wastes the run.
     */
    fun emptyScreenNotice(settled: Boolean): String = buildString {
        append("SCREEN — nothing readable. The accessibility tree returned no usable ")
        append("elements, so this is a surface it cannot describe (a web page, a map, ")
        append("a game, a video, or a screen that had not drawn yet)")
        if (!settled) append("; the screen was also still moving when the wait expired")
        append(".")
        append(System.lineSeparator())
        append("ESCALATE to perceive_screen — it can see pixels where the tree sees nothing.")
    }
}
