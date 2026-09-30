package com.aura.aura_ui.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deep-link catalog: stays valid JSON and covers the expanded app set (unit
 * tests run with the module as the working directory, like
 * [com.aura.aura_ui.agent.skills.BundledSkillAssetsTest]).
 */
class DeepLinkCatalogAssetTest {

    private fun catalogJson(): String {
        val f = File("src/main/assets/deeplink_catalog.json")
        assertTrue("deeplink_catalog.json not found from ${File(".").absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `catalog parses and has the expanded app set`() {
        val root = Json.parseToJsonElement(catalogJson()).jsonObject
        val apps = root["apps"]!!.jsonObject.keys
        val expected = setOf(
            "com.android.settings", "com.spotify.music", "com.google.android.youtube",
            "com.google.android.apps.maps", "com.whatsapp", "com.google.android.gm",
            "com.instagram.android", "org.telegram.messenger", "com.twitter.android",
            "com.android.vending", "com.android.chrome", "com.google.android.calendar",
            "com.flipkart.android", "com.google.android.apps.photos",
        )
        assertTrue("missing: ${expected - apps}", apps.containsAll(expected))
    }

    @Test
    fun `maps has a route with a start and stops - one link instead of a dozen taps`() {
        // The 2026-09-24 Maps run spent ~5 minutes tapping in a route this link opens directly.
        assertTrue(catalogJson().contains("maps/dir/?api=1&origin={origin}&destination={destination}&waypoints={stops}"))
    }

    @Test
    fun `no catalog entry points at a payment or banking app`() {
        val root = Json.parseToJsonElement(catalogJson()).jsonObject
        val apps = root["apps"]!!.jsonObject.keys
        val forbidden = listOf("paytm", "phonepe", "paisa", "npci", "bank", "wallet", "upi")
        assertTrue(apps.none { pkg -> forbidden.any { pkg.contains(it) } })
    }
}
