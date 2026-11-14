package com.aura.aura_ui.agentcore

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What the 6-hour fresh start and `search_history around` read from a thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadStoreTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AgentCoreDatabase::class.java)
        .allowMainThreadQueries().build()
    private var clock = 0L
    private val threads = ThreadStore(db.dao(), now = { clock++ })

    @After fun close() = db.close()

    @Test
    fun `the transcript is oldest first, chat only, and around reads a hit's neighbours`() = runBlocking {
        val t = threads.create().id
        threads.append(t, ItemKind.USER, "Book a cab to the airport")
        threads.append(t, ItemKind.TOOL, "tap", tool = "tap")
        threads.append(t, ItemKind.ASSISTANT, "Booked an Ola Mini.")
        threads.append(t, ItemKind.USER, "Thanks")
        assertEquals("user: Book a cab to the airport\nassistant: Booked an Ola Mini.\nuser: Thanks", threads.recentTranscript(t))
        val hit = threads.search("Ola").single()
        assertEquals(listOf("Book a cab to the airport", "Booked an Ola Mini.", "Thanks"), threads.around(hit.rowid).map { it.text })
        threads.setSummary(t, "User booked an Ola to the airport.")
        assertEquals("User booked an Ola to the airport.", threads.thread(t)?.summary)
    }

    @Test
    fun `undo puts the context back, removes the turn from the chat and returns what was sent`() = runBlocking {
        // saveContext is not called: Robolectric's SQLite hides the conflict Room's @Upsert relies on.
        val t = threads.create().id
        threads.append(t, ItemKind.USER, "first")
        val photo = Attachment("content://photo/1", "image/jpeg", "receipt.jpg", 10)
        val asked = threads.append(t, ItemKind.USER, "what is this", attachments = listOf(photo))
        threads.append(t, ItemKind.ASSISTANT, "A receipt.")
        threads.setUndo(t, Undo(null, ContextCodec.encodeTaint(setOf(Origin.WEB)), asked))

        assertEquals("what is this" to listOf(photo), threads.undo(t))
        assertEquals(listOf("first"), db.dao().items(t).first().map { it.text })
        assertEquals(setOf(Origin.WEB), threads.loadContext(t).taint)
        assertEquals(null, threads.undo(t))
    }
}
