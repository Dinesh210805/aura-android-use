package com.aura.aura_ui.mcp.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aura.aura_ui.mcp.bridge.browser.BrowserChromeBehavior
import com.aura.aura_ui.mcp.bridge.browser.BrowserChromeHost
import com.aura.aura_ui.mcp.bridge.browser.ChromeTab
import com.aura.aura_ui.mcp.bridge.browser.PageScript
import com.aura.mcp.bridge.BrowserAction
import com.aura.mcp.bridge.BrowserBridge
import com.aura.mcp.bridge.BrowserCapture
import com.aura.mcp.bridge.BrowserErrorKind
import com.aura.mcp.bridge.BrowserPage
import com.aura.mcp.bridge.BrowserResult
import com.aura.mcp.bridge.BrowserSessionMode
import com.aura.mcp.bridge.BrowserTab
import com.aura.mcp.bridge.BrowserTabAction
import com.aura.mcp.bridge.BrowserTabs
import com.aura.mcp.bridge.ExtractedRow
import com.aura.mcp.bridge.FindOutcome
import com.aura.mcp.bridge.RawElement
import com.aura.aura_ui.mcp.bridge.browser.BrowserWindow
import com.aura.mcp.bridge.HandoffEnd
import com.aura.mcp.bridge.HandoffState
import com.aura.mcp.bridge.looksLikeLoginWall
import com.aura.mcp.server.BrowserHandoff
import com.aura.mcp.tools.PendingUpload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The **scratch** browser engine: an offscreen [WebView] AURA owns outright.
 *
 * This is the cheap half of the browser plane. It has full DOM access, so reading a
 * page costs a JSON payload instead of a screenshot plus an OmniParser pass plus a
 * coordinate tap — a difference of one to two orders of magnitude in tokens, and it
 * is deterministic where vision is probabilistic.
 *
 * What it deliberately cannot do: anything requiring the user's login. It holds none
 * of Chrome's cookies by construction, which is a security property, not a gap. The
 * `mine` mode that *can* reach signed-in pages is Slice 2 and reports
 * [BrowserErrorKind.UNAVAILABLE] until it lands — the tool layer turns that into an
 * actionable message rather than a silent wrong answer.
 *
 * Threading: WebView is main-thread-only. Every touch of it goes through
 * `Dispatchers.Main`, and a [Mutex] serialises whole operations so a `read` can never
 * interleave with a navigation and observe a half-loaded page.
 */
class AppBrowserBridge(
    context: Context,
    /**
     * False for the [research] instance: its page must never reach
     * [com.aura.aura_ui.agent.state.AuraStateStore], or the voice plane would believe a browser
     * window opened (or closed) that the user never saw.
     */
    private val publishesState: Boolean = true,
) : BrowserBridge, BrowserChromeHost {

    companion object {
        /**
         * The one browser for this process — and that is the physical truth, not a shortcut:
         * there is one screen, one overlay window, and one set of tabs the user can see.
         *
         * ### The bug this closes (device report 2026-08-05)
         *
         * *"1st command 'open amazon', 2nd command 'search for an item' — it opened another
         * tab, went to amazon again, then searched."*
         *
         * `open()` was never at fault; it calls `ensureWebView()` and reuses correctly. The
         * fault was lifetime. `AuraAgent.browserBridge` is `by lazy`, i.e. one per **agent
         * instance**, justified by its KDoc on the grounds that *"AuraAgent itself is already
         * held for the whole session by its callers."* That holds for exactly one of the three
         * construction sites:
         *
         * | Site | Shape | Survives? |
         * |---|---|---|
         * | `AuraOverlayService` | `by lazy { AuraAgent(...) }` | yes |
         * | `AuraAgentPhoneRunner` | `AuraAgent(context).runFromSavedSettings(...)` | **no** |
         * | `AssistantForegroundService` | `fun agent() = AuraAgent(...)` | **no** |
         *
         * `AuraAgentPhoneRunner` is the Gemini Live → `drive_phone` path — the one the user
         * actually speaks to. Every voice command built a fresh agent, a fresh bridge, a fresh
         * WebView and a new tab, orphaning the previous window.
         *
         * This is verbatim the failure the instance KDoc claims to have fixed. The fix was
         * real but applied one level too low: a lifetime fix is only as long-lived as the
         * shortest-lived thing holding it.
         */
        @Volatile
        private var instance: AppBrowserBridge? = null

        fun shared(context: Context): AppBrowserBridge =
            instance ?: synchronized(this) {
                instance ?: AppBrowserBridge(context.applicationContext).also { instance = it }
            }

        @Volatile
        private var researchInstance: AppBrowserBridge? = null

        /**
         * A second browser, never shown, for background look-ups (pre-task research and the
         * agent's `look_up`). Separate from [shared] because research runs beside the agent and
         * must not navigate the tab the agent - or the user - is looking at. Cookies are
         * process-wide in WebView, so the two still share them.
         */
        fun research(context: Context): AppBrowserBridge =
            researchInstance ?: synchronized(this) {
                researchInstance ?: AppBrowserBridge(context.applicationContext, publishesState = false)
                    .also { researchInstance = it }
            }

        // ── Tuning constants ──────────────────────────────────────────────────
        // These live here rather than in a second `private companion object`
        // further down: Kotlin allows exactly ONE companion per class, so the
        // two silently competed and every constant below read as unresolved.

        /** Logcat tag for tab lifecycle (F12) — `adb logcat -s AuraBrowserTabs:I`. */
        const val TAG_TABS = "AuraBrowserTabs"

        /** Only used if display metrics are unavailable; a plausible phone viewport. */
        /** Engine-side cap; the tool layer budgets again for the model's benefit. */
        const val EXTRACT_ENGINE_CAP = 60

        /**
         * How many tabs may be open at once.
         *
         * Each tab is a whole renderer with its own memory, and this process is already
         * holding an ONNX model and an accessibility tree on a phone. Four covered every
         * comparison a person actually asks for ("cheaper on Amazon or Flipkart?") back
         * when the agent was the only thing that could open one.
         *
         * Raised to eight on 2026-08-06, when the window grew a tab strip and the user
         * could open tabs by hand. The pool is deliberately **shared** — nobody's tab is
         * special — and four shared slots meant four hand-opened tabs left the agent
         * nothing and it began failing with "too many tabs" on its own next call.
         */
        const val MAX_TABS = 8

        const val FALLBACK_WIDTH_PX = 1080
        const val FALLBACK_HEIGHT_PX = 2400

        /** Probes per axis when checking a capture for blankness. 16×16 = 256 reads. */
        const val SCREENSHOT_PROBE_GRID = 16

        /** Bounded wait for the renderer to commit a frame before capturing. */
        const val VISUAL_STATE_TIMEOUT_MS = 2_000L
        const val VISUAL_STATE_REQUEST_ID = 1L

        /** Second chance after a flat capture, before believing the page really is blank. */
        const val REPAINT_RETRY_MS = 400L

        const val PAGE_TIMEOUT_MS = 20_000L
        const val SCRIPT_TIMEOUT_MS = 5_000L

        /** Time given to an in-page action to either mutate the DOM or start navigating. */
        const val SETTLE_MS = 600L

        /** Down-to-up gap for a native tap — long enough to register as a real touch. */
        const val TAP_HOLD_MS = 60L

        /**
         * How many times `find` scrolls before giving up. Only matters for
         * infinite-scroll pages — a normal document is fully searchable on the
         * first probe, because the DOM does not care where the viewport is.
         */
        const val FIND_SCROLL_ATTEMPTS = 4
        const val FIND_SCROLL_FRACTION = 0.9
        const val FIND_SCROLL_SETTLE_MS = 500L

        const val WAIT_POLL_MS = 300L
        const val MIN_WAIT_MS = 500L
        const val MAX_WAIT_MS = 30_000L

        /** PNG ignores this, but the parameter is required. */
        const val SCREENSHOT_QUALITY = 100
    }

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One live surface. Every tool operates on whichever of these is [activeIndex].
     *
     * A list rather than a single WebView because comparing two sites is the one intention
     * a single surface cannot express — see [BrowserBridge.tabs]. Each tab keeps its own
     * WebView, so switching back finds the page as it was rather than reloading it (a
     * search results page is often not reproducible, so a reload can silently disagree
     * with what the agent was comparing against).
     *
     * Guarded by [mutex] and only ever touched on `Dispatchers.Main`, like the WebViews
     * themselves.
     */
    private val tabs = mutableListOf<Tab>()
    private var activeIndex = 0

    private class Tab(val view: WebView) {
        var hasPage = false

        // ── chrome state ───────────────────────────────────────────────
        // Pushed here by the tab's persistent WebViewClient/WebChromeClient rather than
        // read out of the WebView on demand: `view.url` and `view.title` are only correct
        // on the main thread and lag behind a redirect, and the URL bar has to update the
        // instant navigation *starts* or it shows the previous page's address while the
        // next one loads — the exact moment a user checks the address bar for a login.

        var url: String = ""
        var title: String = ""

        /** 0..100. Starts at 100 so an untouched tab does not render a stalled bar. */
        var progress: Int = 100

        /**
         * Set by [loadUrl] for the duration of one agent-initiated load, resumed by the
         * persistent client, then cleared.
         *
         * This field is why the client can be persistent at all. The old code installed a
         * fresh `WebViewClient` per load to carry its continuation, which meant every
         * agent navigation destroyed whatever callbacks anything else had registered — and
         * left a dead, already-settled client behind to swallow the user's own in-page
         * navigations. Holding only the *continuation* per load, and the *client* per tab,
         * separates the two lifetimes that were wrongly fused.
         */
        var pendingLoad: ((BrowserResult.Failure?) -> Unit)? = null

        /** Resume an in-flight [loadUrl] exactly once. */
        fun settleLoad(failure: BrowserResult.Failure?) {
            val pending = pendingLoad ?: return
            pendingLoad = null
            pending(failure)
        }
    }

    private val active: Tab? get() = tabs.getOrNull(activeIndex)

    /**
     * The active tab's WebView, or null when nothing is open.
     *
     * Kept as a property with the old name so the nine call sites that predate tabs read
     * exactly as before: "the browser" means "the tab in front", which is the same thing a
     * person means.
     */
    private val webView: WebView? get() = active?.view

    private var hasPage: Boolean
        get() = active?.hasPage == true
        set(value) { active?.hasPage = value }

    /**
     * One-shot file arming for `browser_upload`. See [PendingUpload]: a chooser AURA did
     * not arm gets nothing, because `onShowFileChooser` fires for any page that opens a
     * picker — including one the user never agreed to give a file to.
     */
    private val pendingUpload = PendingUpload()

    /**
     * Show the browser for a direct user visit from AURA Home. Reuses the active tab when
     * one exists; otherwise it opens the browser's own new-tab panel. This deliberately
     * stays outside the suspendable MCP API because it is a local UI action.
     */
    fun showUserBrowser() {
        chromeScope.launch {
            mutex.withLock {
                if (handoffPrompt != null) return@withLock
                backgroundRequested = false
                val tab = active ?: newTab()
                if (window.isShowing) {
                    window.resize(BrowserWindow.Size.FULL)
                } else if (window.attach(tab.view, BrowserWindow.Size.FULL, isHandoff = false)) {
                    if (!tab.hasPage) window.showNewTabPanel(true)
                }
            }
        }
    }

    override fun isAvailable(mode: BrowserSessionMode): Boolean =
        mode == BrowserSessionMode.SCRATCH

    override suspend fun open(
        url: String,
        mode: BrowserSessionMode,
        visible: Boolean,
    ): BrowserResult<BrowserPage> =
        guardScratch(mode) {
            mutex.withLock {
                withContext(Dispatchers.Main) {
                    val tab = ensureTab()
                    val view = tab.view
                    when (val load = loadUrl(tab, url)) {
                        null -> {
                            hasPage = true
                            // Re-assert layout after navigation: a page that swaps
                            // documents can otherwise re-render into a stale viewport.
                            view.layOutOffscreen()
                            applyVisibility(visible, view)
                            extract(view)
                        }
                        else -> load
                    }
                }
            }
        }

    override suspend fun read(mode: BrowserSessionMode): BrowserResult<BrowserPage> =
        guardScratch(mode) {
            mutex.withLock {
                withContext(Dispatchers.Main) {
                    val view = webView.takeIf { hasPage }
                        ?: return@withContext noPage()
                    extract(view)
                }
            }
        }

    override suspend fun act(
        mode: BrowserSessionMode,
        selector: String,
        action: BrowserAction,
        value: String?,
    ): BrowserResult<BrowserPage> = guardScratch(mode) {
        mutex.withLock {
            withContext(Dispatchers.Main) {
                val view = webView.takeIf { hasPage } ?: return@withContext noPage()

                val failure = when (action) {
                    BrowserAction.BACK -> {
                        if (!view.canGoBack()) {
                            BrowserResult.Failure(BrowserErrorKind.ACT_FAILED, "No page to go back to.")
                        } else {
                            view.goBack()
                            null
                        }
                    }
                    BrowserAction.SCROLL_DOWN -> runScript(view, PageScript.scrollBy(0.8))
                    BrowserAction.SCROLL_UP -> runScript(view, PageScript.scrollBy(-0.8))
                    else -> runScript(view, PageScript.act(selector, action.name.lowercase(), value))
                }
                if (failure != null) return@withContext failure

                // A click may navigate. Rather than racing onPageFinished against an
                // in-place DOM update, give the page a fixed moment to settle and then
                // re-extract — whatever it became is what we report.
                delay(SETTLE_MS)
                extract(view)
            }
        }
    }

    override suspend fun find(mode: BrowserSessionMode, text: String): BrowserResult<FindOutcome> =
        guardScratch(mode) {
            mutex.withLock {
                withContext(Dispatchers.Main) {
                    val view = webView.takeIf { hasPage } ?: return@withContext noPage()

                    // The DOM holds the whole document regardless of scroll position, so
                    // the first probe usually settles it without scrolling at all. The
                    // loop exists solely for infinite-scroll pages, where the content
                    // genuinely does not exist until you go down and it lazily loads.
                    var scrolled = false
                    var present = probeContains(view, text)
                    var attempts = 0
                    while (!present && attempts < FIND_SCROLL_ATTEMPTS) {
                        runScript(view, PageScript.scrollBy(FIND_SCROLL_FRACTION))
                        delay(FIND_SCROLL_SETTLE_MS)
                        scrolled = true
                        present = probeContains(view, text)
                        attempts++
                    }

                    // Extract first: it applies the data-aura-el stamps that `locate`
                    // resolves against, and it is the page the caller gets back anyway.
                    val page = when (val extracted = extract(view)) {
                        is BrowserResult.Success -> extracted.value
                        is BrowserResult.Failure -> return@withContext extracted
                    }

                    val selector = if (present) locateSelector(view, text) else null
                    BrowserResult.Success(
                        FindOutcome(page = page, matchedSelector = selector, requiredScrolling = scrolled),
                    )
                }
            }
        }

    override suspend fun waitFor(
        mode: BrowserSessionMode,
        text: String?,
        timeoutMs: Long,
    ): BrowserResult<BrowserPage> = guardScratch(mode) {
        mutex.withLock {
            withContext(Dispatchers.Main) {
                val view = webView.takeIf { hasPage } ?: return@withContext noPage()

                val budget = timeoutMs.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
                var waited = 0L
                while (waited < budget) {
                    // A null needle means "wait for the page to stop changing"; the
                    // cheapest usable proxy is that document.readyState is complete,
                    // which loadUrl already guarantees — so a null needle just settles.
                    if (text == null || probeContains(view, text)) break
                    delay(WAIT_POLL_MS)
                    waited += WAIT_POLL_MS
                }

                if (text != null && waited >= budget && !probeContains(view, text)) {
                    return@withContext BrowserResult.Failure(
                        BrowserErrorKind.TIMEOUT,
                        "'$text' did not appear within ${budget / 1000}s.",
                    )
                }
                extract(view)
            }
        }
    }

    override suspend fun upload(
        mode: BrowserSessionMode,
        selector: String,
        fileUri: String,
    ): BrowserResult<BrowserPage> = guardScratch(mode) {
        mutex.withLock {
            withContext(Dispatchers.Main) {
                val view = webView.takeIf { hasPage } ?: return@withContext noPage()

                // A native touch is required here, NOT `el.click()`. Confirmed on device:
                // Chromium logs "File chooser dialog can only be shown with a user
                // activation" and silently refuses to invoke onShowFileChooser for a
                // script-originated click — a security policy against pages scripting
                // their own file picker. Only a real MotionEvent through the WebView's
                // input pipeline reads as a trusted user gesture.
                val rect = resolveElementRect(view, selector)
                    ?: return@withContext BrowserResult.Failure(
                        BrowserErrorKind.ACT_FAILED,
                        "Could not locate the upload control on the page.",
                    )

                // Arm FIRST, then tap. The chooser callback can arrive synchronously
                // inside the tap dispatch, so arming afterwards would race and cancel
                // our own upload.
                pendingUpload.arm(fileUri)
                dispatchRealTap(view, rect.first, rect.second)

                delay(SETTLE_MS)
                // Clears a still-armed file if the tap missed its target or no chooser
                // ever opened — otherwise it would sit armed and fire against whatever
                // page opens the next chooser, the exact hazard PendingUpload exists to
                // prevent. A no-op if the real chooser already consumed it.
                pendingUpload.consume()
                extract(view)
            }
        }
    }

    /** [selector]'s on-screen centre in WebView-local pixels, or null if unresolved. */
    private suspend fun resolveElementRect(view: WebView, selector: String): Pair<Float, Float>? {
        val raw = evaluate(view, PageScript.elementRect(selector)) ?: return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        val cssX = obj["x"]?.jsonPrimitive?.doubleOrNull ?: return null
        val cssY = obj["y"]?.jsonPrimitive?.doubleOrNull ?: return null
        val scale = view.scale.takeIf { it > 0f } ?: 1f
        return (cssX.toFloat() * scale) to (cssY.toFloat() * scale)
    }

    /** Dispatch a real DOWN/UP touch at [x]/[y] (WebView-local px) — see [upload] for why. */
    private suspend fun dispatchRealTap(view: WebView, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        try {
            view.dispatchTouchEvent(down)
        } finally {
            down.recycle()
        }
        delay(TAP_HOLD_MS)
        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        try {
            view.dispatchTouchEvent(up)
        } finally {
            up.recycle()
        }
    }

    override suspend fun extract(
        mode: BrowserSessionMode,
        fields: List<String>,
    ): BrowserResult<List<ExtractedRow>> = guardScratch(mode) {
        mutex.withLock {
            withContext(Dispatchers.Main) {
                val view = webView.takeIf { hasPage } ?: return@withContext noPage()

                val raw = evaluate(view, PageScript.extractRows(EXTRACT_ENGINE_CAP))
                    ?: return@withContext BrowserResult.Failure(
                        BrowserErrorKind.ACT_FAILED,
                        "Could not read the page structure.",
                    )

                val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: return@withContext BrowserResult.Failure(
                        BrowserErrorKind.ACT_FAILED,
                        "The page returned something unreadable.",
                    )

                val rows = obj["rows"]?.jsonArray.orEmpty().mapNotNull { entry ->
                    val row = entry.jsonObject
                    val text = row["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    ExtractedRow(
                        text = text,
                        link = row["link"]?.jsonPrimitive?.contentOrNull,
                        // Field matching is deliberately NOT done in JS. Guessing what
                        // "price" means from markup is exactly the kind of cleverness that
                        // fails silently on the next site; the model reads the text and is
                        // far better at it. `fields` stays in the contract as a hint for
                        // when a real matcher earns its place.
                        fields = emptyMap(),
                    )
                }
                BrowserResult.Success(rows)
            }
        }
    }

    override suspend fun screenshot(mode: BrowserSessionMode): BrowserResult<BrowserCapture> =
        guardScratch(mode) {
            mutex.withLock {
                withContext(Dispatchers.Main) {
                    val view = webView.takeIf { hasPage } ?: return@withContext noPage()

                    // `open` settles on a fixed delay, which is fine for reading the DOM
                    // (the JS runs regardless of paint) but not for capturing pixels: the
                    // renderer can have committed nothing yet, and `view.draw` then copies
                    // an empty surface. Measured on CPH2661 — the same page captured real
                    // content when the renderer was already warm and blank when cold.
                    awaitVisualState(view)
                    val first = captureBase64Png(view)

                    // One retry, because "the renderer needed a moment longer" is both the
                    // likeliest cause and the only one we can do anything about. If it is
                    // still flat after this, the flag is the truth and the agent is told.
                    if (first is BrowserResult.Success && first.value.uniform) {
                        delay(REPAINT_RETRY_MS)
                        awaitVisualState(view)
                        captureBase64Png(view)
                    } else {
                        first
                    }
                }
            }
        }

    override suspend fun close(mode: BrowserSessionMode): BrowserResult<Unit> =
        guardScratch(mode) {
            // The conversation plane must stop believing a window is open the moment it is not.
            if (publishesState) com.aura.aura_ui.agent.state.AuraStateStore.onBrowserPage(null)
            mutex.withLock {
                withContext(Dispatchers.Main) {
                    // Take the window down BEFORE destroying the tabs, and unconditionally.
                    // `returnToTaskView = false`: there is about to be nothing left to show —
                    // re-attaching the outgoing WebView here would put a window up over a
                    // view this same call is a line away from destroying.
                    //
                    // Without the teardown itself, closing during a handoff does not merely
                    // leak an overlay (a full-screen window wrapping a destroyed WebView,
                    // with no owner left to remove it). It PERMANENTLY BRICKS the browser
                    // plane: `handoffPrompt` stays non-null, so `isHandedOff` stays true and
                    // every write is refused for the life of the process — and nothing
                    // remains that could ever clear it, because `handoffState` reads the page
                    // through `active?.view` and `tabs` is now empty, leaving `loginFormGone`
                    // null forever. "Unknown is not gone" is the right rule; it is also what
                    // makes this unrecoverable.
                    endHandoff(returnToTaskView = false)
                    // Every tab, not just the active one. The contract is "release the
                    // surface and any memory it holds", and a WebView left behind by a
                    // close is a leak the agent has no handle to reach any more.
                    tabs.forEach { it.view.destroySafely() }
                    tabs.clear()
                    activeIndex = 0
                    // Write the cookie store to disk before the surfaces that produced it
                    // are gone. WebView flushes on its own schedule, so a login completed
                    // seconds before the process dies is otherwise simply lost — which is
                    // half of "I logged in and it didn't stick". Persistent cookies then
                    // survive to the next launch; session cookies never do, here or in
                    // any other browser.
                    CookieManager.getInstance().flush()
                    BrowserResult.Success(Unit)
                }
            }
        }

    // ── handoff ────────────────────────────────────────────────────────

    private val window by lazy {
        BrowserWindow(appContext, this).apply {
            // Set once, not per-attach like onDone: this callback doesn't need to close
            // over anything call-specific, only the live handoffPrompt/backgroundRequested
            // state below.
            onClose = {
                // Main-thread callback from the close button/badge. During a handoff this
                // IS "I'm done" — end it the same safe, polled way onDone does; the agent's
                // next handoffState() poll tears the window down via endHandoff(), never
                // this callback directly. Outside a handoff, "close" backgrounds the plain
                // "watch it work" window (the same path open(background=true) takes)
                // instead of destroying tabs here: a real teardown has to go through
                // browser_close's mutex so it can never race an in-flight read/act on the
                // same WebView.
                if (handoffPrompt != null) {
                    handoffEnd = HandoffEnd.USER_DONE
                } else {
                    backgroundRequested = true
                    detach()?.layOutOffscreen()
                }
            }
        }
    }

    /** Non-null exactly while the user has the browser. Read by [handoffState] and the gate. */
    @Volatile
    private var handoffPrompt: String? = null

    @Volatile
    private var handoffEnd: HandoffEnd? = null

    /**
     * The last `visible` a caller passed to [open]. Read on the main thread only.
     *
     * Sticky rather than per-call: once a task says "run in the background", later
     * `read`/`act`/`find` calls on the same tab must not pop the window back up, and once
     * a task is visible, ending a handoff should return to that state rather than vanish.
     */
    private var backgroundRequested = false

    /**
     * When true, a browser window this plane pops up opens as the minimized ball instead of full
     * screen. Set for runs that came from a voice conversation: the user is talking to AURA, not
     * watching a page, so a full-screen browser would cover what they are doing. The bubble still
     * shows the browser is working, and a tap expands it. A login handoff ignores this � the user
     * has to see that page.
     */
    @Volatile
    var openMinimized: Boolean = false

    override suspend fun handoff(
        mode: BrowserSessionMode,
        prompt: String,
    ): BrowserResult<HandoffState> = guardScratch(mode) {
        mutex.withLock {
            withContext(Dispatchers.Main) {
                val tab = active ?: return@withContext BrowserResult.Failure(
                    BrowserErrorKind.NO_PAGE,
                    "Nothing to hand over — open a page first.",
                )

                window.onDone = {
                    // Main-thread callback from the button. Only records the verdict; the
                    // teardown happens on the agent's next poll, so the window is never
                    // removed from under a coroutine mid-read.
                    handoffEnd = HandoffEnd.USER_DONE
                }

                val shown = window.attach(tab.view, BrowserWindow.Size.FULL, isHandoff = true, handoffPrompt = prompt)
                if (!shown) {
                    return@withContext BrowserResult.Failure(
                        BrowserErrorKind.UNAVAILABLE,
                        "Could not show the browser window — AURA needs the 'display over " +
                            "other apps' permission. Ask the user to grant it, or ask them " +
                            "to do this step in their own browser.",
                    )
                }
                handoffPrompt = prompt
                handoffEnd = null
                BrowserResult.Success(HandoffState(handedOff = true, prompt = prompt))
            }
        }
    }

    override suspend fun handoffState(mode: BrowserSessionMode): BrowserResult<HandoffState> =
        guardScratch(mode) {
            mutex.withLock {
                withContext(Dispatchers.Main) {
                    val prompt = handoffPrompt
                        ?: return@withContext BrowserResult.Success(HandoffState(false, null))

                    val view = active?.view
                    // Read the page BEFORE deciding, because "is the login form gone" is
                    // the first auto-resume condition and it can only be answered by
                    // looking. A page we could not read stays null — unknown is not "gone".
                    val page = view?.let { (extract(it) as? BrowserResult.Success)?.value }
                    val formGone = page?.let { !it.looksLikeLoginWall }

                    val end = handoffEnd ?: when {
                        !window.isFrontmost && window.size == BrowserWindow.Size.HIDDEN ->
                            HandoffEnd.WINDOW_GONE
                        BrowserHandoff.shouldAutoResume(
                            loginFormGone = formGone,
                            msSinceLastTouch = window.msSinceLastTouch,
                            windowFrontmost = window.isFrontmost,
                        ) -> HandoffEnd.AUTO
                        else -> null
                    }

                    if (end == null) {
                        return@withContext BrowserResult.Success(
                            HandoffState(handedOff = true, prompt = prompt),
                        )
                    }

                    endHandoff()
                    BrowserResult.Success(
                        HandoffState(handedOff = false, prompt = prompt, endedBy = end, page = page),
                    )
                }
            }
        }

    /**
     * Main thread. Takes the handoff window down.
     *
     * @param returnToTaskView when true (the default), a task that was not asked to run in
     *   the background gets its plain "watch it work" window back immediately — a handoff
     *   ending should not silently drop the user back to seeing nothing, since they never
     *   opted into invisibility. [close] passes false: there is nothing left to show a
     *   window over once the tab it would adopt is a line away from being destroyed.
     */
    private fun endHandoff(returnToTaskView: Boolean = true) {
        val returned = window.detach()
        handoffPrompt = null
        handoffEnd = null
        // The whole point of a handoff is that the user just logged in. Persist that
        // immediately rather than trusting WebView's own flush timer to outlive the
        // process — this is the single most valuable flush point in the class.
        CookieManager.getInstance().flush()
        if (returnToTaskView && returned != null && !backgroundRequested) {
            window.attach(returned, BrowserWindow.Size.FULL, isHandoff = false)
        } else {
            // The engine measures its own detached views; now that nobody else owns this
            // one's layout, it needs that treatment again or every element comes back 0×0.
            returned?.layOutOffscreen()
        }
    }

    /**
     * Main thread. Show or hide the "watch the task" window per the caller's [visible]
     * choice, made on [open]. Never touches an active handoff — that window is a
     * mandatory escalation the agent cannot suppress by asking for the background.
     *
     * Does nothing if the window is already showing: attaching again would snap a window
     * the user minimized to [BrowserWindow.Size.BALL] back to full size, fighting a choice
     * they just made. [followActiveTab] is the one path allowed to move an already-shown
     * window, because that is a change of *which page*, not a demand for a bigger window.
     */
    private fun applyVisibility(visible: Boolean, view: WebView) {
        backgroundRequested = !visible
        if (handoffPrompt != null) return
        if (!visible) {
            if (window.isShowing) window.detach()?.layOutOffscreen()
            return
        }
        if (!window.isShowing) {
            // Attach at FULL, then shrink: BALL is only ever reached by resize, which keeps the
            // WebView's full-size layout. Attaching straight at BALL would lay the page out in a
            // 60dp window and extraction would read a 60px viewport.
            if (window.attach(view, BrowserWindow.Size.FULL, isHandoff = false) && openMinimized) {
                window.resize(BrowserWindow.Size.BALL)
            }
        }
    }

    /**
     * Main thread. Keep the visible window pointed at whichever tab just became active.
     *
     * Only runs when a window is already up and no handoff owns it — opening or switching
     * a tab while the browser plane is backgrounded must not pop a window up out of
     * nowhere, and a handoff's window belongs to the login it is showing, not to
     * whichever tab happens to be active underneath it.
     */
    private fun followActiveTab(newView: WebView, previousView: WebView?) {
        if (!window.isShowing || handoffPrompt != null) return
        val currentSize = window.size
        window.attach(newView, currentSize, isHandoff = false)
        previousView?.layOutOffscreen()
    }

    /** True while the user has the browser — the gate the tool layer consults. */
    override val isHandedOff: Boolean get() = handoffPrompt != null

    // ── chrome (spec 2026-08-06) ───────────────────────────────────────

    /**
     * For chrome actions that mutate the tab list and therefore need [mutex].
     *
     * `Main.immediate` so a tap that needs no suspension runs inside the touch handler and
     * the strip repaints in the same frame, instead of flickering the old tab for one.
     */
    private val chromeScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Main thread. Tell the window its chrome state moved. Cheap no-op when no window. */
    private fun publishChrome() {
        window.refreshChrome()
    }

    override fun chromeTabs(): List<ChromeTab> = tabs.mapIndexed { index, tab ->
        // canGoBack/canGoForward walk the WebView's history and are only ever read for the
        // tab in front (they drive the two toolbar arrows; chips do not show them). This
        // runs on every progress tick, so asking eight background renderers a question
        // nobody reads is eight native round-trips per tick for nothing.
        val front = index == activeIndex
        ChromeTab(
            title = BrowserChromeBehavior.tabTitle(tab.title, tab.url),
            url = tab.url,
            secure = BrowserChromeBehavior.isSecure(tab.url),
            progress = tab.progress,
            canGoBack = front && runCatching { tab.view.canGoBack() }.getOrDefault(false),
            canGoForward = front && runCatching { tab.view.canGoForward() }.getOrDefault(false),
        )
    }

    override fun chromeActiveIndex(): Int = activeIndex

    // Navigation the user drives goes straight at the WebView, deliberately WITHOUT
    // taking `mutex`. Two reasons. The calls are main-thread-only and synchronous, so
    // there is no race to guard. And `loadUrl` suspends for up to PAGE_TIMEOUT_MS — taking
    // the lock for a user's tap would stall the agent's `handoffState` poll behind a page
    // load, which is precisely the poll that notices the user has finished. The user
    // driving the page out from under a mid-task agent is not a race; it is a takeover,
    // and the agent's next extract() correctly reports whatever page it landed on.

    override fun onChromeBack() {
        active?.view?.let { if (it.canGoBack()) it.goBack() }
    }

    override fun onChromeForward() {
        active?.view?.let { if (it.canGoForward()) it.goForward() }
    }

    override fun onChromeReload() {
        active?.view?.reload()
    }

    override fun onChromeNavigate(raw: String) {
        val target = BrowserChromeBehavior.normalizeInput(raw) ?: return
        val tab = active ?: return
        tab.hasPage = true
        // Get out of the way first. Observed on device: searching from the new-tab panel
        // loaded the page correctly and left the panel sitting on top of it, so the URL
        // bar said duckduckgo.com while the screen still said "Where to?". The panel is
        // an overlay on the page host, so nothing dismisses it but us.
        window.showNewTabPanel(false)
        tab.view.loadUrl(target)
    }

    override fun onChromeHome() {
        window.showNewTabPanel(true)
    }

    override fun onChromeNewTab() {
        chromeScope.launch {
            mutex.withLock {
                if (tabs.size >= MAX_TABS) return@withLock
                val outgoing = active?.view
                val tab = newTab()
                followActiveTab(tab.view, outgoing)
                window.showNewTabPanel(true)
                publishChrome()
            }
        }
    }

    override fun onChromeSelectTab(index: Int) {
        chromeScope.launch {
            mutex.withLock {
                if (index !in tabs.indices || index == activeIndex) return@withLock
                val outgoing = active?.view
                activeIndex = index
                window.showNewTabPanel(false)
                followActiveTab(tabs[index].view, outgoing)
                publishChrome()
            }
        }
    }

    override fun onChromeCloseTab(index: Int) {
        chromeScope.launch {
            mutex.withLock {
                if (index !in tabs.indices) return@withLock

                // Closing the page the user was handed IS "I'm done" — record the verdict
                // and let the agent's next poll tear the handoff down, exactly as the
                // window's own close button does. Without this the tab would go, taking
                // the Done button with it, while handoffPrompt stayed non-null: every
                // write refused, and no affordance left to end it. Closing a *background*
                // tab during a handoff is harmless and still allowed.
                if (handoffPrompt != null && tabs[index].view === active?.view) {
                    handoffEnd = HandoffEnd.USER_DONE
                    return@withLock
                }

                // The window is holding the active tab's WebView as a child. Hand it back
                // before destroying anything, or a closed tab leaves the window parenting
                // a dead view — the same class of bug browser_close's ordering comment
                // warns about.
                val outgoing = active?.view
                val closing = tabs.removeAt(index)
                if (closing.view === outgoing) window.detach()
                closing.view.destroySafely()
                CookieManager.getInstance().flush()

                if (tabs.isEmpty()) {
                    activeIndex = 0
                    window.detach()?.layOutOffscreen()
                    publishChrome()
                    return@withLock
                }
                activeIndex = BrowserChromeBehavior.activeIndexAfterClose(
                    closed = index,
                    active = activeIndex,
                    remaining = tabs.size,
                )
                val incoming = tabs[activeIndex].view
                if (closing.view === outgoing) {
                    window.attach(incoming, BrowserWindow.Size.FULL, isHandoff = false)
                }
                publishChrome()
            }
        }
    }

    override suspend fun tabs(
        mode: BrowserSessionMode,
        action: BrowserTabAction,
        url: String?,
        index: Int?,
    ): BrowserResult<BrowserTabs> =
        guardScratch(mode) {
            mutex.withLock {
                withContext(Dispatchers.Main) {
                    when (action) {
                        BrowserTabAction.LIST -> BrowserResult.Success(snapshot())

                        BrowserTabAction.OPEN -> {
                            if (url.isNullOrBlank()) {
                                return@withContext BrowserResult.Failure(
                                    BrowserErrorKind.BAD_REQUEST,
                                    "browser_tabs open needs a 'url'.",
                                )
                            }
                            // A cap, because each tab is a whole renderer. Refusing is the
                            // honest answer: silently closing the oldest would discard the
                            // very page the agent opened tabs in order to compare against.
                            if (tabs.size >= MAX_TABS) {
                                return@withContext BrowserResult.Failure(
                                    BrowserErrorKind.BAD_REQUEST,
                                    "Already at the $MAX_TABS-tab limit. Close one first.",
                                )
                            }
                            val outgoing = active?.view
                            val tab = newTab()
                            when (val load = loadUrl(tab, url)) {
                                null -> {
                                    tab.hasPage = true
                                    tab.view.layOutOffscreen()
                                    followActiveTab(tab.view, outgoing)
                                    when (val page = extract(tab.view)) {
                                        is BrowserResult.Success ->
                                            BrowserResult.Success(snapshot(page.value))
                                        else -> page as BrowserResult.Failure
                                    }
                                }
                                else -> {
                                    // Don't strand a dead tab in the list — the agent would
                                    // see a tab it never successfully opened and could switch
                                    // to it, getting an empty page with no explanation.
                                    tab.view.destroySafely()
                                    tabs.remove(tab)
                                    activeIndex = activeIndex.coerceAtMost(tabs.lastIndex.coerceAtLeast(0))
                                    load
                                }
                            }
                        }

                        BrowserTabAction.SWITCH -> {
                            val target = index ?: return@withContext missingIndex("switch")
                            if (target !in tabs.indices) return@withContext badIndex(target)
                            val outgoing = active?.view
                            activeIndex = target
                            // Re-assert layout: a tab that has been sitting in the
                            // background can otherwise render into a stale viewport, which
                            // is the 0×0 extraction failure all over again.
                            val tab = tabs[target]
                            tab.view.layOutOffscreen()
                            followActiveTab(tab.view, outgoing)
                            if (!tab.hasPage) {
                                BrowserResult.Success(snapshot())
                            } else {
                                when (val page = extract(tab.view)) {
                                    is BrowserResult.Success -> BrowserResult.Success(snapshot(page.value))
                                    else -> page as BrowserResult.Failure
                                }
                            }
                        }

                        BrowserTabAction.CLOSE -> {
                            val target = index ?: return@withContext missingIndex("close")
                            if (target !in tabs.indices) return@withContext badIndex(target)
                            tabs.removeAt(target).view.destroySafely()
                            // Keep the *same* tab in front where possible. Closing tab 3
                            // should not silently move the agent to a different site than
                            // the one it was working in. Shared with the chrome's close
                            // button — this logic existed here first and correctly, and a
                            // second hand-written copy over there promptly got it wrong.
                            activeIndex = BrowserChromeBehavior.activeIndexAfterClose(
                                closed = target,
                                active = activeIndex,
                                remaining = tabs.size,
                            )
                            publishChrome()
                            BrowserResult.Success(snapshot())
                        }
                    }
                }
            }
        }

    /** Must run on the main thread — reads live WebView state. */
    /**
     * What is open right now, for the turn-0 preamble — a plain read with no `mode` guard and
     * no mutex, because it is called while assembling a run rather than during one.
     *
     * Main-thread values (`WebView.title`/`url`) read from whatever thread the caller is on;
     * a stale title in a hint is harmless, whereas blocking run assembly on the main thread
     * would not be.
     */
    fun openTabsForPreamble(): List<Triple<String?, String, Boolean>> =
        tabs.mapIndexed { i, tab ->
            Triple(tab.view.title, tab.view.url.orEmpty(), i == activeIndex)
        }.filter { it.second.isNotBlank() }

    /**
     * Publish the active tab to [com.aura.aura_ui.agent.state.AuraStateStore] as `host — title`.
     *
     * Host, not the full URL: query strings routinely carry session tokens and search terms, and
     * this string is sent to Google as part of the Live prompt. The host plus the page's own title
     * is enough for the brain lane to know a window is already open on the thing being discussed.
     */
    private fun feedBrowserState() = runCatching {
        if (!publishesState) return@runCatching
        val active = tabs.getOrNull(activeIndex)
        val url = active?.view?.url
        if (url.isNullOrBlank()) {
            com.aura.aura_ui.agent.state.AuraStateStore.onBrowserPage(null)
            return@runCatching
        }
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")
        val title = active.view.title?.takeIf { it.isNotBlank() }
        com.aura.aura_ui.agent.state.AuraStateStore.onBrowserPage(
            listOfNotNull(host ?: url, title).joinToString(" — "),
        )
    }

    // Central point: every browser operation that reports tab state passes through here, so the
    // shared store stays current without a callback on each individual tool.
    private fun snapshot(page: BrowserPage? = null): BrowserTabs = BrowserTabs(
        tabs = tabs.mapIndexed { i, tab ->
            BrowserTab(
                index = i,
                title = tab.view.title.orEmpty(),
                url = tab.view.url.orEmpty(),
                active = i == activeIndex,
            )
        },
        activeIndex = activeIndex,
        page = page,
    ).also { feedBrowserState() }

    private fun missingIndex(what: String) = BrowserResult.Failure(
        BrowserErrorKind.BAD_REQUEST,
        "browser_tabs $what needs an 'index' from browser_tabs list.",
    )

    private fun badIndex(target: Int) = BrowserResult.Failure(
        BrowserErrorKind.BAD_REQUEST,
        "No tab at index $target. Open tabs: ${tabs.indices.toList()}.",
    )

    /**
     * Tear a surface down without letting it take the process with it.
     *
     * `destroy()` on a WebView that is mid-load throws on some OEM builds, and this runs
     * inside close paths where a throw would leave the tab list inconsistent — half the
     * tabs destroyed, the list not updated.
     */
    private fun WebView.destroySafely() {
        runCatching {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
    }

    // ── engine ─────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureTab(): Tab = active?.also {
        android.util.Log.i(
            TAG_TABS,
            "TAB_REUSED index=$activeIndex totalTabs=${tabs.size} bridge=${System.identityHashCode(this)}",
        )
    } ?: newTab()

    /** Build a fully-configured surface and make it the active tab. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(): Tab = createWebView().let { view ->
        val tab = Tab(view)
        installClients(tab)
        tabs += tab
        activeIndex = tabs.lastIndex
        // F12 traceability (user request 2026-08-05). Tool calls were already logged, but
        // nothing recorded whether a call REUSED a WebView or created one — which is exactly
        // the evidence needed to tell "the agent opened a second tab" from "the bridge was
        // rebuilt underneath it". Logs the bridge identity for the same reason: two identity
        // hashes across two commands means the process singleton is not holding.
        android.util.Log.i(
            TAG_TABS,
            "TAB_CREATED index=${tabs.lastIndex} totalTabs=${tabs.size} bridge=${System.identityHashCode(this)}",
        )
        tab
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(appContext).also { view ->
        view.settings.apply {
            // Required — the whole point of this engine is DOM-level access.
            javaScriptEnabled = true
            domStorageEnabled = true
            // Everything below narrows the surface a hostile page can reach. There is
            // deliberately NO addJavascriptInterface call anywhere: an exposed object
            // is reachable by every script on every page the browser visits.
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Popups open as real tabs (see onCreateWindow). A large share of "log me in"
            // runs on a `window.open` SSO popup, and with multiple-window support off the
            // WebView does not merely block it — `onCreateWindow` never fires at all, so
            // the "Sign in with…" button silently does nothing and the user is left
            // tapping a dead control. Opening it into our own tab list, where it is
            // visible and closeable, is a narrower answer than the flag it replaces:
            // `javaScriptCanOpenWindowsAutomatically` stays FALSE, so only a genuine user
            // gesture can reach this path.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false

            // No "AURA/scratch" suffix. It bought nothing — the stock WebView UA already
            // carries a `wv` token, so anything sniffing for an embedded browser finds it
            // either way — while inviting UA-matching breakage on sites that allowlist
            // known browsers.
        }

        // Cookies are process-global, not per-WebView, so this configures every tab at
        // once. Third-party acceptance defaults to FALSE for apps targeting Lollipop and
        // later, which quietly breaks any login that redirects through an identity
        // provider — i.e. most of them. This was the single largest cause of "I logged in
        // and it didn't stick".
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }

        // The WebChromeClient is installed by installClients() along with everything else
        // whose lifetime is the tab's — there is only ever one, so file-chooser arming and
        // progress reporting share it rather than one wrapping the other.
        view.layOutOffscreen()
    }

    /**
     * Install the clients that live as long as [tab] does.
     *
     * Separate from [createWebView] only because they need the `Tab` to write chrome state
     * into, and the view has to exist before the tab can wrap it.
     */
    private fun installClients(tab: Tab) {
        val view = tab.view

        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                // The URL bar must move the moment navigation starts. Waiting for
                // onPageFinished would show the *previous* page's address for the whole
                // load — worst of all during a login, which is exactly when someone
                // checks the address bar.
                url?.let { tab.url = it }
                tab.progress = 0
                // Any navigation in the front tab retires the new-tab panel — the agent's
                // and a tapped link's as much as the user's. onChromeNavigate dismisses it
                // too, a beat earlier, so a tap feels instant rather than waiting on DNS;
                // this is the one that catches every other route in.
                if (tabs.getOrNull(activeIndex) === tab) window.showNewTabPanel(false)
                publishChrome()
            }

            override fun onPageFinished(v: WebView?, finishedUrl: String?) {
                finishedUrl?.let { tab.url = it }
                tab.progress = 100
                tab.title = v?.title.orEmpty()
                publishChrome()
                tab.settleLoad(null)
            }

            override fun onReceivedError(
                v: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // Subresource failures (a missing image, a blocked tracker) are normal
                // and must not fail the page — only main-frame errors do.
                if (request?.isForMainFrame != true) return
                tab.progress = 100
                publishChrome()
                tab.settleLoad(
                    BrowserResult.Failure(
                        BrowserErrorKind.LOAD_FAILED,
                        "Could not load ${request.url}: ${error?.description ?: "unknown error"}",
                    ),
                )
            }

            /**
             * Fires for same-document navigation — a single-page app changing route, a
             * `history.pushState`, an in-page anchor. `onPageStarted` does not, so without
             * this the URL bar freezes on the entry point of every SPA.
             */
            override fun doUpdateVisitedHistory(v: WebView?, url: String?, isReload: Boolean) {
                url?.let { tab.url = it }
                publishChrome()
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, newProgress: Int) {
                tab.progress = newProgress
                publishChrome()
            }

            override fun onReceivedTitle(v: WebView?, title: String?) {
                tab.title = title.orEmpty()
                publishChrome()
            }

            /**
             * A popup — almost always an SSO window. Give it a real tab rather than a
             * hidden surface: the user has to be able to see the identity provider they
             * are typing a password into, and to close it if it is not what they expected.
             *
             * Refusing (returning false) when the pool is full is deliberate. The
             * alternative — silently evicting someone's tab to make room for a page the
             * page asked for — lets any site close the user's other tabs.
             *
             * Mutates `tabs`/`activeIndex` WITHOUT taking [mutex], unlike every other
             * mutation. Not an oversight: the framework needs the new WebView written into
             * `resultMsg` before this returns, so there is nowhere to suspend. It is still
             * main-thread-only, like every other touch of the tab list, so the list itself
             * cannot tear — the exposure is an agent call suspended mid-operation resuming
             * to find a different tab in front. That is also the honest outcome: the popup
             * IS the page the click produced.
             */
            override fun onCreateWindow(
                v: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                if (tabs.size >= MAX_TABS || resultMsg == null) return false
                val popup = newTab()
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = popup.view
                resultMsg.sendToTarget()
                followActiveTab(popup.view, view)
                publishChrome()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?,
            ): Boolean {
                val armed = pendingUpload.consume()
                if (armed == null) {
                    // Nothing armed = the PAGE opened this picker, not the agent. Cancel
                    // it rather than presenting a file chooser the user never asked for,
                    // in a browser they cannot see.
                    callback?.onReceiveValue(null)
                    return true
                }
                callback?.onReceiveValue(arrayOf(Uri.parse(armed)))
                return true
            }
        }
    }

    /**
     * Give the detached WebView a real viewport.
     *
     * Load-bearing, and the failure it prevents is invisible: a WebView that was
     * never attached to a window is also never measured or laid out, so the page
     * renders into a 0×0 viewport. `getBoundingClientRect()` then returns zero
     * width and height for *every* node, the extraction script's visibility filter
     * discards all of them, and each page comes back with `elements: []` while page
     * text still reads fine — a total functional failure that compiles cleanly and
     * passes every JVM test. Measured on device: 0 of 4 elements before this call,
     * 4 of 4 after.
     */
    private fun WebView.layOutOffscreen() {
        // Only when nobody else owns this view's layout. Once the handoff window adopts a
        // tab it becomes a child of a real ViewGroup, and a parent lays its children out;
        // measuring and laying out by hand underneath it fights that, producing a
        // mis-sized viewport or a surface that never draws. This fails *visually* rather
        // than with an exception, which is exactly the class of bug that cost the 0×0
        // extraction failure — hence a guard rather than discipline at four call sites.
        if (parent != null) return
        val metrics = appContext.resources.displayMetrics
        val width = metrics.widthPixels.takeIf { it > 0 } ?: FALLBACK_WIDTH_PX
        val height = metrics.heightPixels.takeIf { it > 0 } ?: FALLBACK_HEIGHT_PX
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, width, height)
    }

    /**
     * Load [url] into [tab] and suspend until it settles.
     *
     * Takes a `Tab`, not a `WebView`, because the outcome now arrives through the tab's
     * **persistent** client rather than one installed for this call. The old version
     * assigned `view.webViewClient` here, once per load, which had two consequences that
     * only showed up once the window grew chrome: every agent navigation destroyed
     * whatever callbacks anything else had registered, and the settled client left behind
     * afterwards swallowed the user's own in-page navigations — so a URL bar built on it
     * would appear to work and then quietly stop updating.
     */
    private suspend fun loadUrl(tab: Tab, url: String): BrowserResult.Failure? =
        try {
            withTimeout(PAGE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    tab.pendingLoad = { failure -> if (cont.isActive) cont.resume(failure) }
                    tab.view.loadUrl(url)
                    cont.invokeOnCancellation {
                        tab.pendingLoad = null
                        tab.view.stopLoading()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Clear the continuation the timeout just abandoned. Leaving it set would let
            // a late onPageFinished resume a coroutine that is already gone.
            tab.pendingLoad = null
            BrowserResult.Failure(
                BrowserErrorKind.TIMEOUT,
                "$url did not finish loading within ${PAGE_TIMEOUT_MS / 1000}s.",
            )
        }

    /** Run a script whose contract is `{ ok: boolean, error?: string }`. */
    private suspend fun runScript(view: WebView, script: String): BrowserResult.Failure? {
        val raw = evaluate(view, script)
            ?: return BrowserResult.Failure(BrowserErrorKind.ACT_FAILED, "The page did not respond.")
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return BrowserResult.Failure(BrowserErrorKind.ACT_FAILED, "Malformed response from the page.")
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull == true) return null
        return BrowserResult.Failure(
            BrowserErrorKind.ACT_FAILED,
            obj["error"]?.jsonPrimitive?.contentOrNull ?: "The action was rejected by the page.",
        )
    }

    private suspend fun extract(view: WebView): BrowserResult<BrowserPage> {
        val raw = evaluate(view, PageScript.EXTRACT)
            ?: return BrowserResult.Failure(BrowserErrorKind.TIMEOUT, "The page did not respond to extraction.")
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return BrowserResult.Failure(BrowserErrorKind.LOAD_FAILED, "Could not read the page contents.")

        val elements = obj["elements"]?.jsonArray.orEmpty().map { entry ->
            val e = entry.jsonObject
            RawElement(
                selector = e["selector"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                role = e["role"]?.jsonPrimitive?.contentOrNull ?: "element",
                label = e["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                value = e["value"]?.jsonPrimitive?.contentOrNull,
                disabled = e["disabled"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }.filter { it.selector.isNotBlank() }

        return BrowserResult.Success(
            BrowserPage(
                url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                elements = elements,
            ),
        )
    }

    /**
     * `evaluateJavascript` hands back the result JSON-encoded. Our scripts return a
     * `JSON.stringify(...)` string, so the callback value is a *quoted* JSON string —
     * decode one layer before parsing. Returns null on timeout or a JS throw.
     */
    private suspend fun evaluate(view: WebView, script: String): String? = try {
        withTimeout(SCRIPT_TIMEOUT_MS) {
            val encoded = suspendCancellableCoroutine { cont ->
                view.evaluateJavascript(script) { result -> cont.resume(result) }
            }
            if (encoded == null || encoded == "null") {
                null
            } else {
                runCatching { json.parseToJsonElement(encoded).jsonPrimitive.content }.getOrNull()
            }
        }
    } catch (e: TimeoutCancellationException) {
        null
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** Cheap "is this text on the page" probe. False on any script trouble. */
    private suspend fun probeContains(view: WebView, text: String): Boolean {
        val raw = evaluate(view, PageScript.containsText(text)) ?: return false
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return false
        return obj["found"]?.jsonPrimitive?.booleanOrNull == true
    }

    /** Resolve [text] to a stamped element selector, or null if only plain text matched. */
    private suspend fun locateSelector(view: WebView, text: String): String? {
        val raw = evaluate(view, PageScript.locate(text)) ?: return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        return obj["selector"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * Draw the WebView into a bitmap and return it base64-encoded, flagged if it looks
     * like nothing was drawn at all.
     *
     * Works whether or not the window is visible — the view is laid out in memory, so
     * there is something to draw regardless. The hazard this guards is that a
     * hardware-accelerated WebView can draw **blank** into a software canvas on some
     * devices, and that failure is invisible from the payload: a flat bitmap is a valid
     * PNG and base64-encodes to a long, healthy-looking string. Checking the string is
     * empty (which is what this used to do) catches nothing.
     *
     * So the pixels themselves are inspected. Verified on CPH2661 (2026-08-02) — the
     * capture comes back with real content there, but the hazard is device-variance, so
     * one green device is not the fleet.
     */
    private fun captureBase64Png(view: WebView): BrowserResult<BrowserCapture> {
        val width = view.width.takeIf { it > 0 } ?: FALLBACK_WIDTH_PX
        val height = view.height.takeIf { it > 0 } ?: FALLBACK_HEIGHT_PX

        return runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))

            val uniform = isUniform(bitmap)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, SCREENSHOT_QUALITY, stream)
            bitmap.recycle()
            BrowserCapture(
                pngBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP),
                uniform = uniform,
            )
        }.fold(
            onSuccess = { capture ->
                if (capture.pngBase64.isBlank()) {
                    BrowserResult.Failure(BrowserErrorKind.ACT_FAILED, "Screenshot came back empty.")
                } else {
                    BrowserResult.Success(capture)
                }
            },
            onFailure = { e ->
                BrowserResult.Failure(
                    BrowserErrorKind.ACT_FAILED,
                    "Could not capture the page: ${e.message}",
                )
            },
        )
    }

    /**
     * Suspend until the WebView says the current DOM has been committed to a draw.
     *
     * This is the API-blessed answer to "is it safe to screenshot yet" — it exists
     * precisely for capture and print of WebViews. A fixed `delay()` is the alternative,
     * and it is strictly worse: too short races the renderer, too long taxes every call
     * forever, and neither is actually *correct*.
     *
     * Failure is swallowed on purpose. On an offscreen view there is no guarantee a draw
     * is ever scheduled, so the callback may simply never fire; the bounded wait then
     * degrades to the old fixed-delay behaviour rather than failing the capture. The
     * `uniform` flag remains the honest backstop either way.
     *
     * The constant [VISUAL_STATE_REQUEST_ID] and the ignored `requestId` are safe **only
     * because every caller holds `mutex`**, so at most one callback is ever outstanding on
     * one WebView. That stops being true the moment `browser_tabs` lands and several
     * WebViews exist — thread the real id through then, or a retry could resume on a
     * sibling's stale frame.
     */
    private suspend fun awaitVisualState(view: WebView) {
        runCatching {
            withTimeout(VISUAL_STATE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    view.postVisualStateCallback(
                        VISUAL_STATE_REQUEST_ID,
                        object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * True when every sampled pixel is the same colour.
     *
     * A coarse grid rather than every pixel: this runs on the main thread inside the
     * capture, and a blank canvas is blank everywhere, so sampling loses nothing while
     * a full scan of a 1080×2400 bitmap would cost 2.6M reads. Bails at the first
     * difference, so a normal page exits after a handful of probes.
     */
    private fun isUniform(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / SCREENSHOT_PROBE_GRID).coerceAtLeast(1)
        val stepY = (bitmap.height / SCREENSHOT_PROBE_GRID).coerceAtLeast(1)
        val first = bitmap.getPixel(0, 0)

        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                if (bitmap.getPixel(x, y) != first) return false
                y += stepY
            }
            x += stepX
        }
        return true
    }

    private fun noPage(): BrowserResult.Failure =
        BrowserResult.Failure(BrowserErrorKind.NO_PAGE, "No page is open in the scratch browser.")

    private suspend fun <T> guardScratch(
        mode: BrowserSessionMode,
        block: suspend () -> BrowserResult<T>,
    ): BrowserResult<T> =
        if (mode != BrowserSessionMode.SCRATCH) {
            BrowserResult.Failure(
                BrowserErrorKind.UNAVAILABLE,
                "Driving the user's own signed-in browser isn't available on this build yet; " +
                    "only the scratch browser is wired.",
            )
        } else {
            block()
        }

}
