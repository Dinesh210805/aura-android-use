package com.aura.aura_ui.mcp.bridge.browser

import java.net.URLEncoder

/**
 * Spec 2026-08-06 — *the browser plane becomes a browser*.
 *
 * Every decision the browser chrome makes, with none of the `WindowManager` it makes them
 * in. Split out for the same reason `PausePillBehavior` is split out of
 * `PausePillOverlay`: view construction is not unit-testable on the JVM, but *"is this a
 * URL or a search?"* and *"how far down does the toolbar have to start?"* are pure
 * questions with right and wrong answers — and they are where the bugs live.
 */
internal object BrowserChromeBehavior {

    /**
     * DuckDuckGo rather than Google, for one specific reason: this is an embedded WebView,
     * and Google responds to those with sign-in interstitials and `disallowed_useragent`
     * refusals. A search box that lands on a nag screen is worse than no search box.
     */
    const val SEARCH_PREFIX = "https://duckduckgo.com/?q="

    /** Shown in the URL field when there is no page — an empty box tells the user nothing. */
    const val EMPTY_URL_HINT = "Search or enter address"

    const val NEW_TAB_LABEL = "New tab"

    /** How many characters a tab chip gets before it is ellipsised. */
    const val TAB_TITLE_MAX = 18

    /** The only two schemes the URL bar will navigate to. */
    private val NAVIGABLE_SCHEMES = setOf("http", "https")

    /**
     * Schemes that must be refused rather than passed through.
     *
     * `javascript:` in a URL bar is the classic self-XSS delivery route — "paste this in
     * the address bar" — and `data:`/`file:`/`content:` hand a page an origin it did not
     * earn. The engine already sets `allowFileAccess = false`; this closes the same door
     * from the side the user can type into.
     */
    private val REFUSED_SCHEMES = setOf("javascript", "data", "file", "content", "intent", "blob", "jar")

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):")
    private val HOST_LABEL = Regex("^[a-zA-Z0-9\\-_]+$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** Padding the chrome rows need in order to sit clear of the system bars. */
    data class ChromePadding(val top: Int, val bottom: Int)

    /**
     * Turn whatever the user typed into a URL to load, or null if it is not a navigation.
     *
     * The rule leans towards *search*. Guessing "search" for something that was a host
     * costs one extra tap; guessing "host" for something that was a search phrase costs a
     * DNS error page and the user's confidence that the bar works.
     */
    fun normalizeInput(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // An explicit scheme is a statement of intent — honour it or refuse it, but never
        // reinterpret it as a search. Note this deliberately does NOT treat "localhost:8080"
        // as scheme "localhost": only schemes we actually recognise short-circuit here, so
        // a host:port falls through to the host test below where it belongs.
        SCHEME.find(text)?.groupValues?.get(1)?.lowercase()?.let { scheme ->
            if (scheme in REFUSED_SCHEMES) return null
            if (scheme in NAVIGABLE_SCHEMES) return text
        }

        // A space is the one unambiguous signal. No hostname contains one.
        if (text.any { it.isWhitespace() }) return search(text)

        val authority = text.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringBefore(':')

        // Local addresses get http. A dev server or a router almost never has a
        // certificate, so https-first there is a guaranteed error page.
        if (host.equals("localhost", ignoreCase = true) || IPV4.matches(host)) return "http://$text"

        return if (looksLikeHost(host)) "https://$text" else search(text)
    }

    /**
     * Does this look like a hostname rather than a phrase that happens to contain a dot?
     *
     * The last label has to read as a TLD — alphabetic and at least two characters. That
     * single condition is what separates `google.com` from `3.5`, `1.99` and `v2.1`, all
     * of which pass a naive "contains a dot" test and none of which are hosts.
     */
    private fun looksLikeHost(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (labels.any { it.isEmpty() || !HOST_LABEL.matches(it) }) return false
        val tld = labels.last()
        return tld.length >= 2 && tld.all { it.isLetter() }
    }

    private fun search(query: String): String =
        SEARCH_PREFIX + URLEncoder.encode(query, "UTF-8")

    /**
     * The URL as a person should read it: no scheme, no `www.`, no trailing slash.
     *
     * Hiding the scheme is safe here only because [isSecure] drives a separate lock icon —
     * dropping `https://` without showing security somewhere else would remove the user's
     * only signal at exactly the moment (a login handoff) they most need it.
     */
    fun displayUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed == "about:blank") return EMPTY_URL_HINT
        return trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removeSuffix("/")
            .ifEmpty { EMPTY_URL_HINT }
    }

    /** https and nothing else. There is no partially-secure page worth a soft signal. */
    fun isSecure(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)

    /** What to write on a tab chip: its title, else its host, else "New tab". */
    fun tabTitle(title: String?, url: String?, max: Int = TAB_TITLE_MAX): String {
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { return ellipsise(it, max) }

        val target = url?.trim().orEmpty()
        if (target.isEmpty() || target == "about:blank") return NEW_TAB_LABEL

        val host = target
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')
            .substringBefore('?')
        return if (host.isEmpty()) NEW_TAB_LABEL else ellipsise(host, max)
    }

    /**
     * Public because the strip needs a *shorter* budget than [tabTitle]'s default.
     *
     * Measured on the test device: the window controls take 45% of a 354dp row, leaving
     * ~150dp for chips — at 18 characters a single chip fills the whole strip and pushes
     * every other tab out of view. The tooltip-length title is still right for the
     * new-tab panel's cards, which have a full row each, so the two callers pass
     * different maxima rather than sharing one compromise that suits neither.
     */
    fun ellipsise(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    /**
     * How far the chrome rows must inset themselves from the window's own edges.
     *
     * The window is `FLAG_LAYOUT_IN_SCREEN` at the display's real size, so it genuinely
     * owns the pixels behind the status and navigation bars — measured on the test device
     * (1240×2772 @ 560dpi) that is 139px at the top and 56px at the bottom, ~7% of the
     * screen. Nothing consumed those insets before this, which is why the banner rendered
     * 83% underneath the clock and the "I'm done" bar lost its lower half to the gesture
     * pill. The page still bleeds edge to edge; only the chrome steps aside.
     *
     * [imeHeightPx] **replaces** the nav bar rather than stacking with it: the IME inset
     * already spans everything behind the keyboard, so adding both would float the bottom
     * bar a nav-bar's height above it.
     */
    fun chromePadding(statusBarPx: Int, navBarPx: Int, imeHeightPx: Int): ChromePadding =
        ChromePadding(
            top = statusBarPx.coerceAtLeast(0),
            bottom = maxOf(navBarPx, imeHeightPx).coerceAtLeast(0),
        )

    /**
     * Which tab should be in front after [closed] is removed.
     *
     * Lives here because it was written twice and the two copies disagreed. The engine's
     * `browser_tabs close` had it right; the chrome's close button grew its own version
     * that used only `coerceAtMost` and so never decremented when a tab *below* the active
     * one went away. The result was silent and complete desync — the window kept rendering
     * the old tab while every read, every Back press and the highlighted chip addressed its
     * neighbour. One tested function, two call sites.
     *
     * The rule: keep the *same page* in front wherever possible. Closing tab 3 must not
     * quietly move the agent to a different site than the one it was working in.
     *
     * @param remaining how many tabs are left AFTER the removal.
     */
    fun activeIndexAfterClose(closed: Int, active: Int, remaining: Int): Int = when {
        remaining <= 0 -> 0
        closed < active -> (active - 1).coerceIn(0, remaining - 1)
        else -> active.coerceIn(0, remaining - 1)
    }

    /**
     * Whether the tab chips are worth their row.
     *
     * Governs the *chips* only — the row itself always shows, because it also carries the
     * AURA mark and the window controls. A single chip labelled with the page you are
     * already looking at is pure noise.
     */
    fun shouldShowTabChips(tabCount: Int): Boolean = tabCount > 1
}
