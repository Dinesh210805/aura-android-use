package com.aura.mcp.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        pending.arm("content://media/external/file/41")

        assertEquals("content://media/external/file/41", pending.consume())
    }

    @Test
    fun `it is ONE shot — a second chooser gets nothing`() {
        // The property that matters. A page that opens a second picker after the agent's
        // upload must not receive the same file again.
        val pending = PendingUpload()
        pending.arm("content://media/external/file/41")
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
        pending.arm("content://media/external/file/7")
        pending.arm("content://media/external/file/8")

        assertEquals("content://media/external/file/8", pending.consume())
        assertNull(pending.consume())
    }

    @Test
    fun `only MediaStore content URIs can be armed`() {
        // find_files only returns content://media/… URIs. Anything else could point the
        // chooser at AURA's own storage (prefs, databases) and upload it to the page.
        val pending = PendingUpload()
        listOf(
            "file:///data/data/com.aura.aura_ui/shared_prefs/aura_prefs.xml",
            "/data/user/0/com.aura.aura_ui/shared_prefs/aura_prefs.xml",
            "content://com.aura.aura_ui.fileprovider/files/x",
            "content://media.evil/external/file/1",
            "content://media@other/external/file/1",
            "javascript:alert(1)",
            "",
        ).forEach { uri ->
            assertFalse(pending.arm(uri), uri)
            assertNull(pending.consume(), uri)
        }
        assertTrue(pending.arm("CONTENT://media/external/images/media/12"))
    }
}
