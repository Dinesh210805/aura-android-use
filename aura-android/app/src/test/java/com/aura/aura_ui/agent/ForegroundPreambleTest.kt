package com.aura.aura_ui.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPreambleTest {

    @Test
    fun `null package yields no preamble`() {
        assertNull(ForegroundPreamble.forForeground(packageName = null))
    }

    @Test
    fun `blank package yields no preamble`() {
        assertNull(ForegroundPreamble.forForeground(packageName = "   "))
    }

    @Test
    fun `AURA's own package yields no preamble`() {
        assertNull(
            ForegroundPreamble.forForeground(
                packageName = "com.aura.aura_ui",
                appLabel = "AURA",
                ownPackage = "com.aura.aura_ui",
            ),
        )
    }

    @Test
    fun `package without label still names the package`() {
        val note = ForegroundPreamble.forForeground(packageName = "com.whatsapp")
        assertTrue(note!!.contains("com.whatsapp"))
    }

    @Test
    fun `label and package both appear when label is known`() {
        val note = ForegroundPreamble.forForeground(
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
        )!!
        assertTrue(note.contains("WhatsApp"))
        assertTrue(note.contains("com.whatsapp"))
    }

    @Test
    fun `preamble tells the model not to relaunch and to start from this screen`() {
        val note = ForegroundPreamble.forForeground(
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
        )!!
        assertTrue("must warn against relaunching", note.contains("do not relaunch"))
        assertTrue("must say to start here", note.contains("start from this screen"))
        assertTrue("must not push the expensive look (doctrine: read_screen is default)", !note.contains("perceive_screen"))
    }

    @Test
    fun `blank label falls back to package only`() {
        val note = ForegroundPreamble.forForeground(
            packageName = "com.whatsapp",
            appLabel = "  ",
        )!!
        assertTrue(note.contains("com.whatsapp"))
    }

    @Test
    fun `a different foreground app than AURA still produces a preamble`() {
        val note = ForegroundPreamble.forForeground(
            packageName = "com.spotify.music",
            appLabel = "Spotify",
            ownPackage = "com.aura.aura_ui",
        )
        assertTrue(note != null && note.contains("Spotify"))
    }
}
