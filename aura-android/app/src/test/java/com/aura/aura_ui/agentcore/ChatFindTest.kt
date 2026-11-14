package com.aura.aura_ui.agentcore

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFindTest {
    private fun row(id: Long, kind: ItemKind, text: String) = ItemRow(rowid = id, threadId = "t", atMs = id, kind = kind.name, text = text)

    @Test
    fun `finds the user's and AURA's messages, ignoring case, never tool rows`() {
        val items = listOf(
            row(1, ItemKind.USER, "Book a cab to the Airport"),
            row(2, ItemKind.TOOL, "airport search"),
            row(3, ItemKind.ASSISTANT, "The airport cab is booked."),
            row(4, ItemKind.USER, "thanks"),
        )
        assertEquals(listOf(1L, 3L), ChatFind.matches(items, "airport"))
        assertEquals(emptyList<Long>(), ChatFind.matches(items, "a"))
        assertEquals(emptyList<Long>(), ChatFind.matches(items, "metro"))
    }
}
