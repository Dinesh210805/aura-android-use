package com.aura.aura_ui.data.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Swiggy shapes below are verbatim from a real APK read — see
 * docs/deeplink-audit/2026-07-24-deeplink-discovery-audit.md (F3). Its whole
 * shortcuts.xml is capabilities with zero <shortcut> elements.
 */
class AppActionSynthesizerTest {

    private val swiggy = AppActionInfo(
        name = "actions.intent.ORDER_MENU_ITEM",
        urlTemplate = "swiggy://explore{?dish,restaurant,cuisine,location}",
        parameterKeys = listOf("restaurant", "location", "dish", "cuisine"),
    )

    @Test
    fun `capability with a url-template becomes an app_action entry`() {
        val entries = AppActionSynthesizer.synthesize("in.swiggy.android", listOf(swiggy))
        assertEquals(1, entries.size)
        assertEquals("app_action", entries[0].source)
    }

    @Test
    fun `url-template is passed through verbatim for the agent to fill`() {
        val entries = AppActionSynthesizer.synthesize("in.swiggy.android", listOf(swiggy))
        assertEquals("swiggy://explore{?dish,restaurant,cuisine,location}", entries[0].uriTemplate)
    }

    @Test
    fun `example uri is the concrete prefix before the first slot`() {
        val entries = AppActionSynthesizer.synthesize("in.swiggy.android", listOf(swiggy))
        assertEquals("swiggy://explore", entries[0].exampleUri)
        assertEquals("swiggy", entries[0].scheme)
    }

    @Test
    fun `label is humanised and names the fillable slots`() {
        val entries = AppActionSynthesizer.synthesize("in.swiggy.android", listOf(swiggy))
        val label = entries[0].label
        assertTrue("verb should be readable: $label", label.startsWith("Order menu item"))
        assertTrue("slots should be listed: $label", label.contains("dish"))
    }

    @Test
    fun `ordering capabilities require confirmation - they can spend money`() {
        val entries = AppActionSynthesizer.synthesize("in.swiggy.android", listOf(swiggy))
        assertTrue(entries[0].requiresConfirmation)
    }

    @Test
    fun `read-only capabilities do not require confirmation`() {
        val entries = AppActionSynthesizer.synthesize(
            "org.telegram.messenger",
            listOf(
                AppActionInfo(
                    "actions.intent.GET_THING",
                    "tg://search{?query}",
                    listOf("query"),
                ),
            ),
        )
        assertFalse(entries[0].requiresConfirmation)
    }

    @Test
    fun `capability without a url-template is skipped - nothing to open`() {
        val entries = AppActionSynthesizer.synthesize(
            "org.telegram.messenger",
            listOf(
                AppActionInfo("actions.intent.GET_ACCOUNT", null, emptyList()),
                AppActionInfo("actions.intent.GET_BARCODE", "  ", emptyList()),
            ),
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `blocked schemes never surface`() {
        val entries = AppActionSynthesizer.synthesize(
            "com.example.app",
            listOf(AppActionInfo("actions.intent.OPEN_APP_FEATURE", "content://x{?y}", listOf("y"))),
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `duplicate templates collapse`() {
        val entries = AppActionSynthesizer.synthesize("in.swiggy.android", listOf(swiggy, swiggy))
        assertEquals(1, entries.size)
    }

    @Test
    fun `a template with no slots still yields an openable entry`() {
        val entries = AppActionSynthesizer.synthesize(
            "com.example.app",
            listOf(AppActionInfo("actions.intent.OPEN_APP_FEATURE", "myapp://home", emptyList())),
        )
        assertEquals("myapp://home", entries[0].exampleUri)
    }
}
