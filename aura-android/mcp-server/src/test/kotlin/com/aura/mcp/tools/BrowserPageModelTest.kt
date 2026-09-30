package com.aura.mcp.tools

import com.aura.mcp.bridge.BrowserPage
import com.aura.mcp.bridge.RawElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The page model is the seam between "what the engine scraped" and "what the model
 * is allowed to see". Two properties matter and both are cheap to get wrong:
 *
 *  1. **Selectors never escape.** The model addresses elements by opaque integer,
 *     exactly like `som_id` in `perceive_screen`. If a CSS selector leaks into a
 *     tool result, the model starts inventing selectors and the abstraction is dead.
 *  2. **Results stay inside a token budget.** A news homepage has thousands of
 *     nodes; handing that to a Groq-backed agent at 30k TPM burns the run.
 */
class BrowserPageModelTest {

    private fun page(
        elements: List<RawElement>,
        text: String = "Hello world",
        url: String = "https://example.com",
    ) = BrowserPage(url = url, title = "Example", text = text, elements = elements)

    private fun link(n: Int, label: String = "Link $n") =
        RawElement(selector = "#a$n", role = "link", label = label)

    // ── numbering ──────────────────────────────────────────────────────

    @Test
    fun `elements are numbered sequentially from 1 in document order`() {
        val snap = BrowserPageModel.snapshot(page(listOf(link(1), link(2), link(3))), generation = 7)

        assertEquals(listOf(1, 2, 3), snap.elements.map { it.elId })
        assertEquals(listOf("Link 1", "Link 2", "Link 3"), snap.elements.map { it.label })
    }

    @Test
    fun `handle map carries the selector so the model never has to`() {
        val snap = BrowserPageModel.snapshot(page(listOf(link(1), link(2))), generation = 1)

        assertEquals("#a1", snap.handles[1])
        assertEquals("#a2", snap.handles[2])
    }

    @Test
    fun `snapshot carries the generation it was minted under`() {
        val snap = BrowserPageModel.snapshot(page(listOf(link(1))), generation = 42)

        assertEquals(42, snap.generation)
    }

    // ── label hygiene ──────────────────────────────────────────────────

    @Test
    fun `unlabeled controls get a synthetic label instead of being dropped`() {
        // Icon-only buttons are common and often the only way forward on a page.
        // Dropping them would leave the model a hole it cannot reason about — the
        // same failure mode the perceive toggle-state fix corrected.
        val snap = BrowserPageModel.snapshot(
            page(listOf(RawElement(selector = "#x", role = "button", label = "   "))),
            generation = 1,
        )

        assertEquals(1, snap.elements.size)
        assertEquals("(unlabeled button)", snap.elements.first().label)
    }

    @Test
    fun `labels have runs of whitespace collapsed`() {
        val snap = BrowserPageModel.snapshot(
            page(listOf(RawElement(selector = "#x", role = "link", label = "Buy\n\n   now  "))),
            generation = 1,
        )

        assertEquals("Buy now", snap.elements.first().label)
    }

    @Test
    fun `disabled controls are tagged not dropped`() {
        val snap = BrowserPageModel.snapshot(
            page(
                listOf(
                    RawElement(selector = "#ok", role = "button", label = "Continue", disabled = true),
                ),
            ),
            generation = 1,
        )

        assertEquals(1, snap.elements.size)
        assertTrue(snap.elements.first().disabled)
    }

    // ── budgeting ──────────────────────────────────────────────────────

    @Test
    fun `element list is capped and the truncation is reported`() {
        val many = (1..200).map { link(it) }

        val snap = BrowserPageModel.snapshot(page(many), generation = 1, maxElements = 50)

        assertEquals(50, snap.elements.size)
        assertTrue(snap.elementsTruncated)
        assertEquals(200, snap.totalElements)
    }

    @Test
    fun `an under-budget page is not marked truncated`() {
        val snap = BrowserPageModel.snapshot(page(listOf(link(1))), generation = 1, maxElements = 50)

        assertFalse(snap.elementsTruncated)
        assertFalse(snap.textTruncated)
    }

    @Test
    fun `page text is truncated to the budget and flagged`() {
        val long = "word ".repeat(5_000)

        val snap = BrowserPageModel.snapshot(page(emptyList(), text = long), generation = 1, maxTextChars = 100)

        assertTrue(snap.text.length <= 100)
        assertTrue(snap.textTruncated)
    }

    @Test
    fun `truncation does not split a word in half`() {
        val snap = BrowserPageModel.snapshot(
            page(emptyList(), text = "alpha bravo charlie delta"),
            generation = 1,
            maxTextChars = 14,
        )

        // "alpha bravo ch" would be the naive cut; we must stop at a boundary.
        assertEquals("alpha bravo", snap.text)
    }

    @Test
    fun `page text has whitespace normalised`() {
        val snap = BrowserPageModel.snapshot(
            page(emptyList(), text = "line one\n\n\n   line two"),
            generation = 1,
        )

        assertEquals("line one line two", snap.text)
    }

    // ── login-wall detection ───────────────────────────────────────────

    @Test
    fun `a scratch page showing a password field is flagged as needing the real session`() {
        // This is the single most useful hint the model can get: a scratch browser
        // can NEVER satisfy a login wall, so "try harder" is always wrong and
        // "retry with session=mine" is always right.
        val snap = BrowserPageModel.snapshot(
            page(listOf(RawElement(selector = "#p", role = "password", label = "Password"))),
            generation = 1,
        )

        assertTrue(snap.looksLikeLoginWall)
    }

    @Test
    fun `an ordinary page is not flagged as a login wall`() {
        val snap = BrowserPageModel.snapshot(page(listOf(link(1))), generation = 1)

        assertFalse(snap.looksLikeLoginWall)
    }

    // ── values ─────────────────────────────────────────────────────────

    @Test
    fun `form values survive into the snapshot`() {
        val snap = BrowserPageModel.snapshot(
            page(listOf(RawElement(selector = "#q", role = "input", label = "Search", value = "kotlin"))),
            generation = 1,
        )

        assertEquals("kotlin", snap.elements.first().value)
    }

    @Test
    fun `absent values stay null rather than becoming empty strings`() {
        val snap = BrowserPageModel.snapshot(page(listOf(link(1))), generation = 1)

        assertNull(snap.elements.first().value)
    }
}
