package com.aura.mcp.tools

import com.aura.mcp.bridge.BrowserSessionMode
import com.aura.mcp.bridge.BrowserTab
import com.aura.mcp.bridge.BrowserTabAction
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `browser_tabs` — the tool that exists so the agent can *compare*.
 *
 * Every other browser tool is fine with one page at a time. Comparing is the intention a
 * single surface structurally cannot express: with one WebView the agent must navigate
 * away from the first site to see the second, and a search results page is frequently not
 * reproducible (session, ranking, stock), so re-opening it to re-read can silently
 * disagree with what was being compared.
 */
class BrowserTabsTest {

    private val scratch = BrowserSessionMode.SCRATCH

    // ── action parsing ───────────────────────────────────────────────────

    @Test
    fun `known actions parse`() {
        assertEquals(BrowserTabAction.OPEN, BrowserTabAction.parse("open"))
        assertEquals(BrowserTabAction.SWITCH, BrowserTabAction.parse("switch"))
        assertEquals(BrowserTabAction.CLOSE, BrowserTabAction.parse("close"))
        assertEquals(BrowserTabAction.LIST, BrowserTabAction.parse("list"))
    }

    @Test
    fun `parsing tolerates the shapes a model actually emits`() {
        assertEquals(BrowserTabAction.OPEN, BrowserTabAction.parse("  OPEN "))
        assertEquals(BrowserTabAction.OPEN, BrowserTabAction.parse("new"))
        assertEquals(BrowserTabAction.SWITCH, BrowserTabAction.parse("Activate"))
    }

    @Test
    fun `an unrecognised action falls back to the harmless one`() {
        // The default is load-bearing rather than tidy. A typo that fell through to OPEN or
        // CLOSE would navigate or destroy a surface the agent was mid-way through using;
        // falling through to LIST costs one wasted call and tells the model what exists.
        assertEquals(BrowserTabAction.LIST, BrowserTabAction.parse("swtich"))
        assertEquals(BrowserTabAction.LIST, BrowserTabAction.parse(null))
        assertEquals(BrowserTabAction.LIST, BrowserTabAction.parse(""))
    }

    // ── payload ──────────────────────────────────────────────────────────

    private fun tabs(vararg titles: String, activeIndex: Int = 0) =
        titles.mapIndexed { i, t ->
            BrowserTab(index = i, title = t, url = "https://$t.example", active = i == activeIndex)
        }

    @Test
    fun `the listing names every tab and which one is in front`() {
        val payload = BrowserPayload.tabs(tabs("amazon", "flipkart", activeIndex = 1), 1, null, scratch)

        val list = payload["tabs"]!!.jsonArray
        assertEquals(2, list.size)
        assertEquals("amazon", list[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(1, payload["active_index"]!!.jsonPrimitive.content.toInt())
        // Only the active tab carries the flag — an omitted false costs no tokens.
        assertNull(list[0].jsonObject["active"])
        assertEquals("true", list[1].jsonObject["active"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a listing with no page attached carries no page`() {
        // action="list" must not mint handles: bumping the generation on a read-only call
        // would silently invalidate the el_ids the agent is holding from its last read.
        val payload = BrowserPayload.tabs(tabs("amazon"), 0, null, scratch)

        assertNull(payload["page"])
    }

    @Test
    fun `switching returns the page so the agent need not read again`() {
        val snapshot = PageSnapshot(
            url = "https://flipkart.example",
            title = "Flipkart",
            text = "results",
            elements = emptyList(),
            handles = emptyMap(),
            generation = 7,
            totalElements = 0,
            textTruncated = false,
            elementsTruncated = false,
            looksLikeLoginWall = false,
        )

        val payload = BrowserPayload.tabs(tabs("amazon", "flipkart", activeIndex = 1), 1, snapshot, scratch)

        val page = payload["page"]!!.jsonObject
        assertEquals("Flipkart", page["title"]!!.jsonPrimitive.content)
        // The generation travels WITH the elements it belongs to, never at the top level of
        // a result whose subject is the tab list.
        assertEquals(7, page["generation"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `an empty tab set says so instead of looking like a bug`() {
        val payload = BrowserPayload.tabs(emptyList(), 0, null, scratch)

        assertEquals(0, payload["open_tabs"]!!.jsonPrimitive.content.toInt())
        assertTrue(payload["summary"]!!.jsonPrimitive.content.contains("No tabs open"))
    }

    @Test
    fun `the summary tells the model that other tools follow the active tab`() {
        // Without this the model has no way to know that browser_read after a switch reads
        // the NEW tab — the most likely misunderstanding of a multi-surface tool.
        val summary = BrowserPayload.tabs(tabs("a", "b"), 0, null, scratch)["summary"]!!
            .jsonPrimitive.content

        assertTrue(summary.contains("active tab"))
    }
}

/**
 * The `browser_handoff` payload — the shape the agent's waiting loop turns on.
 */
class BrowserHandoffPayloadTest {

    private val scratch = BrowserSessionMode.SCRATCH

    @Test
    fun `a running handoff is not reported as a failure`() {
        // Load-bearing. A handoff in progress is neither success nor failure, but a model
        // handed success=false treats it as something to retry — which here means opening
        // a second window over the one the user is typing their password into.
        val payload = BrowserPayload.handoff(
            com.aura.mcp.bridge.HandoffState(handedOff = true, prompt = "Sign in to Naukri"),
            snapshot = null,
            mode = scratch,
        )

        assertEquals("true", payload["success"]!!.jsonPrimitive.content)
        assertEquals("true", payload["waiting_for_user"]!!.jsonPrimitive.content)
    }

    @Test
    fun `while waiting, the agent is told to poll and never to ask for a password`() {
        val summary = BrowserPayload.handoff(
            com.aura.mcp.bridge.HandoffState(handedOff = true, prompt = "Sign in"),
            null,
            scratch,
        )["summary"]!!.jsonPrimitive.content

        assertTrue(summary.contains("check=true"))
        assertTrue(summary.contains("password"))
    }

    @Test
    fun `a finished handoff warns that the page may be somewhere else entirely`() {
        // The user logs in and lands on a dashboard, not the form. An agent that resumes
        // against its pre-handoff mental model acts on elements that no longer exist.
        val summary = BrowserPayload.handoff(
            com.aura.mcp.bridge.HandoffState(
                handedOff = false,
                prompt = "Sign in",
                endedBy = com.aura.mcp.bridge.HandoffEnd.USER_DONE,
            ),
            null,
            scratch,
        )["summary"]!!.jsonPrimitive.content

        assertTrue(summary.contains("changed completely"))
    }

    @Test
    fun `a window that vanished is not reported as the user finishing`() {
        // An OEM killing the overlay, or the user swiping it away, is not consent that the
        // login happened. Saying otherwise makes the agent proceed as though signed in.
        val summary = BrowserPayload.handoff(
            com.aura.mcp.bridge.HandoffState(
                handedOff = false,
                prompt = "Sign in",
                endedBy = com.aura.mcp.bridge.HandoffEnd.WINDOW_GONE,
            ),
            null,
            scratch,
        )["summary"]!!.jsonPrimitive.content

        assertTrue(summary.contains("may not have been"))
    }
}
