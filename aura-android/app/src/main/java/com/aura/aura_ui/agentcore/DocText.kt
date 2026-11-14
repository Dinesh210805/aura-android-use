package com.aura.aura_ui.agentcore

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Text out of the office files the user attaches: Word (`.docx`) and Excel (`.xlsx`), which are
 * zipped XML. Pure, so it is unit-tested without a phone.
 *
 * - Contract: returns null for a file that is not the expected zip. Reads at most
 *   [MAX_UNZIPPED] bytes per file, so a zip bomb costs nothing.
 * - Why regex and not an XML parser: the parts read are flat text runs, and a regex reads no DTD
 *   and resolves no entity beyond the five XML ones.
 */
object DocText {
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val MAX_UNZIPPED = 8 * 1024 * 1024

    fun docx(bytes: ByteArray): String? {
        val xml = unzip(bytes) { it == "word/document.xml" }["word/document.xml"] ?: return null
        return PARAGRAPH.findAll(xml).map { p -> RUN.findAll(p.value).joinToString("") { unescape(it.groupValues[1]) } }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Each sheet as a heading and tab-separated rows. */
    fun xlsx(bytes: ByteArray): String? {
        val parts = unzip(bytes) { it == "xl/sharedStrings.xml" || SHEET_FILE.matches(it) }
        val sheets = parts.keys.filter { SHEET_FILE.matches(it) }
            .sortedBy { SHEET_FILE.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        if (sheets.isEmpty()) return null
        val shared = parts["xl/sharedStrings.xml"]?.let { xml ->
            SHARED.findAll(xml).map { si -> RUN_ANY.findAll(si.value).joinToString("") { unescape(it.groupValues[1]) } }.toList()
        }.orEmpty()
        return sheets.joinToString("\n\n") { name ->
            val rows = ROW.findAll(parts.getValue(name)).map { row ->
                CELL.findAll(row.value).map { cell -> cellText(cell.groupValues[1], cell.groupValues[2], shared) }.joinToString("\t")
            }.filter { it.isNotBlank() }
            "Sheet ${SHEET_FILE.find(name)?.groupValues?.get(1)}:\n" + rows.joinToString("\n")
        }.trim()
    }

    private fun cellText(attrs: String, body: String, shared: List<String>): String {
        val value = VALUE.find(body)?.groupValues?.get(1)
        return when {
            "t=\"s\"" in attrs -> value?.toIntOrNull()?.let(shared::getOrNull).orEmpty()
            "t=\"inlineStr\"" in attrs -> RUN_ANY.findAll(body).joinToString("") { unescape(it.groupValues[1]) }
            else -> value?.let(::unescape).orEmpty()
        }
    }

    private fun unzip(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, String> = runCatching {
        val out = mutableMapOf<String, String>()
        var budget = MAX_UNZIPPED
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.filter { wanted(it.name) }.forEach { entry ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(BUFFER)
                while (budget > 0) {
                    val n = zip.read(chunk, 0, minOf(chunk.size, budget))
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                    budget -= n
                }
                out[entry.name] = buffer.toString(Charsets.UTF_8.name())
            }
        }
        out
    }.getOrDefault(emptyMap())

    internal fun unescape(text: String): String = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private const val BUFFER = 16 * 1024
    private val PARAGRAPH = Regex("""<w:p[ >].*?</w:p>|<w:p/>""", RegexOption.DOT_MATCHES_ALL)
    private val RUN = Regex("""<w:t(?:\s[^>]*)?>(.*?)</w:t>""", RegexOption.DOT_MATCHES_ALL)
    private val RUN_ANY = Regex("""<t(?:\s[^>]*)?>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
    private val SHARED = Regex("""<si>.*?</si>""", RegexOption.DOT_MATCHES_ALL)
    private val SHEET_FILE = Regex("""xl/worksheets/sheet(\d+)\.xml""")
    private val ROW = Regex("""<row[ >].*?</row>""", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("""<c\s([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
    private val VALUE = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
}

/**
 * One window of an attachment's text.
 * @param total the whole text's length, or null when it is not known yet (a PDF read page by page).
 */
data class DocChunk(val text: String, val offset: Int, val total: Int?) {
    val end: Int get() = offset + text.length
    val more: Boolean get() = total == null || end < total
}
