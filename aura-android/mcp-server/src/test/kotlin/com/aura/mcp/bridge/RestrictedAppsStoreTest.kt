package com.aura.mcp.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RestrictedAppsStore — the user-managed "AURA stays out of this app" list
 * behind the Restricted Apps settings screen. Persistence is injected as
 * load/save lambdas so the store is pure JVM here and SharedPreferences-backed
 * on device (mirrors [TrustedClientsStoreTest]'s approach).
 */
class RestrictedAppsStoreTest {

    private var persisted: String? = null

    private fun store() = RestrictedAppsStore(
        load = { persisted },
        save = { persisted = it },
    )

    private fun entry(
        pkg: String,
        tier: RestrictedTier = RestrictedTier.DECLINE_ONLY,
        source: RestrictedAppSource = RestrictedAppSource.USER_ADDED,
        category: RestrictedAppCategory = RestrictedAppCategory.OTHER,
    ) = RestrictedAppEntry(packageName = pkg, appName = pkg, category = category, tier = tier, source = source)

    @Test
    fun `upsert adds a new entry and it is contained`() {
        val s = store()
        s.upsert(entry("com.bank.app"))

        assertTrue(s.contains("com.bank.app"))
        assertEquals(1, s.entries.value.size)
    }

    @Test
    fun `upsert replaces the existing entry for the same package`() {
        val s = store()
        s.upsert(entry("com.bank.app", tier = RestrictedTier.DECLINE_ONLY))
        s.upsert(entry("com.bank.app", tier = RestrictedTier.DISABLE_ACCESSIBILITY))

        assertEquals(1, s.entries.value.size)
        assertEquals(RestrictedTier.DISABLE_ACCESSIBILITY, s.entryFor("com.bank.app")?.tier)
    }

    @Test
    fun `remove drops one entry, leaves others`() {
        val s = store()
        s.upsert(entry("com.bank.app"))
        s.upsert(entry("com.auth.app"))

        s.remove("com.bank.app")

        assertFalse(s.contains("com.bank.app"))
        assertTrue(s.contains("com.auth.app"))
        assertEquals(1, s.entries.value.size)
    }

    @Test
    fun `updateTier changes tier on an existing entry`() {
        val s = store()
        s.upsert(entry("com.bank.app", tier = RestrictedTier.DECLINE_ONLY))

        s.updateTier("com.bank.app", RestrictedTier.DISABLE_ACCESSIBILITY)

        assertEquals(RestrictedTier.DISABLE_ACCESSIBILITY, s.entryFor("com.bank.app")?.tier)
    }

    @Test
    fun `updateTier on unknown package is a no-op`() {
        val s = store()
        s.updateTier("ghost.app", RestrictedTier.DISABLE_ACCESSIBILITY)
        assertTrue(s.entries.value.isEmpty())
    }

    @Test
    fun `mergeSuggestions adds only packages not already present`() {
        val s = store()
        s.upsert(entry("com.bank.app", tier = RestrictedTier.DISABLE_ACCESSIBILITY, source = RestrictedAppSource.USER_ADDED))

        s.mergeSuggestions(
            listOf(
                entry("com.bank.app", tier = RestrictedTier.DECLINE_ONLY, source = RestrictedAppSource.LLM_SUGGESTED),
                entry("com.crypto.app", source = RestrictedAppSource.LLM_SUGGESTED),
            ),
        )

        // Existing user choice for com.bank.app is untouched — suggestion never overwrites.
        assertEquals(RestrictedTier.DISABLE_ACCESSIBILITY, s.entryFor("com.bank.app")?.tier)
        assertEquals(RestrictedAppSource.USER_ADDED, s.entryFor("com.bank.app")?.source)
        // The genuinely new suggestion was added.
        assertTrue(s.contains("com.crypto.app"))
        assertEquals(2, s.entries.value.size)
    }

    @Test
    fun `clearAll empties the list`() {
        val s = store()
        s.upsert(entry("com.bank.app"))
        s.upsert(entry("com.auth.app"))

        s.clearAll()

        assertTrue(s.entries.value.isEmpty())
    }

    @Test
    fun `persists across instances`() {
        store().upsert(entry("com.bank.app", tier = RestrictedTier.DISABLE_ACCESSIBILITY))

        val reloaded = store()
        assertTrue(reloaded.contains("com.bank.app"))
        assertEquals(RestrictedTier.DISABLE_ACCESSIBILITY, reloaded.entryFor("com.bank.app")?.tier)
    }

    @Test
    fun `corrupt persisted json is treated as empty, not a crash`() {
        persisted = "{not json"
        val s = store()
        assertTrue(s.entries.value.isEmpty())
        s.upsert(entry("com.bank.app"))
        assertTrue(store().contains("com.bank.app"))
    }

    @Test
    fun `entryFor returns null for unknown or blank package`() {
        val s = store()
        assertNull(s.entryFor("ghost.app"))
        assertNull(s.entryFor(null))
        assertFalse(s.contains(""))
    }
}
