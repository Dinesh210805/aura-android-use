package com.aura.aura_ui.presentation.overlay

import com.aura.aura_ui.presentation.overlay.ReportMarkdown.Block
import com.aura.aura_ui.presentation.overlay.ReportMarkdown.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportMarkdownTest {

    @Test
    fun `parses the shapes a client report uses`() {
        val md = """
            ## Best pick: boAt Stone 352 Pro
            Highest rated of the three,
            under budget.

            | | Price | Rating |
            |---|---|---|
            | **Stone 352 Pro** | ₹1,499 | 4.3★ |
            | SoundDrum 1 | ₹937 | 4.1★ |

            - IPX7 on the Stone 352
            1. Open the listing
        """.trimIndent()

        assertEquals(
            listOf(
                Block.Heading(2, "Best pick: boAt Stone 352 Pro"),
                Block.Paragraph("Highest rated of the three, under budget."),
                Block.Table(
                    header = listOf("", "Price", "Rating"),
                    rows = listOf(
                        listOf("**Stone 352 Pro**", "₹1,499", "4.3★"),
                        listOf("SoundDrum 1", "₹937", "4.1★"),
                    ),
                ),
                Block.Bullet("•", "IPX7 on the Stone 352"),
                Block.Bullet("1.", "Open the listing"),
            ),
            ReportMarkdown.parse(md),
        )
    }

    @Test
    fun `ragged and oversized tables are padded and capped`() {
        val rows = (1..40).joinToString("\n") { "| r$it | a | b | c | d | e | f | g |" }
        val table = ReportMarkdown.parse("| x | y |\n|---|---|\n$rows").single() as Block.Table
        assertEquals(ReportMarkdown.MAX_COLS, table.header.size)
        assertEquals(ReportMarkdown.MAX_ROWS, table.rows.size)
        assertTrue(table.rows.all { it.size == ReportMarkdown.MAX_COLS })
    }

    @Test
    fun `junk never throws and falls through as text`() {
        assertEquals(emptyList<Block>(), ReportMarkdown.parse(""))
        assertEquals(listOf(Block.Paragraph("<b>hi</b> **open")), ReportMarkdown.parse("<b>hi</b> **open"))
        ReportMarkdown.parse("|\n|---\n| |".repeat(5_000)) // over MAX_CHARS, odd pipes
    }

    @Test
    fun `inline bold and code split out, unclosed markers stay literal`() {
        assertEquals(
            listOf(Span("Pick "), Span("this", bold = true), Span(" via "), Span("tap", code = true)),
            ReportMarkdown.spans("Pick **this** via `tap`"),
        )
        assertEquals(listOf(Span("a ** b")), ReportMarkdown.spans("a ** b"))
    }
}
