package com.aura.mcp.tools

import com.aura.mcp.bridge.BrowserErrorKind
import com.aura.mcp.bridge.BrowserPage
import com.aura.mcp.bridge.BrowserSessionMode
import com.aura.mcp.bridge.RawElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The payload IS the model's view of the world, so its shape carries as much risk
 * as the logic behind it. Two things are asserted hard:
 *
 *  - **No selector ever appears in the wire format.** This is the abstraction that
 *    lets a page change its markup without changing the model's vocabulary.
 *  - **Uninteresting fields are omitted, not nulled.** 60 elements × `"value": null,
 *    "disabled": false` is a meaningful slice of a 30k TPM budget spent saying nothing.
 */
class BrowserPayloadTest {

    private fun snapshotOf(vararg elements: RawElement, text: String = "Body text") =
        BrowserPageModel.snapshot(
            BrowserPage(
                url = "https://example.com/x",
                title = "Example",
                text = text,
                elements = elements.toList(),
            ),
            generation = 3,
        )

    // ── page payload ───────────────────────────────────────────────────

    @Test
    fun `page payload carries url title generation and text`() {
        val json = BrowserPayload.page(snapshotOf(), BrowserSessionMode.SCRATCH)

        assertEquals("https://example.com/x", json["url"]?.jsonPrimitive?.content)
        assertEquals("Example", json["title"]?.jsonPrimitive?.content)
        assertEquals(3, json["generation"]?.jsonPrimitive?.content?.toInt())
        assertEquals("Body text", json["text"]?.jsonPrimitive?.content)
        assertTrue(json["success"]?.jsonPrimitive?.content.toBoolean())
    }

    @Test
    fun `selectors never reach the wire`() {
        val json = BrowserPayload.page(
            snapshotOf(RawElement(selector = "#secret-selector", role = "link", label = "Go")),
            BrowserSessionMode.SCRATCH,
        )

        assertFalse(json.toString().contains("secret-selector"))
        assertFalse(json.toString().contains("selector"))
    }

    @Test
    fun `elements expose el_id role and label`() {
        val json = BrowserPayload.page(
            snapshotOf(RawElement(selector = "#a", role = "button", label = "Buy")),
            BrowserSessionMode.SCRATCH,
        )

        val el = json["elements"]?.jsonArray?.first()?.jsonObject
        assertEquals(1, el?.get("el_id")?.jsonPrimitive?.content?.toInt())
        assertEquals("button", el?.get("role")?.jsonPrimitive?.content)
        assertEquals("Buy", el?.get("label")?.jsonPrimitive?.content)
    }

    @Test
    fun `null value and false disabled are omitted rather than emitted`() {
        val json = BrowserPayload.page(
            snapshotOf(RawElement(selector = "#a", role = "link", label = "Go")),
            BrowserSessionMode.SCRATCH,
        )

        val el = json["elements"]?.jsonArray?.first()?.jsonObject
        assertNull(el?.get("value"))
        assertNull(el?.get("disabled"))
    }

    @Test
    fun `present value and true disabled are emitted`() {
        val json = BrowserPayload.page(
            snapshotOf(
                RawElement(selector = "#q", role = "input", label = "Search", value = "kotlin", disabled = true),
            ),
            BrowserSessionMode.SCRATCH,
        )

        val el = json["elements"]?.jsonArray?.first()?.jsonObject
        assertEquals("kotlin", el?.get("value")?.jsonPrimitive?.content)
        assertTrue(el?.get("disabled")?.jsonPrimitive?.content.toBoolean())
    }

    @Test
    fun `truncation flags are omitted when nothing was truncated`() {
        val json = BrowserPayload.page(snapshotOf(), BrowserSessionMode.SCRATCH)

        assertNull(json["text_truncated"])
        assertNull(json["elements_truncated"])
    }

    // ── login wall ─────────────────────────────────────────────────────

    @Test
    fun `a login wall in scratch mode tells the agent to escalate to the real session`() {
        val json = BrowserPayload.page(
            snapshotOf(RawElement(selector = "#p", role = "password", label = "Password")),
            BrowserSessionMode.SCRATCH,
        )

        assertTrue(json["requires_session"]?.jsonPrimitive?.content.toBoolean())
        assertTrue(json["hint"]?.jsonPrimitive?.content?.contains("session=\"mine\"") == true)
    }

    @Test
    fun `a login wall in mine mode is not flagged - the user is already signed in there`() {
        val json = BrowserPayload.page(
            snapshotOf(RawElement(selector = "#p", role = "password", label = "Password")),
            BrowserSessionMode.MINE,
        )

        assertNull(json["requires_session"])
    }

    // ── failures ───────────────────────────────────────────────────────

    @Test
    fun `failure payload exposes a machine-readable kind alongside the message`() {
        val json = BrowserPayload.failure(
            BrowserErrorKind.LOAD_FAILED,
            "DNS failure",
            BrowserSessionMode.SCRATCH,
        )

        assertFalse(json["success"]?.jsonPrimitive?.content.toBoolean())
        assertEquals("load_failed", json["error_kind"]?.jsonPrimitive?.content)
        assertEquals("DNS failure", json["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `every failure kind carries an actionable hint`() {
        // An error the model can't act on just becomes a retry loop.
        for (kind in BrowserErrorKind.entries) {
            val json = BrowserPayload.failure(kind, "boom", BrowserSessionMode.SCRATCH)
            assertTrue(
                json["hint"]?.jsonPrimitive?.content?.isNotBlank() == true,
                "missing hint for $kind",
            )
        }
    }

    @Test
    fun `stale handles are structurally distinguishable and name the fix`() {
        val json = BrowserPayload.staleHandles(currentGeneration = 5, mode = BrowserSessionMode.SCRATCH)

        assertTrue(json["stale_handles"]?.jsonPrimitive?.content.toBoolean())
        assertEquals(5, json["current_generation"]?.jsonPrimitive?.content?.toInt())
        assertTrue(json["hint"]?.jsonPrimitive?.content?.contains("browser_read") == true)
    }

    @Test
    fun `unknown handle is distinct from stale handle`() {
        val json = BrowserPayload.unknownHandle(elId = 42, mode = BrowserSessionMode.SCRATCH)

        assertEquals("unknown_handle", json["error_kind"]?.jsonPrimitive?.content)
        assertNull(json["stale_handles"])
        assertTrue(json["error"]?.jsonPrimitive?.content?.contains("42") == true)
    }

    // ── session parsing ────────────────────────────────────────────────

    @Test
    fun `session defaults to scratch and only explicit mine escalates`() {
        assertEquals(BrowserSessionMode.SCRATCH, BrowserSessionMode.parse(null))
        assertEquals(BrowserSessionMode.SCRATCH, BrowserSessionMode.parse("scratch"))
        assertEquals(BrowserSessionMode.SCRATCH, BrowserSessionMode.parse("nonsense"))
        assertEquals(BrowserSessionMode.MINE, BrowserSessionMode.parse("MINE"))
        assertEquals(BrowserSessionMode.MINE, BrowserSessionMode.parse(" mine "))
    }
}
