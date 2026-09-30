package com.aura.mcp.tools

import com.aura.mcp.bridge.BrowserAction
import com.aura.mcp.bridge.BrowserBridge
import com.aura.mcp.bridge.BrowserCapture
import com.aura.mcp.bridge.BrowserErrorKind
import com.aura.mcp.bridge.BrowserPage
import com.aura.mcp.bridge.BrowserResult
import com.aura.mcp.bridge.BrowserSessionMode
import com.aura.mcp.bridge.BrowserTabAction
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The `browser_*` tool family.
 *
 * One vocabulary, two session modes, N engines underneath (see [BrowserBridge]).
 * The model learns `open → read → act` once and it means the same thing on an
 * Android WebView, the user's Chrome, or a Playwright context on a PC.
 *
 * Deliberate shape choices:
 *  - **`browser_act` returns the page it produced.** Acting without observing forces
 *    a second call to find out what happened; folding the observation in halves the
 *    round trips, the same efficiency doctrine applied to the perceive/act loop.
 *  - **Elements are opaque integers.** No selector crosses the wire (see [BrowserPayload]).
 *  - **`session` defaults to `scratch`.** Reaching into the user's real logged-in
 *    browser must be a deliberate escalation, never an accident of a missing argument.
 */
internal fun Server.registerBrowserTools(bridge: BrowserBridge) {
    val sessions = BrowserSessions()

    registerBrowserOpen(bridge, sessions)
    registerBrowserRead(bridge, sessions)
    registerBrowserAct(bridge, sessions)
    registerBrowserFind(bridge, sessions)
    registerBrowserWait(bridge, sessions)
    registerBrowserScreenshot(bridge)
    registerBrowserExtract(bridge)
    registerBrowserUpload(bridge, sessions)
    registerBrowserTabs(bridge, sessions)
    registerBrowserHandoff(bridge, sessions)
    registerBrowserClose(bridge, sessions)
}

// ── browser_find ───────────────────────────────────────────────────────

private fun Server.registerBrowserFind(bridge: BrowserBridge, sessions: BrowserSessions) = scopedTool(
    name = "browser_find",
    description = "Find text on the open page and get the page back with that element's el_id — instead of " +
        "scrolling around. found=true with no el_id means the text is there but not clickable.",
    inputSchema = browserSchema(
        extra = mapOf("text" to stringSchema("Visible text or label to look for, e.g. 'Add to cart'.")),
        required = listOf("text"),
    ),
) { request ->
    val args = request.arguments
    val mode = BrowserSessionMode.parse(args?.stringArg("session"))
    val text = args?.stringArg("text")?.trim()
    if (text.isNullOrEmpty()) {
        return@scopedTool errorResult("browser_find requires a non-empty 'text' string")
    }

    when (val result = bridge.guarded(mode) { it.find(mode, text) }) {
        is BrowserResult.Success -> {
            val snapshot = sessions.handles(mode).mint(
                page = result.value.page,
                maxTextChars = args.textBudget(),
                maxElements = args.elementBudget(),
            )
            jsonOkPayload(
                BrowserPayload.found(
                    snapshot = snapshot,
                    mode = mode,
                    query = text,
                    // The engine speaks selectors; the model only ever sees integers.
                    matchedElId = snapshot.handles.entries
                        .firstOrNull { it.value == result.value.matchedSelector }?.key,
                    scrolled = result.value.requiredScrolling,
                ),
            )
        }
        is BrowserResult.Failure ->
            jsonOkPayload(BrowserPayload.failure(result.kind, result.message, mode), success = false)
    }
}

// ── browser_wait ───────────────────────────────────────────────────────

private fun Server.registerBrowserWait(bridge: BrowserBridge, sessions: BrowserSessions) = scopedTool(
    name = "browser_wait",
    description = "Wait until text appears on the page (results loading, a spinner finishing), then return " +
        "the page. Better than calling browser_read in a loop.",
    inputSchema = browserSchema(
        extra = mapOf(
            "text" to stringSchema("Text to wait for. Omit to simply let the page settle."),
            "timeout_ms" to numberSchema("How long to wait (500–30000, default 10000)."),
        ),
    ),
) { request ->
    val args = request.arguments
    val mode = BrowserSessionMode.parse(args?.stringArg("session"))
    val text = args?.stringArg("text")?.trim()?.takeIf { it.isNotEmpty() }
    val timeout = (args?.intArg("timeout_ms") ?: DEFAULT_WAIT_MS).toLong()

    bridge.guarded(mode) { it.waitFor(mode, text, timeout) }.render(mode, sessions, args)
}

// ── browser_screenshot ─────────────────────────────────────────────────

private fun Server.registerBrowserScreenshot(bridge: BrowserBridge) = scopedTool(
    name = "browser_screenshot",
    description = "A picture of the open page. Only when the text is not enough: a chart, a map, a price " +
        "inside an image, a popup covering things.",
    inputSchema = browserSchema(),
) { request ->
    val mode = BrowserSessionMode.parse(request.arguments?.stringArg("session"))

    when (val result = bridge.guarded(mode) { it.screenshot(mode) }) {
        is BrowserResult.Success -> CallToolResult(
            content = captureContent(result.value),
            isError = false,
        )
        is BrowserResult.Failure ->
            jsonOkPayload(BrowserPayload.failure(result.kind, result.message, mode), success = false)
    }
}

/**
 * Shape a capture into tool content.
 *
 * Pulled out of the handler so the branch that matters can actually be tested — the
 * warning path only triggers on a device that fails to draw, which is exactly the input
 * no test would ever naturally produce. An untested branch guarding a rare failure is
 * how the previous `encoded.isBlank()` check stayed broken indefinitely.
 *
 * A flat capture is returned, not refused: a genuinely blank page is legitimate. But it
 * ships with the warning beside it, because an image alone gives a VLM no way to tell
 * "the page is empty" from "the WebView failed to draw" — and it will confidently
 * describe either one.
 */
internal fun captureContent(capture: BrowserCapture) = buildList {
    add(ImageContent(data = capture.pngBase64, mimeType = "image/png"))
    if (capture.uniform) {
        add(
            TextContent(
                "WARNING: this screenshot is a single flat colour. Either the page is " +
                    "genuinely blank, or this device failed to draw the page into the image. " +
                    "Do not describe its contents — call browser_read instead, which reads the " +
                    "page directly and does not depend on drawing.",
            ),
        )
    }
}


// ── browser_handoff ────────────────────────────────────────────────────

private fun Server.registerBrowserHandoff(bridge: BrowserBridge, sessions: BrowserSessions) = scopedTool(
    name = "browser_handoff",
    description = "Hand the page to the user for a step only they can do — signing in, a one-time code, a " +
        "CAPTCHA, choosing an address, paying. AURA's browser is never signed in, so do not retry " +
        "login forms, and never ask the user for a password. After handing off, the task is not " +
        "over: every following call must be browser_handoff with check=true until it returns " +
        "handed_off=false — then read the page and carry on.",
    inputSchema = browserSchema(
        extra = mapOf(
            "prompt" to stringSchema(
                "What to ask the user to do, in one line. Shown on the window, e.g. " +
                    "'Sign in to Naukri so I can apply for you'.",
            ),
            "check" to booleanSchema("True to poll an existing handoff instead of starting one."),
        ),
    ),
) { request ->
    val args = request.arguments
    val mode = BrowserSessionMode.parse(args?.stringArg("session"))
    val checking = args?.get("check")?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() } == true

    val result = if (checking) {
        bridge.guarded(mode) { it.handoffState(mode) }
    } else {
        val prompt = args?.stringArg("prompt")?.trim().orEmpty()
            .ifBlank { "AURA needs you to finish this step." }
        bridge.guarded(mode) { it.handoff(mode, prompt) }
    }

    when (result) {
        is BrowserResult.Success -> {
            // Mint handles only for the page handed BACK. While the handoff is running
            // there is no page result, and there must not be: el_ids minted mid-handoff
            // would point at a page the user is actively changing under the agent.
            val snapshot = result.value.page?.let { page ->
                sessions.handles(mode).mint(
                    page = page,
                    maxTextChars = args.textBudget(),
                    maxElements = args.elementBudget(),
                )
            }
            jsonOkPayload(BrowserPayload.handoff(result.value, snapshot, mode))
        }
        is BrowserResult.Failure ->
            jsonOkPayload(BrowserPayload.failure(result.kind, result.message, mode), success = false)
    }
}


// ── browser_tabs ───────────────────────────────────────────────────────

private fun Server.registerBrowserTabs(bridge: BrowserBridge, sessions: BrowserSessions) = scopedTool(
    name = "browser_tabs",
    description = "Several pages at once. action open (with url) starts a second page without losing the " +
        "first — how you compare sites. list shows open tabs, switch (index) brings one to the " +
        "front as it was, close discards one. Other browser tools act on the active tab.",
    inputSchema = browserSchema(
        extra = mapOf(
            "action" to stringSchema("list | open | switch | close. Defaults to list."),
            "url" to stringSchema("Absolute http(s) URL. Required for action=\"open\"."),
            "index" to numberSchema("Tab index from action=\"list\". Required for switch/close."),
        ),
        pageCaps = false,
    ),
) { request ->
    val args = request.arguments
    val mode = BrowserSessionMode.parse(args?.stringArg("session"))
    val action = BrowserTabAction.parse(args?.stringArg("action"))
    val url = args?.stringArg("url")?.trim()
    val index = args?.get("index")?.let { runCatching { it.jsonPrimitive.int }.getOrNull() }

    // Validate the URL here rather than in the engine, for the same reason browser_open
    // does: a malformed URL is knowable without spending a tab on it, and the failure
    // reads better when it names the argument the model got wrong.
    if (action == BrowserTabAction.OPEN && !isSupportedUrl(url.orEmpty())) {
        return@scopedTool jsonOkPayload(
            BrowserPayload.failure(
                BrowserErrorKind.INVALID_URL,
                "browser_tabs open needs an absolute http(s) URL; got '${url.orEmpty()}'",
                mode,
            ),
            success = false,
        )
    }

    when (val result = bridge.guarded(mode) { it.tabs(mode, action, url, index) }) {
        is BrowserResult.Success -> {
            // Mint handles only when a page actually came back. Minting on a bare `list`
            // would bump the generation and silently invalidate the el_ids the agent is
            // holding — a read-only call must not cost the agent its handles.
            val snapshot = result.value.page?.let { page ->
                sessions.handles(mode).mint(
                    page = page,
                    maxTextChars = args.textBudget(),
                    maxElements = args.elementBudget(),
                )
            }
            jsonOkPayload(
                BrowserPayload.tabs(result.value.tabs, result.value.activeIndex, snapshot, mode),
            )
        }
        is BrowserResult.Failure ->
            jsonOkPayload(BrowserPayload.failure(result.kind, result.message, mode), success = false)
    }
}


// ── browser_extract ────────────────────────────────────────────────────

private fun Server.registerBrowserExtract(bridge: BrowserBridge) = scopedTool(
    name = "browser_extract",
    description = "Get the repeated items on a page — results, products, listings — as rows with text and " +
        "link, plus the total count.",
    inputSchema = browserSchema(),
) { request ->
    val mode = BrowserSessionMode.parse(request.arguments?.stringArg("session"))
    val fields = request.arguments?.get("fields")?.let { raw ->
        runCatching { raw.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
    }.orEmpty()

    when (val result = bridge.guarded(mode) { it.extract(mode, fields) }) {
        is BrowserResult.Success -> {
            val projection = BrowserExtractModel.project(
                rows = result.value,
                maxRows = EXTRACT_MAX_ROWS,
                maxCharsPerRow = EXTRACT_MAX_CHARS_PER_ROW,
            )
            jsonOkPayload(BrowserPayload.extract(projection, mode), success = true)
        }
        is BrowserResult.Failure ->
            jsonOkPayload(BrowserPayload.failure(result.kind, result.message, mode), success = false)
    }
}

/** Enough to compare a page of results without flooding the context. */
private const val EXTRACT_MAX_ROWS = 25
private const val EXTRACT_MAX_CHARS_PER_ROW = 400


// ── browser_upload ─────────────────────────────────────────────────────

private fun Server.registerBrowserUpload(bridge: BrowserBridge, sessions: BrowserSessions) = scopedTool(
    name = "browser_upload",
    description = "Attach a file to an upload control on the page. Give the control's el_id and a path from " +
        "find_files. Returns the page so you can see the file name appear.",
    inputSchema = browserSchema(
        extra = mapOf(
            "el_id" to numberSchema("The upload control's el_id from browser_read."),
            "file" to stringSchema("File URI or path, as returned by find_files."),
            "generation" to numberSchema("The 'generation' from the page result your el_id came from."),
        ),
        required = listOf("el_id", "file"),
    ),
) { request ->
    val args = request.arguments
    val mode = BrowserSessionMode.parse(args?.stringArg("session"))
    val elId = args?.intArg("el_id")
    val file = args?.stringArg("file")?.trim()

    if (elId == null || file.isNullOrEmpty()) {
        return@scopedTool errorResult("browser_upload requires 'el_id' and 'file'")
    }

    // Same generation-stamped resolution as browser_act. An upload aimed at a stale
    // el_id would attach the user's resume to whatever control now sits at that number
    // — precisely the false-success mode generations exist to prevent.
    val selector = when (
        val resolution = sessions.handles(mode).resolve(elId, args.intArg("generation"))
    ) {
        is HandleResolution.Resolved -> resolution.selector
        is HandleResolution.Stale ->
            return@scopedTool jsonOkPayload(
                BrowserPayload.staleHandles(resolution.currentGeneration, mode),
                success = false,
            )
        HandleResolution.Unknown ->
            return@scopedTool jsonOkPayload(BrowserPayload.unknownHandle(elId, mode), success = false)
    }

    bridge.guarded(mode) { it.upload(mode, selector, file) }.render(mode, sessions, args)
}

// ── browser_open ───────────────────────────────────────────────────────

private fun Server.registerBrowserOpen(bridge: BrowserBridge, sessions: BrowserSessions) = scopedTool(
    name = "browser_open",
    description = "Open a web page in AURA's browser and get its text plus numbered elements (el_id) to act " +
        "on. For websites when the user did not name an app or a browser app. It reuses one tab: " +
        "keep working with browser_read, browser_find and browser_act; open a second tab with " +
        "browser_tabs only to compare pages. session scratch (default) is signed into nothing; if " +
        "the result says requires_session, retry with session mine — the user's signed-in " +
        "browser. The window is visible by default; set background true only if the user asked " +
        "for it to run out of sight.",
    inputSchema = browserSchema(
        extra = mapOf(
            "url" to stringSchema("Absolute http(s) URL to open."),
            "background" to booleanSchema(
                "True to run without showing the browser window. Default false: the window " +
                    "is visible so the user can watch, and can minimize it to a bubble " +
                    "themselves without interrupting the task. Only set true when the user " +
                    "explicitly wants this done in the background.",
            ),
        ),
        required = listOf("url"),
    ),
) { request ->
    val args = request.arguments
    val mode = BrowserSessionMode.parse(args?.stringArg("session"))
    val url = args?.stringArg("url")?.trim().orEmpty()
    val background = args?.get("background")
        ?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() } == true

    if (!isSupportedUrl(url)) {
        return@scopedTool jsonOkPayload(
            BrowserPayload.failure(
                BrowserErrorKind.INVALID_URL,
                "Not an absolute http(s) URL: '$url'",
                mode,
            ),
            success = false,
        )
    }

    bridge.guarded(mode) { it.open(url, mode, visible = !background) }.render(mode, sessions, args)
}

// ── browser_read ───────────────────────────────────────────────────────

private fun Server.registerBrowserRead(bridge: BrowserBridge, sessions: BrowserSessions) = scopedTool(
    name = "browser_read",
    description = "Read the open page again without navigating. Call it after anything may have changed the " +
        "page, or when a result reports stale_handles — el_ids only work for the page version " +
        "they came from.",
    inputSchema = browserSchema(),
) { request ->
    val args = request.arguments
    val mode = BrowserSessionMode.parse(args?.stringArg("session"))

    bridge.guarded(mode) { it.read(mode) }.render(mode, sessions, args)
}

// ── browser_act ────────────────────────────────────────────────────────

private fun Server.registerBrowserAct(bridge: BrowserBridge, sessions: BrowserSessions) = scopedTool(
    name = "browser_act",
    description = "Act on the open page and get the page back as it is now. click, type, select and submit " +
        "need an el_id from the latest page result; scroll_down, scroll_up and back do not. Pass " +
        "that result's generation so a page that changed underneath is caught instead of clicked " +
        "blindly.",
    inputSchema = browserSchema(
        extra = mapOf(
            "action" to stringSchema(
                "click | type | select | submit | scroll_down | scroll_up | back",
            ),
            "el_id" to numberSchema("Element number from the latest page result. Required for element actions."),
            "value" to stringSchema("Text to type, or option to select. Required for type/select."),
            "generation" to numberSchema(
                "The 'generation' from the page result your el_id came from. Enables staleness detection.",
            ),
        ),
        required = listOf("action"),
    ),
) { request ->
    val args = request.arguments
    val mode = BrowserSessionMode.parse(args?.stringArg("session"))

    val action = BrowserAction.parse(args?.stringArg("action"))
        ?: return@scopedTool errorResult(
            "browser_act: unknown action. Use one of: click, type, select, submit, " +
                "scroll_down, scroll_up, back.",
        )

    if (action.needsValue && args?.stringArg("value").isNullOrEmpty()) {
        return@scopedTool errorResult("browser_act: action '${action.name.lowercase()}' requires 'value'.")
    }

    val selector = if (action.needsElement) {
        val elId = args?.intArg("el_id")
            ?: return@scopedTool errorResult(
                "browser_act: action '${action.name.lowercase()}' requires 'el_id'. " +
                    "Call browser_read to list the elements on the page.",
            )
        when (val resolution = sessions.handles(mode).resolve(elId, args.intArg("generation"))) {
            is HandleResolution.Resolved -> resolution.selector
            is HandleResolution.Stale ->
                return@scopedTool jsonOkPayload(
                    BrowserPayload.staleHandles(resolution.currentGeneration, mode),
                    success = false,
                )
            HandleResolution.Unknown ->
                return@scopedTool jsonOkPayload(BrowserPayload.unknownHandle(elId, mode), success = false)
        }
    } else {
        PAGE_LEVEL_SELECTOR
    }

    bridge.guarded(mode) { it.act(mode, selector, action, args?.stringArg("value")) }
        .render(mode, sessions, args)
}

// ── browser_close ──────────────────────────────────────────────────────

private fun Server.registerBrowserClose(bridge: BrowserBridge, sessions: BrowserSessions) = scopedTool(
    name = "browser_close",
    description = "Close the browser session when the web task is done.",
    inputSchema = browserSchema(pageCaps = false),
) { request ->
    val mode = BrowserSessionMode.parse(request.arguments?.stringArg("session"))

    when (val result = bridge.guarded(mode) { it.close(mode) }) {
        is BrowserResult.Success -> {
            sessions.handles(mode).clear()
            jsonOk(true, mapOf("session" to mode.wire))
        }
        is BrowserResult.Failure ->
            jsonOkPayload(BrowserPayload.failure(result.kind, result.message, mode), success = false)
    }
}

// ── shared plumbing ────────────────────────────────────────────────────

/** Selector placeholder for actions that address the page rather than an element. */
internal const val PAGE_LEVEL_SELECTOR: String = ""

/** Default `browser_wait` budget: long enough for a slow search, short enough to fail fast. */
internal const val DEFAULT_WAIT_MS: Int = 10_000

/** Caller-tunable budgets, clamped so a bad argument can't blow the agent's context. */
internal fun JsonObject?.textBudget(): Int =
    this?.intArg("max_text_chars")?.coerceIn(200, 20_000) ?: BrowserPageModel.DEFAULT_MAX_TEXT_CHARS

internal fun JsonObject?.elementBudget(): Int =
    this?.intArg("max_elements")?.coerceIn(5, 300) ?: BrowserPageModel.DEFAULT_MAX_ELEMENTS

/**
 * Per-mode handle maps. `scratch` and `mine` are genuinely different pages and must
 * never share a numbering space — el_id 3 in the scratch browser has nothing to do
 * with el_id 3 in the user's Chrome.
 */
internal class BrowserSessions {
    private val lock = Any()
    private val perMode = mutableMapOf<BrowserSessionMode, BrowserHandles>()

    fun handles(mode: BrowserSessionMode): BrowserHandles = synchronized(lock) {
        perMode.getOrPut(mode) { BrowserHandles() }
    }
}

/** Only absolute http(s) URLs. Blocks `file:`, `javascript:`, `data:` and bare strings. */
internal fun isSupportedUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    // Must have a host beyond the scheme separator.
    return url.substringAfter("://").substringBefore('/').isNotBlank()
}

/**
 * Run a bridge call behind an availability probe, converting a crash into a structured
 * failure. A thrown exception in a tool handler surfaces to the model as an opaque
 * transport error it cannot act on.
 */
private suspend fun <T> BrowserBridge.guarded(
    mode: BrowserSessionMode,
    block: suspend (BrowserBridge) -> BrowserResult<T>,
): BrowserResult<T> {
    if (!isAvailable(mode)) {
        return BrowserResult.Failure(
            BrowserErrorKind.UNAVAILABLE,
            "The '${mode.wire}' browser engine isn't available on this device.",
        )
    }
    return runCatching { block(this) }.getOrElse {
        BrowserResult.Failure(BrowserErrorKind.LOAD_FAILED, "browser bridge crashed: ${it.message}")
    }
}

/** Mint handles for a successful page result and shape it for the wire. */
private fun BrowserResult<BrowserPage>.render(
    mode: BrowserSessionMode,
    sessions: BrowserSessions,
    args: JsonObject?,
): CallToolResult = when (this) {
    is BrowserResult.Success -> {
        val snapshot = sessions.handles(mode).mint(
            page = value,
            maxTextChars = args.textBudget(),
            maxElements = args.elementBudget(),
        )
        jsonOkPayload(BrowserPayload.page(snapshot, mode))
    }
    is BrowserResult.Failure ->
        jsonOkPayload(BrowserPayload.failure(kind, message, mode), success = false)
}

/**
 * The schema every browser tool shares — and therefore the schema the model is charged for
 * ELEVEN TIMES on every request.
 *
 * That multiplier is the whole design note. A sentence here is not one sentence; measured
 * 2026-08-26, the three shared properties were ~600 tokens across the plane, more than any
 * single browser tool's own description. The `session` property alone explained scratch-vs-mine
 * in full prose — a rule `Doctrine.browser_for_open_web` already states once for the whole run.
 *
 * [pageCaps] exists for the same reason: `browser_close` and `browser_tabs` return no page, so
 * advertising text and element caps to them was paying for an argument that could never apply.
 */
private fun browserSchema(
    extra: Map<String, JsonObject> = emptyMap(),
    required: List<String> = emptyList(),
    pageCaps: Boolean = true,
): io.modelcontextprotocol.kotlin.sdk.types.ToolSchema =
    io.modelcontextprotocol.kotlin.sdk.types.ToolSchema(
        properties = JsonObject(
            extra + mapOf(
                "session" to stringSchema("\"scratch\" (default) or \"mine\" (signed-in browser)."),
            ) + if (pageCaps) {
                mapOf(
                    "max_text_chars" to numberSchema("Page-text cap (default 4000)."),
                    "max_elements" to numberSchema("Element cap (default 60)."),
                )
            } else {
                emptyMap()
            },
        ),
        required = required,
    )
