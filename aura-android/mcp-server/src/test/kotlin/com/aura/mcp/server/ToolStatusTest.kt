package com.aura.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolStatusTest {

    @Test
    fun `a plain sentence passes through without its full stop`() {
        assertEquals("Opening your Library", ToolStatus.clean("  Opening your   Library. "))
    }

    @Test
    fun `ids, packages and markup are refused so the host writes its own line`() {
        assertNull(ToolStatus.clean("Tapping som_42"))
        assertNull(ToolStatus.clean("Opening com.spotify.music"))
        assertNull(ToolStatus.clean("{\"tap\": 3}"))
        assertNull(ToolStatus.clean("   "))
        assertNull(ToolStatus.clean(null))
    }

    @Test
    fun `a long line is cut at a word and marked`() {
        val out = ToolStatus.clean("Scrolling through the whole list of saved playlists to find the one named Road Trip")
        requireNotNull(out)
        assertTrue(out.length <= ToolStatus.MAX_CHARS, out)
        assertTrue(out.endsWith("…"), out)
        assertTrue(!out.contains("Roa…"), out)
    }

    @Test
    fun `the status property is added once and keeps required`() {
        val base = ToolSchema(properties = JsonObject(mapOf("x" to JsonPrimitive(1))), required = listOf("x"))
        val once = ToolStatus.withStatus(base)
        assertTrue(once.properties!!.containsKey(ToolStatus.ARG))
        assertEquals(listOf("x"), once.required)
        assertEquals(once.properties, ToolStatus.withStatus(once).properties)
    }

    @Test
    fun `of reads the argument`() {
        assertEquals("Sending it to Rahul", ToolStatus.of(JsonObject(mapOf("status" to JsonPrimitive("Sending it to Rahul")))))
        assertNull(ToolStatus.of(JsonObject(emptyMap())))
    }
}
