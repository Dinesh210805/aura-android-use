package com.aura.aura_ui.data.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutEntrySynthesizerTest {

    private val pkg = "com.whatsapp"

    private fun shortcut(
        id: String = "new_chat",
        label: String? = "New chat",
        targetPackage: String? = pkg,
        action: String? = "android.intent.action.VIEW",
        dataUri: String? = null,
        targetClass: String? = "com.whatsapp.Main",
        enabled: Boolean = true,
    ) = StaticShortcutInfo(id, label, targetPackage, action, dataUri, targetClass, enabled)

    @Test
    fun `enabled shortcut becomes an app-shortcut entry`() {
        val entries = ShortcutEntrySynthesizer.synthesize(pkg, listOf(shortcut()))
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("app-shortcut://com.whatsapp/new_chat", e.exampleUri)
        assertEquals("New chat", e.label)
        assertEquals("shortcut", e.source)
        assertEquals("new_chat", e.shortcutId)
        assertEquals("app-shortcut", e.scheme)
        assertEquals(pkg, e.host)
    }

    @Test
    fun `disabled shortcuts are skipped`() {
        val entries = ShortcutEntrySynthesizer.synthesize(pkg, listOf(shortcut(enabled = false)))
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `shortcut targeting another package is skipped`() {
        val entries = ShortcutEntrySynthesizer.synthesize(
            pkg,
            listOf(shortcut(targetPackage = "com.evil.other")),
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `shortcut without an action is skipped`() {
        val entries = ShortcutEntrySynthesizer.synthesize(pkg, listOf(shortcut(action = null)))
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `blank label falls back to the shortcut id`() {
        val entries = ShortcutEntrySynthesizer.synthesize(pkg, listOf(shortcut(label = null)))
        assertEquals("new_chat", entries[0].label)
    }

    @Test
    fun `null targetPackage is trusted as own-package`() {
        // shortcuts.xml intents frequently omit targetPackage — the OS resolves
        // them against the owning app, so we must too.
        val entries = ShortcutEntrySynthesizer.synthesize(pkg, listOf(shortcut(targetPackage = null)))
        assertEquals(1, entries.size)
    }

    @Test
    fun `duplicate shortcut ids collapse to the first`() {
        val entries = ShortcutEntrySynthesizer.synthesize(
            pkg,
            listOf(shortcut(label = "First"), shortcut(label = "Second")),
        )
        assertEquals(1, entries.size)
        assertEquals("First", entries[0].label)
    }
}
