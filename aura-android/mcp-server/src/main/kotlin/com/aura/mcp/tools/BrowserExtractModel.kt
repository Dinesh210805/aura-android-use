package com.aura.mcp.tools

import com.aura.mcp.bridge.ExtractedRow

/**
 * Spec 2026-07-31 — budgeting for `browser_extract`.
 *
 * The tool exists because an agent pays a full LLM round trip per call: reading five
 * products naively costs five `browser_read`s plus the model parsing prose out of each,
 * where `browser_extract` returns the repeated blocks a page is already made of in one.
 *
 * That only holds if the result stays small, hence budgeting — and budgeting only stays
 * honest if truncation is **reported**. A silently trimmed result reads to the model as a
 * complete one, so it answers "there are twenty products" when the page had fifty. Same
 * false-success class this codebase fights everywhere else: the failure is not being
 * wrong, it is being wrong *confidently*.
 */
object BrowserExtractModel {

    data class Projection(
        val rows: List<ExtractedRow>,
        /** True if rows were dropped OR any row's text was cut. Surfaced to the model. */
        val truncated: Boolean,
        /** How many repeated blocks the page actually had, before capping. */
        val totalFound: Int,
    )

    fun project(rows: List<ExtractedRow>, maxRows: Int, maxCharsPerRow: Int): Projection {
        val kept = rows.take(maxRows)
        var trimmedAnyRow = false

        val budgeted = kept.map { row ->
            if (row.text.length <= maxCharsPerRow) {
                row
            } else {
                trimmedAnyRow = true
                // Trimmed, never dropped. A dropped row is a hole the model cannot reason
                // about — the same lesson the perceive toggle-state fix landed for disabled
                // nodes, which are tagged rather than removed.
                row.copy(text = row.text.take(maxCharsPerRow))
            }
        }

        return Projection(
            rows = budgeted,
            truncated = rows.size > maxRows || trimmedAnyRow,
            totalFound = rows.size,
        )
    }
}
