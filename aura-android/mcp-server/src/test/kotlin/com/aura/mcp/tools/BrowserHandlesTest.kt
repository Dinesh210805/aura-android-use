package com.aura.mcp.tools

import com.aura.mcp.bridge.BrowserPage
import com.aura.mcp.bridge.RawElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Handles are the browser analogue of `ScreenGeneration`: an integer the model
 * quotes back at us, valid only for the page state it was minted from.
 *
 * The failure this guards against is the expensive one — the page navigated, the
 * model still holds `el_id: 3` from the previous page, and acting on it clicks
 * something arbitrary. Blind action on a changed page is exactly the false-success
 * class of bug the screen-change-detection work was built to kill. Better to make
 * the agent re-read than to let it act on a stale map.
 */
class BrowserHandlesTest {

    private fun pageWith(vararg selectors: String) = BrowserPage(
        url = "https://example.com",
        title = "Example",
        text = "",
        elements = selectors.map { RawElement(selector = it, role = "link", label = it) },
    )

    @Test
    fun `first snapshot starts at generation 1`() {
        val handles = BrowserHandles()

        val snap = handles.mint(pageWith("#a"))

        assertEquals(1, snap.generation)
    }

    @Test
    fun `each mint advances the generation`() {
        val handles = BrowserHandles()

        handles.mint(pageWith("#a"))
        val second = handles.mint(pageWith("#b"))

        assertEquals(2, second.generation)
    }

    @Test
    fun `resolving a current handle yields its selector`() {
        val handles = BrowserHandles()
        handles.mint(pageWith("#first", "#second"))

        val resolved = handles.resolve(elId = 2)

        assertEquals(HandleResolution.Resolved("#second"), resolved)
    }

    @Test
    fun `a handle from a superseded generation is rejected as stale`() {
        val handles = BrowserHandles()
        handles.mint(pageWith("#old"))
        handles.mint(pageWith("#new"))

        // The model quotes generation 1 after we've moved to 2.
        val resolved = handles.resolve(elId = 1, generation = 1)

        assertTrue(resolved is HandleResolution.Stale)
    }

    @Test
    fun `omitting the generation trusts the current page`() {
        // Clients that don't echo the generation still work; they just lose the
        // staleness check. Requiring it would break the simple call path.
        val handles = BrowserHandles()
        handles.mint(pageWith("#only"))

        assertEquals(HandleResolution.Resolved("#only"), handles.resolve(elId = 1, generation = null))
    }

    @Test
    fun `an out-of-range handle is unknown not stale`() {
        val handles = BrowserHandles()
        handles.mint(pageWith("#a"))

        assertTrue(handles.resolve(elId = 99) is HandleResolution.Unknown)
    }

    @Test
    fun `resolving before any page has loaded is unknown`() {
        assertTrue(BrowserHandles().resolve(elId = 1) is HandleResolution.Unknown)
    }

    @Test
    fun `clear drops the map so a closed session cannot be acted on`() {
        val handles = BrowserHandles()
        handles.mint(pageWith("#a"))

        handles.clear()

        assertTrue(handles.resolve(elId = 1) is HandleResolution.Unknown)
    }
}
