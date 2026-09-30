package com.aura.mcp.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Spec 2026-07-31 — `browser_upload`, and the security property it turns on.
 *
 * A `<input type="file">` does nothing in a WebView without
 * `WebChromeClient.onShowFileChooser`. But that callback fires whenever **any** page opens
 * a file picker — including a page AURA merely visited, which the user never asked to give
 * a file to.
 *
 * So the file is never chosen in response to the page. The agent arms exactly one file, for
 * exactly one chooser, and anything else is cancelled. Without that, implementing upload
 * would hand every visited site a way to make the browser attach a file of its choosing.
 */
class PendingUploadTest {

    @Test
    fun `an armed file is handed to the next chooser`() {
        val pending = PendingUpload()
        pending.arm("content://docs/resume.pdf")

        assertEquals("content://docs/resume.pdf", pending.consume())
    }

    @Test
    fun `it is ONE shot — a second chooser gets nothing`() {
        // The property that matters. A page that opens a second picker after the agent's
        // upload must not receive the same file again.
        val pending = PendingUpload()
        pending.arm("content://docs/resume.pdf")
        pending.consume()

        assertNull(pending.consume())
    }

    @Test
    fun `a chooser nobody armed gets nothing`() {
        // The hostile case: a visited page pops a file picker on its own. Nothing was
        // armed, so nothing is handed over and the chooser is cancelled.
        assertNull(PendingUpload().consume())
    }

    @Test
    fun `arming again replaces the previous file rather than queueing it`() {
        // A queue would let an abandoned upload fire later against an unrelated page.
        val pending = PendingUpload()
        pending.arm("content://docs/old.pdf")
        pending.arm("content://docs/new.pdf")

        assertEquals("content://docs/new.pdf", pending.consume())
        assertNull(pending.consume())
    }
}
