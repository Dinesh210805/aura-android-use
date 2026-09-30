package com.aura.aura_ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppLinksPreambleTest {

    private val catalog = File("src/main/assets/deeplink_catalog.json").readText()

    @Test fun `maps links come from the real catalog, the multi-stop route included`() {
        // The 2026-09-24 Maps run never called list_app_deeplinks, so the one-call route was
        // invisible; this note puts it in front of turn 1.
        val entries = AppLinksPreamble.catalogEntries(catalog, "com.google.android.apps.maps")
        assertTrue(entries.any { it.second.contains("waypoints={stops}") })
    }

    @Test fun `the note names the app, the tool and every link`() {
        val note = AppLinksPreamble.forApp("Maps", listOf("Search a place" to "https://www.google.com/maps/search/{query}"))!!
        assertTrue(note.contains("Maps"))
        assertTrue(note.contains("open_deeplink"))
        assertTrue(note.contains("- Search a place: https://www.google.com/maps/search/{query}"))
    }

    @Test fun `no links, no note`() {
        assertNull(AppLinksPreamble.forApp("Maps", emptyList()))
        assertEquals(emptyList<Pair<String, String>>(), AppLinksPreamble.catalogEntries(catalog, "com.example.unknown"))
        assertEquals(emptyList<Pair<String, String>>(), AppLinksPreamble.catalogEntries("not json", "com.whatsapp"))
    }

    @Test fun `a fixed uri is used when an entry has no template`() {
        val json = """{"apps":{"x.y":{"entries":[{"label":"Explore","uri":"https://x.y/explore"}]}}}"""
        assertEquals(listOf("Explore" to "https://x.y/explore"), AppLinksPreamble.catalogEntries(json, "x.y"))
    }
}
