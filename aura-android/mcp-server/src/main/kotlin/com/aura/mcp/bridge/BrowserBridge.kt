package com.aura.mcp.bridge

/**
 * Port for driving a web page.
 *
 * AURA exposes ONE browser tool contract over TWO session modes, because the
 * decisive constraint is *whose cookies are in play*:
 *
 *  - [BrowserSessionMode.SCRATCH] — a browser surface AURA owns. Full DOM access,
 *    token-cheap, deterministic. Carries none of the user's logins, so it can read
 *    articles, compare products and fill public forms, but can never touch Gmail.
 *  - [BrowserSessionMode.MINE] — the user's real, logged-in browser. The only way
 *    to complete a checkout or reply to mail. On Android this is Chrome driven via
 *    accessibility node actions (Chrome serializes page content into
 *    `AccessibilityNodeInfo` whenever an a11y service is active); DOM-level access
 *    is impossible there without adb, so the adapter is node-based, not JS-based.
 *
 * Implementations live in `:app` (Android) and, later, in the PC daemon. The tool
 * layer above never learns which engine answered — that is the whole point of the
 * port, and it is what makes Windows a backend rather than a rewrite.
 *
 * Every method returns [BrowserResult] rather than throwing: the agent reacts to a
 * structured `kind` instead of parsing prose, matching [WebSearchBridge]'s contract.
 */
interface BrowserBridge {

    /** Cheap capability probe; the tool layer short-circuits before doing real work. */
    fun isAvailable(mode: BrowserSessionMode): Boolean

    /**
     * Is the user using the visible browser window right now?
     *
     * Synchronous and allocation-free because the tool chokepoint reads it on **every**
     * browser call, before doing any work. Defaults to false so an engine with no visible
     * window — the PC daemon, a test fake — inherits "never handed off" rather than having
     * to implement a concept it does not have.
     */
    val isHandedOff: Boolean get() = false

    /**
     * Navigate to [url] and return the resulting page.
     *
     * @param visible true (the default) means the user can watch this happen — the
     *   engine shows its window unless a handoff already owns it. False is the deliberate
     *   opt-in for a task that should run without appearing on screen; the caller must ask
     *   for that explicitly, because silently invisible browsing is the thing this window
     *   exists to avoid.
     */
    suspend fun open(url: String, mode: BrowserSessionMode, visible: Boolean = true): BrowserResult<BrowserPage>

    /** Re-read the current page without navigating. */
    suspend fun read(mode: BrowserSessionMode): BrowserResult<BrowserPage>

    /**
     * Perform [action] against the element identified by [selector], then return the
     * page *after* the action settles.
     *
     * Returning the fresh page is deliberate: it collapses act+verify into one round
     * trip, the same efficiency doctrine the orchestration work applied to
     * `perceive_screen` (an action that reports nothing forces a wasted observation call).
     */
    suspend fun act(
        mode: BrowserSessionMode,
        selector: String,
        action: BrowserAction,
        value: String? = null,
    ): BrowserResult<BrowserPage>

    /**
     * Locate [text] on the page and scroll it into view.
     *
     * Note this is NOT the Android "scroll until you see it" loop. The DOM already
     * contains the whole document regardless of scroll position, so finding is a
     * query, not a search — one call instead of eight scroll+read round trips.
     * Scrolling still happens for lazily-loaded (infinite-scroll) pages, where
     * content genuinely does not exist until you go down.
     */
    suspend fun find(mode: BrowserSessionMode, text: String): BrowserResult<FindOutcome>

    /**
     * Wait until [text] appears (or the page stops changing when [text] is null).
     *
     * Exists for content that arrives after load — spinners, lazy lists, results
     * that stream in. Without it the agent's only option is to re-read in a loop,
     * paying an LLM round trip per attempt.
     */
    suspend fun waitFor(
        mode: BrowserSessionMode,
        text: String?,
        timeoutMs: Long,
    ): BrowserResult<BrowserPage>

    /**
     * Pull the page's repeated blocks out in one call — product cards, search results,
     * job listings.
     *
     * The efficiency case, which is the reason the tool exists at all: reading five
     * products naively costs five `read`s plus the model parsing prose from each. A page
     * that shows a list is already *made of* repeated structure, so returning that
     * structure directly turns twenty round trips into one.
     *
     * [fields] is a best-effort hint, not a contract. A page's markup owes us nothing, so
     * [ExtractedRow.text] is always populated even when nothing matched — returning fields
     * only would mean a page we misread yields nothing at all, which is worse than prose.
     */
    suspend fun extract(
        mode: BrowserSessionMode,
        fields: List<String>,
    ): BrowserResult<List<ExtractedRow>>

    /**
     * Attach a file to a file-input on the page, then return the resulting page.
     *
     * [fileUri] is a content or file URI the agent already resolved (via `find_files`).
     * The engine arms it for exactly ONE file chooser — see `PendingUpload` for why that
     * one-shot rule is a security boundary rather than tidiness.
     */
    suspend fun upload(
        mode: BrowserSessionMode,
        selector: String,
        fileUri: String,
    ): BrowserResult<BrowserPage>

    /** PNG of the current page. Works regardless of window visibility. */
    suspend fun screenshot(mode: BrowserSessionMode): BrowserResult<BrowserCapture>

    /**
     * List, open, switch between, or close browser tabs.
     *
     * Exists because **comparing** is the one intention a single surface cannot express.
     * Every other tool works fine on one page at a time, but "is this cheaper on Flipkart
     * or Amazon?" means holding two pages at once: with one surface the agent must navigate
     * away, and navigating away destroys what it was comparing against. Re-opening the
     * first site to re-read it is not merely slow — a search results page is often not
     * reproducible (session, ranking, stock), so the second read can disagree with the
     * first and the comparison is quietly wrong.
     *
     * Real tabs, not a wrapper: each holds its own live [android.webkit.WebView] with its
     * own history and scroll position, so switching back finds the page exactly as it was
     * rather than reloading it.
     *
     * [BrowserTabs.page] is populated for the actions that change which page is live
     * ([BrowserTabAction.OPEN], [BrowserTabAction.SWITCH]) so the agent does not pay a
     * `browser_read` round trip to find out what it just switched to — the same act+verify
     * collapse [act] makes.
     */
    suspend fun tabs(
        mode: BrowserSessionMode,
        action: BrowserTabAction,
        url: String? = null,
        index: Int? = null,
    ): BrowserResult<BrowserTabs>

    /**
     * "I need you" — show the page to the user and stop touching it.
     *
     * The escape valve that makes the whole browser plane honest. AURA cannot log into
     * anything: the scratch engine holds none of the user's cookies by construction, and
     * Google has blocked OAuth inside embedded WebViews since 2023, so no amount of
     * cleverness gets past a sign-in wall. Rather than flailing at a form it can never
     * satisfy — or worse, asking the user to hand over a password — AURA puts the real
     * page in front of them, waits, and picks up where they left off.
     *
     * The *same* WebView is shown, not a copy. A fresh window loading the same URL would
     * discard the session and any half-typed credentials, which is precisely the failure a
     * login handoff exists to avoid.
     *
     * While handed off, WRITE tools are refused and READ tools are not — see
     * `BrowserHandoff` for why that asymmetry is required rather than convenient.
     *
     * @param prompt what the user is being asked to do, shown on the window. A browser
     *   appearing unannounced over someone's phone is indistinguishable from a phishing
     *   overlay; it has to say who opened it and why.
     */
    suspend fun handoff(mode: BrowserSessionMode, prompt: String): BrowserResult<HandoffState>

    /**
     * Where the handoff has got to. Cheap; the agent polls this while it waits.
     *
     * Returns [HandoffState.handedOff] = false once the user is finished, whether they
     * finished by tapping "I'm done", by the auto-resume conditions being met, or because
     * the window went away.
     */
    suspend fun handoffState(mode: BrowserSessionMode): BrowserResult<HandoffState>

    /** Release the surface and any memory it holds. Idempotent. */
    suspend fun close(mode: BrowserSessionMode): BrowserResult<Unit>
}

/**
 * Result of [BrowserBridge.find].
 *
 * [matchedSelector] is engine-native and never reaches the model — the tool layer
 * maps it to the `el_id` minted for the accompanying [page], so "found it" and
 * "here is what you can do about it" arrive together in one call.
 */
data class FindOutcome(
    val page: BrowserPage,
    val matchedSelector: String?,
    /** True when scrolling was needed, i.e. the page lazily loaded more content. */
    val requiredScrolling: Boolean = false,
)

/**
 * The state of a "you take it" handoff.
 *
 * [page] is populated once the handoff has ended, so the agent resumes knowing what the
 * user left behind — logged in, on a different page, with a form half-filled — instead of
 * assuming the page it handed over is the page it gets back.
 */
data class HandoffState(
    val handedOff: Boolean,
    val prompt: String?,
    /** Why it ended. Null while still handed off. */
    val endedBy: HandoffEnd? = null,
    val page: BrowserPage? = null,
)

/**
 * Does this page want a login?
 *
 * One definition, two very different callers, which is why it lives on the port rather
 * than inside either of them: the tool layer uses it to tell the model "the scratch
 * browser can never satisfy this, stop trying", and the handoff uses its *disappearance*
 * as the first auto-resume condition. Two copies of this rule would mean the agent could
 * be told a page is a login wall and simultaneously decide the login is finished.
 *
 * A password field, not text matching. Wording is localised and endlessly various ("Sign
 * in", "Anmelden", "লগ ইন"); `input[type=password]` is the one thing every login on the
 * web actually has, and it does not need translating.
 */
val BrowserPage.looksLikeLoginWall: Boolean
    get() = elements.any { it.role.lowercase() in LOGIN_ELEMENT_ROLES }

private val LOGIN_ELEMENT_ROLES = setOf("password")

enum class HandoffEnd {
    /** The user tapped "I'm done". */
    USER_DONE,

    /** Form gone, user still, window in front — all three. */
    AUTO,

    /** The window went away without a verdict (dismissed, OEM killed it, permission lost). */
    WINDOW_GONE,
}

enum class BrowserTabAction {
    /** What is open right now. Cheap, and the only action that changes nothing. */
    LIST,

    /** New tab at [BrowserBridge.tabs]'s `url`; it becomes active. */
    OPEN,

    /** Make an existing tab active. Its page is returned, unreloaded. */
    SWITCH,

    /** Discard a tab. Closing the active one falls back to its neighbour. */
    CLOSE,
    ;

    companion object {
        /**
         * Defaults to [LIST] on anything unrecognised.
         *
         * The read-only action is the safe default: a typo'd `action` that fell through to
         * OPEN or CLOSE would destroy or navigate a surface the agent was mid-way through
         * using, and a model that meant something else learns more from an accurate list
         * than from an error string.
         */
        fun parse(raw: String?): BrowserTabAction = when (raw?.trim()?.lowercase()) {
            "open", "new" -> OPEN
            "switch", "select", "activate" -> SWITCH
            "close" -> CLOSE
            else -> LIST
        }
    }
}

/** One open tab, as the model sees it. [index] is its handle — stable only within a listing. */
data class BrowserTab(
    val index: Int,
    val title: String,
    val url: String,
    val active: Boolean,
)

/**
 * The tab set after an action.
 *
 * [page] is non-null only when the action changed which page is live, so the agent gets
 * "here is what you switched to" without a follow-up read.
 */
data class BrowserTabs(
    val tabs: List<BrowserTab>,
    val activeIndex: Int,
    val page: BrowserPage? = null,
)

enum class BrowserSessionMode {
    /** AURA-owned surface. No user cookies. */
    SCRATCH,

    /** The user's real logged-in browser. */
    MINE,
    ;

    companion object {
        /**
         * Parse a tool argument. Defaults to [SCRATCH] — the safe, cheap engine.
         * Escalating to the user's real session must be a deliberate model choice.
         */
        fun parse(raw: String?): BrowserSessionMode =
            if (raw?.trim()?.lowercase() == "mine") MINE else SCRATCH
    }
}

enum class BrowserAction {
    CLICK,
    TYPE,
    SELECT,
    SUBMIT,
    SCROLL_DOWN,
    SCROLL_UP,
    BACK,
    ;

    companion object {
        fun parse(raw: String?): BrowserAction? = when (raw?.trim()?.lowercase()) {
            "click", "tap" -> CLICK
            "type", "fill" -> TYPE
            "select" -> SELECT
            "submit" -> SUBMIT
            "scroll_down" -> SCROLL_DOWN
            "scroll_up" -> SCROLL_UP
            "back" -> BACK
            else -> null
        }
    }

    /** Actions that need an element to act on; the rest are page-level. */
    val needsElement: Boolean
        get() = this == CLICK || this == TYPE || this == SELECT || this == SUBMIT

    /** Actions that carry a payload. */
    val needsValue: Boolean
        get() = this == TYPE || this == SELECT
}

/**
 * A page as the *engine* sees it — raw, unbudgeted, with engine-native selectors.
 *
 * The tool layer never forwards [RawElement.selector] to the model. It is mapped to an
 * opaque integer handle first, exactly like `som_id` hides pixel bounds in
 * `perceive_screen`. Keeping selectors server-side means a page can change its markup
 * without the model's vocabulary changing.
 */
data class BrowserPage(
    val url: String,
    val title: String,
    val text: String,
    val elements: List<RawElement>,
)

/**
 * One repeated block on a page — a product card, a search result, a job listing.
 *
 * [text] is always populated even when [fields] is empty. Field extraction is best-effort
 * (a page's markup owes us nothing), so the raw text is the floor the model can always fall
 * back to. Returning fields ONLY would mean a page whose markup we misread yields nothing at
 * all, which is worse than yielding prose.
 */
data class ExtractedRow(
    val text: String,
    /** Absolute URL if the block is a link — what makes "open the third result" possible. */
    val link: String? = null,
    /** Best-effort named values, e.g. price or rating. May be empty. */
    val fields: Map<String, String> = emptyMap(),
)

/**
 * A captured page image, plus whether it is worth believing.
 *
 * [uniform] exists because of a specific silent-failure mode: a hardware-accelerated
 * WebView can draw **blank** into a software canvas on some devices. That produces a
 * perfectly valid PNG of one flat colour, which base64-encodes to a long healthy
 * looking string — so no check on the *payload* can catch it. A VLM handed that image
 * reads "empty page" and the agent confidently acts on nothing.
 *
 * It is a flag and NOT a failure on purpose. A genuinely blank page is legitimate
 * (`about:blank`, a white page mid-navigation), so refusing to return the image would
 * misreport a working engine as a broken one. The agent is told the capture is suspect
 * and can fall back to `browser_read`, which is the better tool for that case anyway.
 */
data class BrowserCapture(
    /** Base64 PNG, no line wrapping. */
    val pngBase64: String,
    /** Every sampled pixel was the same colour — treat the image as unreliable. */
    val uniform: Boolean = false,
)

data class RawElement(
    /** Engine-native handle: a CSS selector on the web backends, a node path on the a11y backend. */
    val selector: String,
    /** link | button | input | textarea | select | checkbox | radio */
    val role: String,
    /** Accessible name or visible text. May be blank for icon-only controls. */
    val label: String,
    /** Current value for form controls. */
    val value: String? = null,
    /**
     * Disabled controls are TAGGED, never dropped. A dropped node just becomes a hole the
     * model can't reason about — the same lesson the perceive toggle-state fix landed.
     */
    val disabled: Boolean = false,
)

sealed interface BrowserResult<out T> {
    data class Success<T>(val value: T) : BrowserResult<T>
    data class Failure(val kind: BrowserErrorKind, val message: String) : BrowserResult<Nothing>
}

enum class BrowserErrorKind {
    /** The requested engine isn't wired on this platform / build. */
    UNAVAILABLE,

    /** URL rejected before any network hit. */
    INVALID_URL,

    /** Navigation failed (DNS, TLS, HTTP error page). */
    LOAD_FAILED,

    /** Engine did not settle within the budget. */
    TIMEOUT,

    /** `read`/`act` called before any `open`. */
    NO_PAGE,

    /**
     * A scratch page hit a login wall. Actionable: the agent should retry with
     * `session: "mine"` rather than flailing at a sign-in form it can never satisfy.
     */
    REQUIRES_SESSION,

    /** The element vanished, or the action was rejected in-page. */
    ACT_FAILED,

    /**
     * The call itself was malformed — a tab index that does not exist, a missing argument.
     *
     * Distinct from the others because it is the only kind that says *the agent* is wrong
     * rather than the page or the network. Retrying it unchanged is guaranteed to fail, so
     * the hint tells the model how to obtain a valid argument instead of suggesting a retry.
     */
    BAD_REQUEST,
}
