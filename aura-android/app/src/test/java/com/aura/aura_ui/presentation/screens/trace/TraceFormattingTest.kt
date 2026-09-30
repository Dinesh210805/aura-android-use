package com.aura.aura_ui.presentation.screens.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four payload shapes that actually reach the trace screen. Path 2 (prose + JSON) and
 * path 3 (truncated JSON) are the ones a naive `parse(blob)` gets wrong — and getting them
 * wrong is silent, because the block still renders, just empty or mangled.
 */
class TraceFormattingTest {

    @Test
    fun `pure json is indented and marked as json from the start`() {
        val result = humanizePayload("""{"tool":"tap","som_id":5,"ok":true}""")

        assertEquals(0, result.jsonStart)
        assertTrue(result.text.contains("\n  \"tool\": \"tap\""))
    }

    @Test
    fun `prose header followed by json keeps the prose and indents the tail`() {
        val raw = "42 elements, 3 clickable\n[{\"e\":\"Send\",\"id\":1}]"

        val result = humanizePayload(raw)

        assertTrue(result.hasJson)
        assertTrue(result.text.startsWith("42 elements, 3 clickable"))
        assertTrue(result.text.contains("\"e\": \"Send\""))
        // The JSON section must start after the prose, or highlighting colours the prose.
        assertTrue(result.jsonStart > "42 elements, 3 clickable".length)
    }

    @Test
    fun `truncated json is shown in full as text rather than dropped`() {
        val raw = """{"elements":[{"id":1,"label":"Sen"""

        val result = humanizePayload(raw)

        assertFalse(result.hasJson)
        assertEquals(raw, result.text)
    }

    @Test
    fun `escape sequences are resolved so multiline output reads as lines`() {
        val result = humanizePayload("""Tapped\nelement 5\tdone ✓""")

        assertFalse(result.hasJson)
        assertEquals("Tapped\nelement 5\tdone ✓", result.text)
    }

    @Test
    fun `a bare word is never quoted by the lenient parser`() {
        assertEquals("ok", humanizePayload("ok").text)
    }

    @Test
    fun `blank payload is returned unchanged`() {
        assertEquals("", humanizePayload("").text)
    }

    @Test
    fun `a trailing backslash does not throw`() {
        assertEquals("path\\", unescape("path\\"))
    }

    @Test
    fun `an invalid unicode escape is kept literally instead of losing characters`() {
        assertEquals("\\uZZ", unescape("\\uZZ"))
    }
}
