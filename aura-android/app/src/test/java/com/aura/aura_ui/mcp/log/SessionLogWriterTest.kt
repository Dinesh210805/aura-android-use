package com.aura.aura_ui.mcp.log

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The point of a single writer: a conversation and the phone task it triggers land in ONE
 * `metadata.json`, with neither plane's entries lost. Two independent writers each rewriting
 * the whole file from a private copy is how the loggers were built, and it silently drops
 * whichever plane wrote second-to-last.
 */
class SessionLogWriterTest {

    @get:Rule val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun root() = temp.newFolder("mcp_logs")

    /** The writer is async by design (never blocks a voice turn), so reads poll for the effect. */
    private fun awaitSession(root: File, timeoutMs: Long = 5_000): SessionLog {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val file = root.listFiles()?.firstOrNull()?.let { File(it, "metadata.json") }
            if (file?.exists() == true) {
                val parsed = runCatching { json.decodeFromString(SessionLog.serializer(), file.readText()) }
                if (parsed.isSuccess) return parsed.getOrThrow()
            }
            Thread.sleep(25)
        }
        throw AssertionError("no session was written within ${timeoutMs}ms")
    }

    private fun awaitUntil(root: File, timeoutMs: Long = 5_000, predicate: (SessionLog) -> Boolean): SessionLog {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: SessionLog? = null
        while (System.currentTimeMillis() < deadline) {
            last = runCatching { awaitSession(root, timeoutMs = 500) }.getOrNull()
            if (last != null && predicate(last)) return last
            Thread.sleep(25)
        }
        throw AssertionError("condition never held; last session was $last")
    }

    @Test
    fun `speech and tool calls written through one writer share one session file`() {
        val root = root()
        val writer = SessionLogWriter(root, "test")

        writer.open { id, now ->
            SessionLog(
                sessionId = id,
                startedAtMillis = now,
                agentLabel = "Gemini Live",
                tokenId = null,
                source = LiveConversationLogger.SOURCE_LIVE,
            )
        }
        writer.edit { s, _ ->
            s.utterances.add(Utterance(1, "user", "open Instagram", "speech"))
        }
        // The action plane, writing through the same owner rather than its own file.
        writer.edit { s, _ ->
            s.invocations.add(ToolInvocation(0, 2, "open_app", null))
        }
        writer.edit { s, _ ->
            s.utterances.add(Utterance(3, "aura", "You're on your profile.", "speech"))
        }

        val session = awaitUntil(root) { it.utterances.size == 2 && it.invocations.size == 1 }
        assertEquals("one session directory, not two", 1, root.listFiles()?.size)
        assertEquals(LiveConversationLogger.SOURCE_LIVE, session.source)
        assertEquals(listOf("open Instagram", "You're on your profile."), session.utterances.map { it.text })
        assertEquals("open_app", session.invocations.single().toolName)
    }

    @Test
    fun `opening an already open writer adopts the session instead of replacing it`() {
        val root = root()
        val writer = SessionLogWriter(root, "test")

        writer.open { id, now -> SessionLog(id, now, agentLabel = "first", tokenId = null) }
        writer.edit { s, _ -> s.utterances.add(Utterance(1, "user", "hello", "speech")) }
        awaitUntil(root) { it.utterances.size == 1 }

        writer.open { id, now -> SessionLog(id, now, agentLabel = "second", tokenId = null) }
        writer.edit { s, _ -> s.utterances.add(Utterance(2, "aura", "hi", "speech")) }

        val session = awaitUntil(root) { it.utterances.size == 2 }
        assertEquals("the first session must survive", "first", session.agentLabel)
        assertEquals(1, root.listFiles()?.size)
    }

    @Test
    fun `a straggler after close joins the conversation it belongs to, not a new one`() {
        // The device found this one: AURA's greeting arrived 130ms AFTER the session ended and
        // opened a whole new session directory to hold that single line.
        val root = root()
        val writer = SessionLogWriter(root, "test")

        writer.open { id, now -> SessionLog(id, now, agentLabel = null, tokenId = null) }
        writer.edit { s, _ -> s.utterances.add(Utterance(1, "user", "hello", "speech")) }
        writer.close { it.endedAtMillis = 99; it.endReason = "closed" }
        awaitUntil(root) { it.endReason == "closed" }

        writer.edit { s, _ -> s.utterances.add(Utterance(9, "aura", "still talking", "speech")) }

        val session = awaitUntil(root) { it.utterances.size == 2 }
        assertEquals("one directory, not one per straggler", 1, root.listFiles()?.size)
        assertEquals("still talking", session.utterances.last().text)
        assertNotNull("and the session stays finished", session.endedAtMillis)
    }

    @Test
    fun `a session nobody spoke in is never created`() {
        // A Live connect that dies in two seconds used to leave a directory whose entire
        // contents were "session started" and "session ended" — a Logs card for nothing.
        val root = root()
        val writer = SessionLogWriter(root, "test")

        writer.open { id, now -> SessionLog(id, now, agentLabel = "Gemini Live", tokenId = null) }
        writer.edit(materialize = false) { s, _ ->
            s.utterances.add(Utterance(1, "system", "Live session started", "lifecycle"))
        }
        writer.edit(materialize = false) { s, _ ->
            s.utterances.add(Utterance(2, "system", "Live session ended", "lifecycle"))
        }
        writer.close { it.endReason = "closed" }
        Thread.sleep(300)

        assertEquals("nothing was said, so nothing is on disk", 0, root.listFiles()?.size ?: 0)
    }

    @Test
    fun `lifecycle notes are kept once the conversation is real`() {
        val root = root()
        val writer = SessionLogWriter(root, "test")

        writer.open { id, now -> SessionLog(id, now, agentLabel = null, tokenId = null) }
        writer.edit(materialize = false) { s, _ ->
            s.utterances.add(Utterance(1, "system", "Live session started", "lifecycle"))
        }
        writer.edit { s, _ -> s.utterances.add(Utterance(2, "user", "hello", "speech")) }
        writer.edit(materialize = false) { s, _ ->
            s.utterances.add(Utterance(3, "system", "reconnected", "lifecycle"))
        }

        val session = awaitUntil(root) { it.utterances.size == 2 }
        // The note that preceded the first words could not be kept — there was nothing to keep
        // it in — but every note after it is recorded, which is what a reconnect needs.
        assertEquals(listOf("hello", "reconnected"), session.utterances.map { it.text })
    }

    @Test
    fun `a second conversation gets its own session`() {
        val root = root()
        val writer = SessionLogWriter(root, "test")

        writer.open { id, now -> SessionLog(id, now, agentLabel = "first", tokenId = null) }
        writer.edit { s, _ -> s.utterances.add(Utterance(1, "user", "one", "speech")) }
        awaitUntil(root) { it.utterances.size == 1 }
        writer.close { it.endReason = "closed" }

        writer.open { id, now -> SessionLog(id, now, agentLabel = "second", tokenId = null) }
        writer.edit { s, _ -> s.utterances.add(Utterance(2, "user", "two", "speech")) }

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && (root.listFiles()?.size ?: 0) < 2) Thread.sleep(25)
        assertEquals("a new conversation must not append to the finished one", 2, root.listFiles()?.size)
    }

    @Test
    fun `a run only joins the conversation when the conversation asked for it`() {
        val writer = SessionLogWriter(root(), "test")
        LiveSessionScope.setConversation(writer)

        // A run started from the app's own UI is a separate story.
        assertNull(LiveSessionScope.claim())

        val claimed = kotlinx.coroutines.runBlocking {
            LiveSessionScope.expectRun { LiveSessionScope.claim() }
        }
        assertEquals(writer, claimed)

        // The intent is consumed: a second run must not inherit it.
        assertNull(LiveSessionScope.claim())
        LiveSessionScope.setConversation(null)
    }

    @Test
    fun `no conversation means no adoption even inside expectRun`() {
        LiveSessionScope.setConversation(null)
        val claimed = kotlinx.coroutines.runBlocking {
            LiveSessionScope.expectRun { LiveSessionScope.claim() }
        }
        assertNull(claimed)
    }
}
