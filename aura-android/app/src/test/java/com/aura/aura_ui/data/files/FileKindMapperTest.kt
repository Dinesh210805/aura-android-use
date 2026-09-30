package com.aura.aura_ui.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindMapperTest {

    @Test
    fun `media kinds map to their mime prefixes`() {
        assertEquals(listOf("image/"), FileKindMapper.mimePrefixesFor("image"))
        assertEquals(listOf("video/"), FileKindMapper.mimePrefixesFor("video"))
        assertEquals(listOf("audio/"), FileKindMapper.mimePrefixesFor("audio"))
    }

    @Test
    fun `document kind covers pdf and office types`() {
        val prefixes = FileKindMapper.mimePrefixesFor("document")!!
        assertTrue(prefixes.contains("application/pdf"))
        assertTrue(prefixes.any { it.contains("text/") })
    }

    @Test
    fun `any and absent kinds mean no mime filter`() {
        assertEquals(emptyList<String>(), FileKindMapper.mimePrefixesFor("any"))
        assertEquals(emptyList<String>(), FileKindMapper.mimePrefixesFor(null))
        assertEquals(emptyList<String>(), FileKindMapper.mimePrefixesFor("  "))
    }

    @Test
    fun `unknown kind is rejected with null`() {
        assertNull(FileKindMapper.mimePrefixesFor("spreadsheetzz"))
    }

    @Test
    fun `kind matching is case-insensitive`() {
        assertEquals(listOf("image/"), FileKindMapper.mimePrefixesFor("Image"))
    }
}
