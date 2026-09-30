package com.aura.mcp.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `browser_open` takes a URL straight from the model, so this is the point where a
 * prompt-injected or hallucinated target would enter the engine. Only absolute
 * http(s) URLs get through.
 *
 * `javascript:` is the sharp one: passed to a WebView it executes in the context of
 * whatever page is currently loaded, which turns "open this link" into arbitrary
 * script execution against a page that may hold the user's session. `file:` and
 * `content:` would read local storage. None of them are things a browsing tool ever
 * legitimately needs.
 */
class BrowserUrlGateTest {

    @Test
    fun `absolute http and https urls are accepted`() {
        assertTrue(isSupportedUrl("https://example.com"))
        assertTrue(isSupportedUrl("http://example.com/path?q=1"))
        assertTrue(isSupportedUrl("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun `script and local-file schemes are rejected`() {
        assertFalse(isSupportedUrl("javascript:alert(1)"))
        assertFalse(isSupportedUrl("JavaScript:alert(1)"))
        assertFalse(isSupportedUrl("file:///data/data/com.aura.aura_ui/databases/memory.db"))
        assertFalse(isSupportedUrl("content://com.android.contacts/contacts"))
        assertFalse(isSupportedUrl("data:text/html,<script>fetch('//evil')</script>"))
        assertFalse(isSupportedUrl("intent://scan/#Intent;scheme=zxing;end"))
    }

    @Test
    fun `relative or bare strings are rejected`() {
        assertFalse(isSupportedUrl("example.com"))
        assertFalse(isSupportedUrl("/search?q=hi"))
        assertFalse(isSupportedUrl(""))
        assertFalse(isSupportedUrl("   "))
    }

    @Test
    fun `a scheme with no host is rejected`() {
        assertFalse(isSupportedUrl("https://"))
        assertFalse(isSupportedUrl("https:///path"))
    }
}
