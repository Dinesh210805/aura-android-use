package com.aura.aura_ui.agent.memory

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Pure parsing + rendering, plus the default-on-read contract of the store. */
@RunWith(RobolectricTestRunner::class)
class UserProfileTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Test fun `parse trims dedupes case-insensitively and keeps the first spelling`() {
        // The user's own capitalisation is what survives — including a language they typed in
        // their own script, which no normaliser could case-fold correctly anyway.
        assertEquals(listOf("English", "tamil"), parseLanguages(" English , tamil ,  TAMIL "))
    }

    @Test fun `parse caps the list`() {
        val raw = (1..20).joinToString(",") { "lang$it" }
        assertEquals(UserProfile.MAX_LANGUAGES, parseLanguages(raw).size)
    }

    @Test fun `parse of blank input yields nothing`() {
        assertTrue(parseLanguages("  ,  , ").isEmpty())
    }

    @Test fun `store defaults to english and tamil until set`() {
        assertEquals(listOf("English", "Tamil"), UserProfileStore(ctx).load().languages)
    }

    @Test fun `cleared list stays cleared and does not resurrect the default`() {
        val store = UserProfileStore(ctx)
        store.setLanguages(emptyList())
        assertTrue(store.load().languages.isEmpty())
    }

    @Test fun `store round-trips name and languages`() {
        val store = UserProfileStore(ctx)
        store.setName("Dinesh")
        store.setLanguages(listOf("Tamil", "English"))
        val loaded = store.load()
        assertEquals("Dinesh", loaded.name)
        assertEquals(listOf("Tamil", "English"), loaded.languages)
    }

    @Test fun `blank name goes back to the unnamed persona`() {
        val store = UserProfileStore(ctx)
        store.setName("Dinesh")
        store.setName("  ")
        assertEquals(null, store.load().name)
    }

    @Test fun `render names the person and pins the language boundary`() {
        val text = ProfileBlock.render(UserProfile("Dinesh", listOf("Tamil", "English")))
        assertTrue(text.contains("Dinesh"))
        assertTrue(text.contains("Tamil, English"))
        assertTrue(text.contains("Default to Tamil"))
        assertTrue(text.contains("not on the list"))
    }

    @Test fun `render is empty when nothing is known`() {
        assertEquals("", ProfileBlock.render(UserProfile(name = null, languages = emptyList())))
    }

    @Test fun `render works with languages but no name`() {
        val text = ProfileBlock.render(UserProfile(name = null, languages = listOf("English")))
        assertTrue(text.contains("English"))
        assertTrue(text.contains("Languages they know"))
    }
}
