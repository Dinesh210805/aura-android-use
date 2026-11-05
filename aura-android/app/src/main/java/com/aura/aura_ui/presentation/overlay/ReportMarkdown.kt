package com.aura.aura_ui.presentation.overlay

/**
 * The small slice of markdown the run report card draws: headings, bullet and numbered lists,
 * pipe tables, paragraphs, and inline `**bold**` / `` `code` ``.
 *
 * - Contract: [parse] never throws; anything it does not recognise comes back as a paragraph.
 *   Input is capped at [MAX_CHARS], tables at [MAX_ROWS] rows and [MAX_COLS] columns.
 * - Why hand-rolled: the input is client-written (`end_session`'s `report`), so it is untrusted,
 *   and the card needs these five shapes laid out in Compose, not a full CommonMark engine.
 */
internal object ReportMarkdown {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Bullet(val marker: String, val text: String) : Block
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block
        data class Paragraph(val text: String) : Block
    }

    data class Span(val text: String, val bold: Boolean = false, val code: Boolean = false)

    const val MAX_CHARS = 6_000
    const val MAX_ROWS = 20
    const val MAX_COLS = 6

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^[-*+•]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\d{1,3})[.)]\s+(.*)$""")
    private val TABLE_RULE = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$""")
    private val HR = Regex("""^(-{3,}|\*{3,}|_{3,})$""")

    fun parse(markdown: String): List<Block> {
        val lines = markdown.take(MAX_CHARS).replace("\r", "").lines().map { it.trim() }
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += Block.Paragraph(paragraph.joinToString(" "))
            paragraph.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("|")) {
                flush()
                val tableLines = lines.drop(i).takeWhile { it.startsWith("|") }
                blocks += table(tableLines)
                i += tableLines.size
                continue
            }
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val numbered = NUMBERED.matchEntire(line)
            when {
                line.isEmpty() || HR.matches(line) -> flush()
                heading != null -> {
                    flush()
                    blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2].trim('#', ' '))
                }
                bullet != null -> {
                    flush()
                    blocks += Block.Bullet("•", bullet.groupValues[1])
                }
                numbered != null -> {
                    flush()
                    blocks += Block.Bullet("${numbered.groupValues[1]}.", numbered.groupValues[2])
                }
                else -> paragraph += line
            }
            i++
        }
        flush()
        return blocks
    }

    /** Pipe rows to a table; a `|---|` second row marks the first as the header. */
    private fun table(lines: List<String>): Block.Table {
        val hasHeader = lines.size > 1 && TABLE_RULE.matches(lines[1])
        val rows = lines.filterIndexed { idx, l -> !(hasHeader && idx == 1) && !TABLE_RULE.matches(l) }
            .map(::cells)
        val header = if (hasHeader) rows.first() else emptyList()
        val body = (if (hasHeader) rows.drop(1) else rows).take(MAX_ROWS)
        val width = (listOf(header) + body).maxOf { it.size }.coerceIn(1, MAX_COLS)
        fun pad(r: List<String>) = (r + List(width) { "" }).take(width)
        return Block.Table(if (hasHeader) pad(header) else emptyList(), body.map(::pad))
    }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    /** Splits `**bold**` and `` `code` `` out of [text]; an unclosed marker stays literal. */
    fun spans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        val plain = StringBuilder()
        var i = 0
        while (i < text.length) {
            val marker = when {
                text.startsWith("**", i) -> "**"
                text[i] == '`' -> "`"
                else -> null
            }
            val close = marker?.let { text.indexOf(it, i + it.length) } ?: -1
            if (marker != null && close > i + marker.length) {
                if (plain.isNotEmpty()) out += Span(plain.toString()).also { plain.clear() }
                val inner = text.substring(i + marker.length, close)
                out += Span(inner, bold = marker == "**", code = marker == "`")
                i = close + marker.length
            } else {
                plain.append(text[i])
                i++
            }
        }
        if (plain.isNotEmpty()) out += Span(plain.toString())
        return out
    }
}
