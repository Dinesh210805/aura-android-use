package com.aura.aura_ui.agent.research

import com.aura.mcp.bridge.BrowserAction
import com.aura.mcp.bridge.BrowserBridge
import com.aura.mcp.bridge.BrowserCapture
import com.aura.mcp.bridge.BrowserErrorKind
import com.aura.mcp.bridge.BrowserPage
import com.aura.mcp.bridge.BrowserResult
import com.aura.mcp.bridge.BrowserSessionMode
import com.aura.mcp.bridge.BrowserTabAction
import com.aura.mcp.bridge.BrowserTabs
import com.aura.mcp.bridge.ExtractedRow
import com.aura.mcp.bridge.FindOutcome
import com.aura.mcp.bridge.HandoffState
import com.aura.mcp.bridge.RawElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebResearchTest {

    /** Pages keyed by what produced them: the opened URL's prefix, or the clicked selector. */
    private class FakeBrowser(
        val onOpen: (String) -> BrowserPage?,
        val onClick: Map<String, BrowserPage> = emptyMap(),
        val reads: MutableList<BrowserPage> = mutableListOf(),
    ) : BrowserBridge {
        val opened = mutableListOf<Pair<String, Boolean>>()
        val clicked = mutableListOf<String>()

        override fun isAvailable(mode: BrowserSessionMode) = true
        override suspend fun open(url: String, mode: BrowserSessionMode, visible: Boolean): BrowserResult<BrowserPage> {
            opened += url to visible
            return onOpen(url)?.let { BrowserResult.Success(it) }
                ?: BrowserResult.Failure(BrowserErrorKind.LOAD_FAILED, "offline")
        }
        override suspend fun act(mode: BrowserSessionMode, selector: String, action: BrowserAction, value: String?): BrowserResult<BrowserPage> {
            clicked += selector
            return onClick[selector]?.let { BrowserResult.Success(it) }
                ?: BrowserResult.Failure(BrowserErrorKind.ACT_FAILED, "no such element")
        }
        override suspend fun read(mode: BrowserSessionMode): BrowserResult<BrowserPage> =
            reads.removeFirstOrNull()?.let { BrowserResult.Success(it) }
                ?: BrowserResult.Failure(BrowserErrorKind.NO_PAGE, "done")

        override suspend fun find(mode: BrowserSessionMode, text: String): BrowserResult<FindOutcome> = TODO()
        override suspend fun waitFor(mode: BrowserSessionMode, text: String?, timeoutMs: Long): BrowserResult<BrowserPage> = TODO()
        override suspend fun extract(mode: BrowserSessionMode, fields: List<String>): BrowserResult<List<ExtractedRow>> = TODO()
        override suspend fun upload(mode: BrowserSessionMode, selector: String, fileUri: String): BrowserResult<BrowserPage> = TODO()
        override suspend fun screenshot(mode: BrowserSessionMode): BrowserResult<BrowserCapture> = TODO()
        override suspend fun tabs(mode: BrowserSessionMode, action: BrowserTabAction, url: String?, index: Int?): BrowserResult<BrowserTabs> = TODO()
        override suspend fun handoff(mode: BrowserSessionMode, prompt: String): BrowserResult<HandoffState> = TODO()
        override suspend fun handoffState(mode: BrowserSessionMode): BrowserResult<HandoffState> = TODO()
        override suspend fun close(mode: BrowserSessionMode): BrowserResult<Unit> = TODO()
    }

    private fun page(url: String, text: String, vararg elements: RawElement) = BrowserPage(url, "t", text, elements.toList())
    private fun el(selector: String, role: String, label: String) = RawElement(selector, role, label)

    private val hints = listOf("Maps", "google", "OnePlus")

    // Shaped like the real results page from the 2026-09-24 09:23 run.
    private val results = page(
        "https://www.google.com/search?hl=en&q=x",
        "AI Mode All Images AI Overview To add stops use &waypoints=A%7CB …",
        el("#ai", "link", "AI Mode"),
        el("#more", "button", "Show more AI Overview"),
        el("#yt", "link", "YouTube https://m.youtube.com watch"),
        el("#dev", "link", "Google for Developers https://developers.google.com Get Started | Maps URLs"),
    )
    private val expanded = results.copy(text = "AI Overview To add stops use &waypoints=A%7CB, up to 9 stops. Full answer.")
    private val devPage = page("https://developers.google.com/maps/documentation/urls/get-started", "waypoints: separate with |")

    @Test fun `overview is expanded, then the official cited source is read - never shown on screen`() = runTest {
        val b = FakeBrowser(onOpen = { results }, onClick = mapOf("#more" to expanded, "#dev" to devPage))
        val r = WebResearch(b).search("How do I add stops in Google Maps?", hints)

        assertTrue(b.opened.single().first.startsWith("https://www.google.com/search?hl=en&q="))
        assertFalse("research must never appear on screen", b.opened.single().second)
        assertEquals(listOf("#more", "#dev"), b.clicked)
        assertEquals(listOf("Google AI Overview", "developers.google.com"), r.sources.map { it.where })
        assertTrue(r.sources[0].text.contains("Full answer"))
        assertTrue(r.sources[1].text.contains("waypoints"))
    }

    @Test fun `no overview - the AI Mode tab is tapped and read once the answer stops growing`() = runTest {
        val plain = page("https://www.google.com/search?q=x", "All Images results", el("#ai", "link", "AI Mode"))
        val growing = listOf("Thinking", "Here is how", "Here is how to add stops", "Here is how to add stops")
            .map { page("https://www.google.com/search?udm=50", it) }
        val b = FakeBrowser(onOpen = { plain }, onClick = mapOf("#ai" to growing.first()), reads = growing.drop(1).toMutableList())
        val r = WebResearch(b).search("q", hints)

        assertEquals(listOf("#ai"), b.clicked)
        assertEquals("Google AI Mode", r.sources.single().where)
        assertEquals("Here is how to add stops", r.sources.single().text)
    }

    @Test fun `no overview and no AI Mode tab - the results page itself is the source`() = runTest {
        val plain = page("https://www.google.com/search?q=x", "ten blue links about maps")
        val r = WebResearch(FakeBrowser(onOpen = { plain })).search("q", hints)
        assertEquals("Google results", r.sources.single().where)
    }

    @Test fun `a Google block page is reported as blocked with no sources`() = runTest {
        val sorry = page("https://www.google.com/sorry/index?continue=x", "unusual traffic from your computer network")
        val r = WebResearch(FakeBrowser(onOpen = { sorry })).search("q", hints)
        assertTrue(r.blocked)
        assertTrue(r.sources.isEmpty())
    }

    @Test fun `a page that will not load is no sources, never an exception`() = runTest {
        val r = WebResearch(FakeBrowser(onOpen = { null })).search("q", hints)
        assertTrue(r.sources.isEmpty())
        assertFalse(r.blocked)
    }

    @Test fun `the question is a natural sentence with the app, version and phone - and no person`() {
        val facts = com.aura.aura_ui.agent.DeviceFacts("OnePlus Nord 4", "OnePlus", "CPH2661", "16", null)
        val app = InstalledApp("Maps", "com.google.android.apps.maps", "26.38.02")
        val q = ResearchText.question("navigate in Google Maps with multiple stops", app, facts)!!
        assertEquals(
            "How do I navigate in Google Maps with multiple stops on my OnePlus Nord 4 (Android 16, Maps 26.38)? " +
                "Is there a link that opens it directly?",
            q,
        )
    }
}
