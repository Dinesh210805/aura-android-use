package com.aura.aura_ui.mcp.bridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 2026-08-06 — *the browser plane becomes a browser*.
 *
 * The pure half of the browser chrome, split out for the same reason
 * `PausePillBehavior` is split out of `PausePillOverlay`: everything interesting about a
 * URL bar is a decision (*is this a URL or a search? is this connection safe? what do I
 * call a tab with no title?*), and none of those decisions need a `WindowManager` to be
 * checked. Keeping them here means they are testable at all.
 */
class BrowserChromeBehaviorTest {

    // ── URL vs. search ─────────────────────────────────────────────────
    //
    // The single most-used control in any browser, and the one with the most folklore.
    // The rule is deliberately conservative: guess "search" unless the text really looks
    // like a host, because a wrong search costs one extra tap while a wrong navigation
    // costs a DNS failure page and a confused user.

    @Test
    fun `a bare host becomes https`() {
        assertEquals("https://google.com", BrowserChromeBehavior.normalizeInput("google.com"))
    }

    @Test
    fun `an explicit scheme is left alone`() {
        assertEquals(
            "http://example.com/x",
            BrowserChromeBehavior.normalizeInput("http://example.com/x"),
        )
    }

    @Test
    fun `a host with a path and query survives intact`() {
        assertEquals(
            "https://example.com/a/b?q=1&r=2",
            BrowserChromeBehavior.normalizeInput("example.com/a/b?q=1&r=2"),
        )
    }

    @Test
    fun `words with a space are a search`() {
        val result = BrowserChromeBehavior.normalizeInput("cheap flights to goa")
        assertTrue(result!!.startsWith(BrowserChromeBehavior.SEARCH_PREFIX))
        assertTrue(result.contains("cheap+flights+to+goa") || result.contains("cheap%20flights"))
    }

    @Test
    fun `a single word with no dot is a search`() {
        val result = BrowserChromeBehavior.normalizeInput("gmail")
        assertTrue(result!!.startsWith(BrowserChromeBehavior.SEARCH_PREFIX))
    }

    /**
     * The case that makes a naive "contains a dot" rule wrong. A version number, a price
     * and a decimal all contain dots and none of them are hosts — the last label has to
     * look like a TLD, which means alphabetic and at least two characters.
     */
    @Test
    fun `a decimal number is a search, not a host`() {
        assertTrue(BrowserChromeBehavior.normalizeInput("3.5")!!.startsWith(BrowserChromeBehavior.SEARCH_PREFIX))
        assertTrue(BrowserChromeBehavior.normalizeInput("1.99")!!.startsWith(BrowserChromeBehavior.SEARCH_PREFIX))
        assertTrue(BrowserChromeBehavior.normalizeInput("v2.1")!!.startsWith(BrowserChromeBehavior.SEARCH_PREFIX))
    }

    /** A question that happens to end in a dot must not become a hostname. */
    @Test
    fun `a sentence is a search`() {
        assertTrue(
            BrowserChromeBehavior.normalizeInput("what is the capital of peru?")!!
                .startsWith(BrowserChromeBehavior.SEARCH_PREFIX),
        )
    }

    /**
     * Local addresses get http, not https. A dev server or a router at 192.168.x.x almost
     * never has a certificate, and https-first there is a guaranteed error page.
     */
    @Test
    fun `localhost and ip addresses get http`() {
        assertEquals("http://localhost:8080", BrowserChromeBehavior.normalizeInput("localhost:8080"))
        assertEquals("http://192.168.1.1", BrowserChromeBehavior.normalizeInput("192.168.1.1"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://example.com", BrowserChromeBehavior.normalizeInput("  example.com  "))
    }

    @Test
    fun `blank input is not a navigation at all`() {
        assertNull(BrowserChromeBehavior.normalizeInput(""))
        assertNull(BrowserChromeBehavior.normalizeInput("   "))
    }

    /** `javascript:` in a URL bar is the classic self-XSS delivery route. */
    @Test
    fun `dangerous schemes are refused, not navigated`() {
        assertNull(BrowserChromeBehavior.normalizeInput("javascript:alert(1)"))
        assertNull(BrowserChromeBehavior.normalizeInput("JavaScript:void(0)"))
        assertNull(BrowserChromeBehavior.normalizeInput("data:text/html,<h1>x"))
        assertNull(BrowserChromeBehavior.normalizeInput("file:///sdcard/secrets.txt"))
    }

    // ── what the bar shows ─────────────────────────────────────────────

    @Test
    fun `display url drops the scheme and the www`() {
        assertEquals("example.com", BrowserChromeBehavior.displayUrl("https://www.example.com"))
        assertEquals("example.com", BrowserChromeBehavior.displayUrl("https://example.com/"))
        assertEquals(
            "mail.google.com/mail/u/0",
            BrowserChromeBehavior.displayUrl("https://mail.google.com/mail/u/0"),
        )
    }

    @Test
    fun `display url of a blank page is a prompt, not an empty box`() {
        assertEquals(BrowserChromeBehavior.EMPTY_URL_HINT, BrowserChromeBehavior.displayUrl(""))
        assertEquals(BrowserChromeBehavior.EMPTY_URL_HINT, BrowserChromeBehavior.displayUrl("about:blank"))
    }

    @Test
    fun `security is https and nothing else`() {
        assertTrue(BrowserChromeBehavior.isSecure("https://example.com"))
        assertTrue(BrowserChromeBehavior.isSecure("HTTPS://EXAMPLE.COM"))
        assertFalse(BrowserChromeBehavior.isSecure("http://example.com"))
        assertFalse(BrowserChromeBehavior.isSecure(""))
    }

    // ── tab chips ──────────────────────────────────────────────────────

    @Test
    fun `a tab is named by its title`() {
        assertEquals("Gmail", BrowserChromeBehavior.tabTitle("Gmail", "https://mail.google.com"))
    }

    @Test
    fun `a titleless tab falls back to its host`() {
        assertEquals("mail.google.com", BrowserChromeBehavior.tabTitle(null, "https://mail.google.com/x"))
        assertEquals("mail.google.com", BrowserChromeBehavior.tabTitle("  ", "https://mail.google.com/x"))
    }

    @Test
    fun `a tab with nothing loaded is a new tab`() {
        assertEquals(BrowserChromeBehavior.NEW_TAB_LABEL, BrowserChromeBehavior.tabTitle(null, null))
        assertEquals(BrowserChromeBehavior.NEW_TAB_LABEL, BrowserChromeBehavior.tabTitle(null, "about:blank"))
    }

    @Test
    fun `long titles are ellipsised so one tab cannot eat the strip`() {
        val long = "A very long page title that would otherwise run off the screen entirely"
        val out = BrowserChromeBehavior.tabTitle(long, null, max = 18)
        assertEquals(18, out.length)
        assertTrue(out.endsWith("…"))
    }

    // ── insets ─────────────────────────────────────────────────────────
    //
    // The bug this file exists to prevent regressing. Measured on the test device
    // (1240×2772 @ 560dpi): statusBars claims 139px, navigationBars 56px. With
    // FLAG_LAYOUT_IN_SCREEN the window really does own those pixels, so the chrome rows
    // have to step out of them by hand or they render underneath the system bars.

    @Test
    fun `chrome pads itself out of the system bars`() {
        val padding = BrowserChromeBehavior.chromePadding(statusBarPx = 139, navBarPx = 56, imeHeightPx = 0)
        assertEquals(139, padding.top)
        assertEquals(56, padding.bottom)
    }

    /**
     * When the keyboard is up it replaces the nav bar rather than stacking with it — the
     * IME inset already includes whatever is behind it. Adding both would float the
     * bottom bar a nav-bar's height above the keyboard.
     */
    @Test
    fun `the keyboard replaces the nav bar rather than stacking with it`() {
        val padding = BrowserChromeBehavior.chromePadding(statusBarPx = 139, navBarPx = 56, imeHeightPx = 900)
        assertEquals(139, padding.top)
        assertEquals(900, padding.bottom)
    }

    @Test
    fun `negative or absent insets never become negative padding`() {
        val padding = BrowserChromeBehavior.chromePadding(statusBarPx = -5, navBarPx = 0, imeHeightPx = 0)
        assertEquals(0, padding.top)
        assertEquals(0, padding.bottom)
    }

    // ── which tab is in front after a close ────────────────────────────
    //
    // Extracted here after the chrome grew its own copy of this arithmetic and dropped a
    // case the engine's copy had. The failure was silent and total: close a tab BELOW the
    // active one and `coerceAtMost` alone leaves activeIndex pointing one tab too far
    // right, so the window kept showing tab B while every read, every back press and the
    // highlighted chip all addressed tab C. Nothing crashed and nothing logged.

    @Test
    fun `closing a tab below the active one shifts it down`() {
        // [A,B,C] active=B(1); close A -> [B,C], B is now index 0.
        assertEquals(0, BrowserChromeBehavior.activeIndexAfterClose(closed = 0, active = 1, remaining = 2))
    }

    @Test
    fun `closing a tab above the active one leaves it alone`() {
        // [A,B,C] active=B(1); close C -> [A,B], B is still index 1.
        assertEquals(1, BrowserChromeBehavior.activeIndexAfterClose(closed = 2, active = 1, remaining = 2))
    }

    /** Closing the tab you are on lands on its neighbour, not back at the start. */
    @Test
    fun `closing the active tab keeps the position`() {
        assertEquals(1, BrowserChromeBehavior.activeIndexAfterClose(closed = 1, active = 1, remaining = 3))
    }

    @Test
    fun `closing the last tab in the list steps back onto the new last`() {
        assertEquals(1, BrowserChromeBehavior.activeIndexAfterClose(closed = 2, active = 2, remaining = 2))
    }

    @Test
    fun `closing the only tab leaves a valid index`() {
        assertEquals(0, BrowserChromeBehavior.activeIndexAfterClose(closed = 0, active = 0, remaining = 0))
    }

    @Test
    fun `the result is always a valid index into what remains`() {
        for (remaining in 0..4) {
            for (active in 0..4) {
                for (closed in 0..4) {
                    val result = BrowserChromeBehavior.activeIndexAfterClose(closed, active, remaining)
                    assertTrue(
                        "closed=$closed active=$active remaining=$remaining gave $result",
                        result >= 0 && (remaining == 0 || result < remaining),
                    )
                }
            }
        }
    }

    // ── the tab chips only earn their space sometimes ──────────────────

    @Test
    fun `one tab does not justify a chip`() {
        assertFalse(BrowserChromeBehavior.shouldShowTabChips(1))
        assertFalse(BrowserChromeBehavior.shouldShowTabChips(0))
        assertTrue(BrowserChromeBehavior.shouldShowTabChips(2))
    }
}
