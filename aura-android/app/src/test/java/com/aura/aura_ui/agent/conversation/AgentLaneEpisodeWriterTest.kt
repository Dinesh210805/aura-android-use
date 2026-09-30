package com.aura.aura_ui.agent.conversation

import android.content.Context
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.agent.memory.EncryptedMemoryService
import com.aura.aura_ui.agent.memory.MemoryType
import com.aura.aura_ui.conversation.ConversationMessage
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentLaneEpisodeWriterTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    private class FakeSummarizer : ConversationSummarizer {
        val transcripts = mutableListOf<String>()
        override suspend fun summarize(transcript: String): ConversationSummary {
            transcripts += transcript
            return ConversationSummary("You ordered food and checked trains.", listOf("likes biryani"), listOf("mixes Tamil and English"))
        }
        override suspend fun extractFacts(transcript: String) = emptyList<String>()
    }

    private fun user(text: String) = ConversationMessage(text = text, isUser = true)
    private fun aura(text: String) = ConversationMessage(text = text, isUser = false)

    private val chat = listOf(user("order biryani"), aura("Ordered."), user("trains to Chennai?"), aura("Three today."))

    @Test fun `a finished conversation becomes one episode plus its facts and style`() = runTest {
        val memory = EncryptedMemoryService(EncryptedJsonStore(ctx, "episode_1")) { 1L }
        val summarizer = FakeSummarizer()
        val writer = AgentLaneEpisodeWriter(summarizer, memory, scope = this)

        writer.onConversationEnded(chat)
        advanceUntilIdle()

        val all = memory.allEntries()
        assertEquals(1, all.count { it.type == MemoryType.EPISODE })
        assertTrue(all.any { it.type == MemoryType.USER && it.text == "likes biryani" })
        assertTrue(all.any { it.type == MemoryType.STYLE })
        assertTrue(summarizer.transcripts.single().startsWith("User: order biryani\nAURA: Ordered."))
    }

    @Test fun `the same messages are never summarized twice when the overlay hides again`() = runTest {
        val summarizer = FakeSummarizer()
        val writer = AgentLaneEpisodeWriter(summarizer, EncryptedMemoryService(EncryptedJsonStore(ctx, "episode_2")) { 1L }, scope = this)

        writer.onConversationEnded(chat)
        writer.onConversationEnded(chat) // hidden again, nothing new said
        writer.onConversationEnded(chat + user("thanks") + aura("Anytime.") + user("bye"))
        advanceUntilIdle()

        assertEquals(2, summarizer.transcripts.size)
        assertEquals("User: thanks\nAURA: Anytime.\nUser: bye", summarizer.transcripts[1])
    }

    @Test fun `an excluded conversation (benchmark or Live) writes nothing, and only that one`() = runTest {
        val summarizer = FakeSummarizer()
        val writer = AgentLaneEpisodeWriter(summarizer, EncryptedMemoryService(EncryptedJsonStore(ctx, "episode_3")) { 1L }, scope = this)

        writer.excludeCurrentConversation()
        writer.onConversationEnded(chat)
        val next = listOf(user("hi"), aura("hey"), user("what's up"))
        writer.onConversationEnded(chat + next)
        advanceUntilIdle()

        assertEquals(listOf("User: hi\nAURA: hey\nUser: what's up"), summarizer.transcripts)
    }

    @Test fun `a single command is a task-log line, not a diary entry`() {
        assertNull(AgentLaneEpisodeWriter.transcriptOrNull(listOf(user("open maps"), aura("Done."))))
    }

    @Test fun `partial and streaming text stays out of the transcript`() {
        val t = AgentLaneEpisodeWriter.transcriptOrNull(
            listOf(user("one"), user("tw").copy(isPartial = true), aura("…").copy(isStreaming = true), user("two")),
        )
        assertEquals("User: one\nUser: two", t)
    }

    @Test fun `a cleared chat starts over from its first message`() {
        assertEquals(chat, AgentLaneEpisodeWriter.unseen(chat, lastSeenId = "gone"))
    }
}
