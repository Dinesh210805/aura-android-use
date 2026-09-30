package com.aura.aura_ui.mcp.bridge.browser

/**
 * Spec 2026-08-06 — *the browser plane becomes a browser*.
 *
 * What the chrome needs from the engine, and nothing else.
 *
 * The window renders tabs, a URL and a progress bar; it does not own any of them. Keeping
 * that traffic behind an interface is what lets `BrowserWindow` stay a *window* — it
 * never touches a `WebView`'s history or the tab list directly, so there is exactly one
 * place (the bridge) where "which page is in front" can be decided, and no way for the
 * chrome to disagree with the agent about it.
 *
 * Every method is called on the main thread. Implementations must assume the user tapped
 * the control *while the agent may be mid-task*, and serialise accordingly.
 */
// Public, not internal, for one mechanical reason: AppBrowserBridge is a public class, and
// a public class cannot implement an internal interface without its overrides "exposing" an
// internal type. Narrowing the bridge instead would ripple through every caller of
// AppBrowserBridge.shared(). The package is still an implementation detail by convention.
interface BrowserChromeHost {

    /** Left to right, exactly as the strip should render them. */
    fun chromeTabs(): List<ChromeTab>

    /** Index into [chromeTabs] of the page currently in front. */
    fun chromeActiveIndex(): Int

    fun onChromeBack()
    fun onChromeForward()
    fun onChromeReload()

    /** [raw] is whatever the user typed — the host normalises it via BrowserChromeBehavior. */
    fun onChromeNavigate(raw: String)

    fun onChromeNewTab()
    fun onChromeSelectTab(index: Int)
    fun onChromeCloseTab(index: Int)

    /** The new-tab surface, not a configured start page. */
    fun onChromeHome()
}

/**
 * One tab, flattened for rendering.
 *
 * A snapshot rather than a live handle: the strip is rebuilt from these on every change,
 * so a chip can never hold a reference to a `WebView` the engine has since destroyed —
 * the crash the old code was one `browser_close` away from.
 */
data class ChromeTab(
    val title: String,
    val url: String,
    val secure: Boolean,
    /** 0..100. 100 means idle, not "finished a moment ago". */
    val progress: Int,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
) {
    val loading: Boolean get() = progress in 0 until 100
}
