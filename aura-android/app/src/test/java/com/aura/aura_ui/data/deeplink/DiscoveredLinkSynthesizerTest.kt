package com.aura.aura_ui.data.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveredLinkSynthesizerTest {

    private fun filter(
        exported: Boolean = true,
        actions: List<String> = listOf("android.intent.action.VIEW"),
        categories: List<String> = listOf("android.intent.category.BROWSABLE"),
        schemes: List<String> = emptyList(),
        hosts: List<String> = emptyList(),
        pathPrefixes: List<String> = emptyList(),
    ) = ManifestFilterInfo(exported, actions, categories, schemes, hosts, pathPrefixes)

    @Test
    fun `custom scheme filter becomes a discovered entry`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(listOf(filter(schemes = listOf("myapp"))))
        assertEquals(1, entries.size)
        assertEquals("myapp://", entries[0].exampleUri)
        assertEquals("myapp", entries[0].scheme)
        assertEquals("discovered", entries[0].source)
    }

    @Test
    fun `scheme plus host builds full example uri`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(filter(schemes = listOf("myapp"), hosts = listOf("profile"))),
        )
        assertEquals("myapp://profile/", entries[0].exampleUri)
        assertEquals("profile", entries[0].host)
    }

    @Test
    fun `path prefix is appended to example uri`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(filter(schemes = listOf("https"), hosts = listOf("example.com"), pathPrefixes = listOf("/items"))),
        )
        assertEquals("https://example.com/items", entries[0].exampleUri)
    }

    @Test
    fun `non-exported activities are skipped`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(filter(exported = false, schemes = listOf("myapp"))),
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `filters without ACTION_VIEW are skipped`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(filter(actions = listOf("android.intent.action.SEND"), schemes = listOf("myapp"))),
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `blocked schemes are never emitted`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(
                filter(schemes = listOf("file")),
                filter(schemes = listOf("content")),
                filter(schemes = listOf("javascript")),
                filter(schemes = listOf("intent")),
                filter(schemes = listOf("data")),
                filter(schemes = listOf("android-app")),
            ),
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `web schemes without a host are junk and skipped`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(filter(schemes = listOf("https")), filter(schemes = listOf("http"))),
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `custom schemes rank before web schemes`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(
                filter(schemes = listOf("https"), hosts = listOf("example.com")),
                filter(schemes = listOf("myapp")),
            ),
        )
        assertEquals("myapp://", entries[0].exampleUri)
        assertEquals("https://example.com/", entries[1].exampleUri)
    }

    @Test
    fun `duplicate example uris collapse`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(filter(schemes = listOf("myapp")), filter(schemes = listOf("myapp"))),
        )
        assertEquals(1, entries.size)
    }

    @Test
    fun `output is capped at the entry limit`() {
        val many = (1..50).map { filter(schemes = listOf("scheme$it")) }
        val entries = DiscoveredLinkSynthesizer.synthesize(many, maxEntries = 20)
        assertEquals(20, entries.size)
    }

    // ── F4: multi-prefix, ranking, honest templates, wildcard hosts ──────────
    // Shapes mirror a real device read — see
    // docs/deeplink-audit/2026-07-24-deeplink-discovery-audit.md (Spotify
    // declares 92 pathPrefixes, 17 pathPatterns, and a "*" host).

    private fun webFilter(
        pathPrefixes: List<String> = emptyList(),
        pathPatterns: List<String> = emptyList(),
        hosts: List<String> = listOf("open.spotify.com"),
    ) = ManifestFilterInfo(
        exported = true,
        actions = listOf("android.intent.action.VIEW"),
        categories = listOf("android.intent.category.BROWSABLE"),
        schemes = listOf("https"),
        hosts = hosts,
        pathPrefixes = pathPrefixes,
        pathPatterns = pathPatterns,
    )

    @Test
    fun `every path prefix surfaces, not just the first`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(webFilter(pathPrefixes = listOf("/album", "/search", "/artist"))),
        )
        val uris = entries.map { it.exampleUri }
        assertTrue("/search must survive", uris.any { it.endsWith("/search") })
        assertTrue("/album must survive", uris.any { it.endsWith("/album") })
        assertTrue("/artist must survive", uris.any { it.endsWith("/artist") })
    }

    @Test
    fun `generic destinations outrank auth and checkout plumbing`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(
                webFilter(
                    pathPrefixes = listOf(
                        "/account/parental-consent/create-managed-account",
                        "/checkout/unified-checkout",
                        "/search",
                    ),
                ),
            ),
        )
        assertEquals("https://open.spotify.com/search", entries[0].exampleUri)
    }

    @Test
    fun `shallower paths outrank deeper ones`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(webFilter(pathPrefixes = listOf("/app/browse/featured", "/browse"))),
        )
        assertEquals("https://open.spotify.com/browse", entries[0].exampleUri)
    }

    @Test
    fun `paths per host are capped so one app cannot flood the budget`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(webFilter(pathPrefixes = (1..30).map { "/p$it" })),
        )
        assertEquals(DiscoveredLinkSynthesizer.MAX_PATHS_PER_HOST, entries.size)
    }

    @Test
    fun `wildcard host is dropped - it only ever resolves to a browser`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(webFilter(hosts = listOf("*", "open.spotify.com"))),
        )
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.none { it.exampleUri.contains("://*") })
    }

    @Test
    fun `template is claimed only when a pathPattern proves a deeper segment`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(webFilter(pathPrefixes = listOf("/album"), pathPatterns = listOf("/album/.*"))),
        )
        assertEquals("https://open.spotify.com/album/{id}", entries[0].uriTemplate)
    }

    @Test
    fun `no template when nothing proves a deeper segment`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(webFilter(pathPrefixes = listOf("/search"))),
        )
        assertEquals(null, entries[0].uriTemplate)
    }

    @Test
    fun `an unrelated pathPattern does not grant a template`() {
        val entries = DiscoveredLinkSynthesizer.synthesize(
            listOf(webFilter(pathPrefixes = listOf("/search"), pathPatterns = listOf("/album/.*"))),
        )
        assertEquals(null, entries[0].uriTemplate)
    }
}
