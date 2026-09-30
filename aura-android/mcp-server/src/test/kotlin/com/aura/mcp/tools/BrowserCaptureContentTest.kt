package com.aura.mcp.tools

import com.aura.mcp.bridge.BrowserCapture
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 2026-07-31 (browser automation) — the `browser_screenshot` blank-capture contract.
 *
 * This exists because of how the *previous* guard failed. `captureBase64Png` used to check
 * `encoded.isBlank()` — whether the base64 *string* was empty — which can never be true for
 * a real bitmap, blank or not. It survived indefinitely for one reason: no test ever handed
 * it a blank capture, so the branch was never executed.
 *
 * The warning path here has the same shape. It only triggers on a device whose WebView fails
 * to draw, which is exactly the input a test will never produce by accident. So it is
 * produced on purpose.
 */
class BrowserCaptureContentTest {

    private val png = "iVBORw0KGgoAAAANSUhEUg=="

    @Test
    fun `an ordinary capture is just the image`() {
        val content = captureContent(BrowserCapture(pngBase64 = png, uniform = false))

        assertEquals(1, content.size, "a healthy capture must not carry a warning")
        assertTrue(content.single() is ImageContent)
    }

    @Test
    fun `a flat capture still returns the image`() {
        // Flagged, NOT refused. A genuinely blank page is legitimate (about:blank, a page
        // mid-navigation), so withholding the image would misreport a working engine as a
        // broken one.
        val content = captureContent(BrowserCapture(pngBase64 = png, uniform = true))

        val image = content.filterIsInstance<ImageContent>().single()
        assertEquals(png, image.data)
    }

    @Test
    fun `a flat capture carries a warning that names the fallback tool`() {
        val content = captureContent(BrowserCapture(pngBase64 = png, uniform = true))

        val warning = content.filterIsInstance<TextContent>().single().text.orEmpty()

        // Both halves matter. The model must be told the image is untrustworthy AND given
        // somewhere to go — an image alone gives a VLM no way to tell "the page is empty"
        // from "the WebView failed to draw", and it will confidently describe either.
        assertTrue(warning.contains("flat colour"), "warning must say why: '$warning'")
        assertTrue(warning.contains("browser_read"), "warning must name the fallback: '$warning'")
    }

    @Test
    fun `the image comes first so a client that reads one content item gets the picture`() {
        val content = captureContent(BrowserCapture(pngBase64 = png, uniform = true))

        assertTrue(content.first() is ImageContent)
    }
}
