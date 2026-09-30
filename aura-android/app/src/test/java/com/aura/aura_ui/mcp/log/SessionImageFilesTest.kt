package com.aura.aura_ui.mcp.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The device found this: every `screenshots/<idx>.png` in a pulled session actually began
 * `ff d8 ff e0` — a JPEG wearing a PNG extension, because the capture bridge hands back
 * `base64Jpeg` through a field named `base64Png`. Consumers survived on magic-byte sniffing,
 * but a log that misstates its own format cannot be read programmatically with confidence.
 */
class SessionImageFilesTest {

    @get:Rule val temp = TemporaryFolder()

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private val pngBytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    @Test
    fun `jpeg bytes are named jpg`() {
        assertEquals("jpg", imageExtensionFor(jpegBytes))
    }

    @Test
    fun `png bytes are named png`() {
        assertEquals("png", imageExtensionFor(pngBytes))
    }

    @Test
    fun `bytes too short to identify fall back to png rather than throwing`() {
        assertEquals("png", imageExtensionFor(byteArrayOf()))
        assertEquals("png", imageExtensionFor(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `a jpg screenshot is found`() {
        val dir = temp.newFolder("session")
        File(dir, "screenshots").mkdirs()
        File(File(dir, "screenshots"), "3.jpg").writeBytes(jpegBytes)

        assertEquals("3.jpg", rawScreenshotFile(dir, 3)?.name)
        assertEquals("screenshots/3.jpg", rawScreenshotRelativePath(dir, 3))
    }

    @Test
    fun `a session recorded before the rename still opens`() {
        // Everything already on the device is `.png`; the fix must not orphan it.
        val dir = temp.newFolder("session")
        File(dir, "screenshots").mkdirs()
        File(File(dir, "screenshots"), "0.png").writeBytes(jpegBytes)

        assertEquals("0.png", rawScreenshotFile(dir, 0)?.name)
        assertEquals("screenshots/0.png", rawScreenshotRelativePath(dir, 0))
    }

    @Test
    fun `an uncaptured index resolves to nothing`() {
        val dir = temp.newFolder("session")
        File(dir, "screenshots").mkdirs()

        assertNull(rawScreenshotFile(dir, 7))
        assertNull(rawScreenshotRelativePath(dir, 7))
    }

    @Test
    fun `both stored image extensions are recognised and nothing else is`() {
        assertTrue(isSessionImageName("4.jpg"))
        assertTrue(isSessionImageName("4.PNG"))
        assertFalse(isSessionImageName("metadata.json"))
        assertFalse(isSessionImageName("4"))
    }
}
