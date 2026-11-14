package com.aura.aura_ui.agentcore

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocTextTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test
    fun `a Word file reads as paragraphs with entities decoded`() {
        val xml = """<w:document><w:body><w:p><w:r><w:t>Invoice </w:t></w:r><w:r><w:t xml:space="preserve">#42 &amp; tax</w:t></w:r></w:p>""" +
            """<w:p><w:r><w:t>Total: 1,200</w:t></w:r></w:p></w:body></w:document>"""
        assertEquals("Invoice #42 & tax\nTotal: 1,200", DocText.docx(zip("word/document.xml" to xml)))
    }

    @Test
    fun `an Excel file reads as sheets of tab separated rows`() {
        val shared = """<sst><si><t>Name</t></si><si><t>Amount</t></si><si><t>Rent</t></si></sst>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>""" +
            """<row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>15000</v></c></row>""" +
            """<row r="3"><c r="A3" t="inlineStr"><is><t>Food</t></is></c><c r="B3"/></row></sheetData></worksheet>"""
        val text = DocText.xlsx(zip("xl/sharedStrings.xml" to shared, "xl/worksheets/sheet1.xml" to sheet))
        assertEquals("Sheet 1:\nName\tAmount\nRent\t15000\nFood", text)
    }

    @Test
    fun `something that is not the expected zip is null`() {
        assertNull(DocText.docx("not a zip".toByteArray()))
        assertNull(DocText.xlsx(zip("word/document.xml" to "<w:p/>")))
    }

    @Test
    fun `a window knows whether more follows`() {
        assertTrue(DocChunk("abc", 0, 10).more)
        assertFalse(DocChunk("abc", 7, 10).more)
        assertTrue(DocChunk("abc", 0, null).more)
    }
}
