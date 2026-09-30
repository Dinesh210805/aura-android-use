package com.aura.mcp.tools

import com.aura.mcp.bridge.ExtractedRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 2026-07-31 — `browser_extract`: structured data out in ONE call.
 *
 * The point of the tool, restated because it drives every choice here: an agent pays a full
 * LLM round trip per call, so reading five products naively costs five `browser_read`s plus
 * the model parsing prose out of each. `browser_extract` returns the repeated blocks a page
 * is already made of, so the same task costs one call.
 *
 * This file covers the projection — budgeting and truncation. The DOM heuristic that finds
 * the repeated blocks lives in `PageScript` and can only be proven on a device.
 */
class BrowserExtractModelTest {

    private fun rows(n: Int, text: String = "Item") =
        (1..n).map { ExtractedRow(text = "$text $it", link = null, fields = emptyMap()) }

    @Test
    fun `rows come back in page order`() {
        val out = BrowserExtractModel.project(rows(3), maxRows = 10, maxCharsPerRow = 100)

        assertEquals(listOf("Item 1", "Item 2", "Item 3"), out.rows.map { it.text })
    }

    @Test
    fun `too many rows are capped`() {
        val out = BrowserExtractModel.project(rows(50), maxRows = 20, maxCharsPerRow = 100)

        assertEquals(20, out.rows.size)
    }

    @Test
    fun `capping is REPORTED and never silent`() {
        // The whole doctrine of this codebase's budgeting: a silently trimmed result reads
        // to the model as a complete one, so it answers "there are 20 products" when there
        // were 50. Truncation the model cannot see is worse than truncation.
        val out = BrowserExtractModel.project(rows(50), maxRows = 20, maxCharsPerRow = 100)

        assertTrue(out.truncated, "capped 50 rows to 20 without saying so")
        assertEquals(50, out.totalFound)
    }

    @Test
    fun `an untruncated result says so`() {
        val out = BrowserExtractModel.project(rows(3), maxRows = 20, maxCharsPerRow = 100)

        assertTrue(!out.truncated)
        assertEquals(3, out.totalFound)
    }

    @Test
    fun `an over-long row is trimmed rather than dropped`() {
        // Dropping it would leave a hole the model cannot reason about — the same lesson
        // the perceive toggle-state fix landed for disabled nodes.
        val long = ExtractedRow(text = "x".repeat(500), link = null, fields = emptyMap())

        val out = BrowserExtractModel.project(listOf(long), maxRows = 10, maxCharsPerRow = 100)

        assertEquals(1, out.rows.size)
        assertEquals(100, out.rows.single().text.length)
        assertTrue(out.truncated, "trimmed a row without saying so")
    }

    @Test
    fun `no repeated structure is an empty result and not an error`() {
        // A page with no list is a normal page. Reporting it as a failure would push the
        // agent into retrying something that can never succeed.
        val out = BrowserExtractModel.project(emptyList(), maxRows = 20, maxCharsPerRow = 100)

        assertTrue(out.rows.isEmpty())
        assertTrue(!out.truncated)
        assertEquals(0, out.totalFound)
    }
}
