package com.aura.aura_ui.mcp.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNameMatchTest {

    private val maps = "Maps" to "com.google.android.apps.maps"
    private val google = "Google" to "com.google.android.googlequicksearchbox"

    private fun score(query: String, app: Pair<String, String>) = AppNameMatch.score(query, app.first, app.second)

    @Test fun `"Google Maps" finds the app labelled Maps - its package supplies the brand`() {
        // 2026-09-24 Maps run: launch_app("Google Maps") failed because only the label was checked.
        assertTrue(score("Google Maps", maps) > 0)
        assertEquals("Google alone does not say maps", 0, score("Google Maps", google))
    }

    @Test fun `the old tiers still rank above a word match`() {
        assertEquals(100, score("maps", maps))
        assertTrue(score("Maps", maps) > score("google maps", maps))
        assertTrue("a word match beats a bare package substring", score("google maps", maps) > score("apps.ma", maps))
        assertEquals(40, score("apps.ma", maps))
    }

    @Test fun `blank query matches nothing`() {
        assertEquals(0, score("  ", maps))
    }

    @Test fun `words covered counts the goal words an app accounts for`() {
        val goal = "Open Google Maps and add stops"
        assertEquals(2, AppNameMatch.wordsCovered(goal, maps.first, maps.second))
        assertEquals(1, AppNameMatch.wordsCovered(goal, google.first, google.second))
    }
}
