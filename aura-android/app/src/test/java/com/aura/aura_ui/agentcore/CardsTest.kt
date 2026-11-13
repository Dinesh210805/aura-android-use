package com.aura.aura_ui.agentcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers how tool results become chat cards, and that skills and device facts reach the prompt. */
class CardsTest {

    @Test
    fun `find_files becomes a files card that survives a round trip`() {
        val result = """{"status":"ok","count":2,"files":[
            {"uri":"content://media/external/images/1","display_name":"invoice.jpg","mime_type":"image/jpeg","size_bytes":1200,"modified_epoch_ms":5},
            {"uri":"content://media/external/files/2","display_name":"invoice.pdf","mime_type":"application/pdf","size_bytes":900,"modified_epoch_ms":4}]}"""

        val card = ChatCard.fromToolResult("find_files", result) as ChatCard.Files

        assertEquals(listOf("invoice.jpg", "invoice.pdf"), card.files.map { it.name })
        assertEquals(listOf(true, false), card.files.map { it.isVisual })
        assertEquals(card, ChatCard.decode(card.encode()))
    }

    @Test
    fun `resolve_contact asks the user to choose only when it is ambiguous`() {
        val two = """{"status":"disambiguate","candidates":[
            {"contact_id":1,"display_name":"Rahul K","phone_number":"+911","score":0.9},
            {"contact_id":2,"display_name":"Rahul S","phone_number":"+912","score":0.8}]}"""
        val one = """{"status":"auto","candidates":[{"contact_id":1,"display_name":"Mom","phone_number":"+913","score":1.0}]}"""

        assertTrue((ChatCard.fromToolResult("resolve_contact", two) as ChatCard.Contacts).choose)
        assertEquals(false, (ChatCard.fromToolResult("resolve_contact", one) as ChatCard.Contacts).choose)
    }

    @Test
    fun `results with nothing to show make no card`() {
        assertNull(ChatCard.fromToolResult("find_files", """{"status":"ok","files":[]}"""))
        assertNull(ChatCard.fromToolResult("tap", """{"ok":true}"""))
        assertNull(ChatCard.fromToolResult("find_files", "not json"))
        assertNull(ChatCard.decode("garbage"))
    }

    @Test
    fun `saved skills and the device are in the system prompt`() {
        val prompt = SystemPrompt.build(null, "", skills = "book-cab: booking a cab in Uber", device = "OnePlus Nord 4, Android 16")

        assertTrue(prompt.contains("<skills>"))
        assertTrue(prompt.contains("book-cab: booking a cab in Uber"))
        assertTrue(prompt.contains("OnePlus Nord 4, Android 16"))
        assertTrue(!SystemPrompt.build(null, "").contains("<skills>"))
    }
}
