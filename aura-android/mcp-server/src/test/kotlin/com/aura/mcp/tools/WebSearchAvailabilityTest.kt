package com.aura.mcp.tools

import com.aura.mcp.bridge.WebSearchBridge
import com.aura.mcp.bridge.WebSearchResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `web_search` is the one tool AURA hides when its resource is missing, because a Tavily API key
 * has no in-run acquisition path — no tool can mint one, so a model that can see the tool could
 * never make it work. (The opposite case is `get_screenshot`, which must stay registered without
 * screen-capture consent: `request_screen_capture_permission` is right there in the same action
 * space, and hiding the tool it unlocks would make it pointless.)
 *
 * What is pinned here is the **failure** behaviour, which is where hiding a tool gets dangerous.
 */
class WebSearchAvailabilityTest {

    private class Bridge(private val configured: () -> Boolean) : WebSearchBridge {
        override fun isConfigured(): Boolean = configured()
        override suspend fun search(query: String, maxResults: Int, topic: String): WebSearchResult =
            error("not dispatched")
    }

    @Test
    fun `a configured bridge keeps the tool`() {
        assertTrue(webSearchIsAvailable(Bridge { true }))
    }

    @Test
    fun `only a definite no hides the tool`() {
        assertFalse(webSearchIsAvailable(Bridge { false }))
    }

    /**
     * The asymmetry that matters. This check runs at **registration**, inside
     * `McpServerBuilder.build` — a new caller at a new moment for a bridge whose implementation
     * reads a key store. If it throws and the exception escapes, the whole tool server dies and
     * takes every gesture and perception tool with it.
     *
     * Registering a `web_search` that then refuses costs one wasted call. Registering nothing
     * costs the product. So a throw must mean "keep the tool", never "hide it".
     */
    @Test
    fun `a bridge that throws keeps the tool rather than taking down the server`() {
        assertTrue(webSearchIsAvailable(Bridge { error("key store unavailable") }))
    }
}
