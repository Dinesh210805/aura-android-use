package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.mcp.log.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTranscriptTest {
    @Test fun `renders turns in order and counts user turns`() {
        val t = SessionTranscript()
        t.addUser("what's the weather")
        t.addModel("Sunny.")
        t.addUser("thanks")
        assertEquals(2, t.userTurnCount())
        val r = t.render()
        assertTrue(r.contains("User: what's the weather"))
        assertTrue(r.contains("AURA: Sunny."))
        assertTrue(r.indexOf("what's the weather") < r.indexOf("thanks"))
    }

    @Test fun `blank turns are ignored and reset clears`() {
        val t = SessionTranscript()
        t.addUser("  ")
        t.addModel("")
        assertEquals(0, t.userTurnCount())
        t.addUser("hi")
        t.reset()
        assertEquals(0, t.userTurnCount())
        assertEquals("", t.render())
    }

    // ── Durable, timestamped logging (2026-08-05) ─────────────────────────────
    //
    // The conversation plane had no log at all — only this class: in memory, no
    // timestamps, cleared on every new conversation, feeding nothing but the summarizer.
    // The part that made diagnosis impossible was `if (t.isEmpty()) return`. A blank
    // transcription is not nothing; it is the transcriber failing, and dropping it makes
    // "AURA said nothing" indistinguishable from "AURA said something nobody recorded".

    private class Sink {
        val seen = mutableListOf<Utterance>()
        fun accept(u: Utterance) { seen.add(u) }
    }

    private fun transcriptAt(startMs: Long = 1_000): Pair<SessionTranscript, Sink> {
        val sink = Sink()
        var now = startMs
        return SessionTranscript(clock = { now++ }, sink = sink::accept) to sink
    }

    @Test
    fun `both sides of the conversation are recorded with timestamps`() {
        val (t, sink) = transcriptAt(startMs = 5_000)

        t.addUser("what's the weather")
        t.addModel("Twenty-eight degrees and clear in Coimbatore.")

        assertEquals(2, sink.seen.size)
        assertEquals("user", sink.seen[0].speaker)
        assertEquals("aura", sink.seen[1].speaker)
        assertTrue("timestamps must be real, not zero", sink.seen.all { it.atMillis >= 5_000 })
        assertTrue("turns must be ordered in time", sink.seen[0].atMillis < sink.seen[1].atMillis)
    }

    @Test
    fun `a blank transcription is logged as a gap, not silently dropped`() {
        val (t, sink) = transcriptAt()

        t.addUser("")
        t.addModel("   ")

        assertEquals("a missing turn must still be a record", 2, sink.seen.size)
        assertTrue(sink.seen.all { it.kind == "gap" })
        assertTrue(sink.seen.all { !it.complete })
        // It must say WHICH side went missing, or the record is useless.
        assertEquals("user", sink.seen[0].speaker)
        assertEquals("aura", sink.seen[1].speaker)
    }

    // ── A gap must mean something went missing (2026-08-12) ───────────────────
    //
    // The first real Live log came back 11 gaps in 26 entries — and they were manufactured,
    // not observed: the controller finalized every turn by calling BOTH addUser and addModel,
    // even though the user's text had already been flushed when the model started replying.
    // So each ordinary turn minted an empty user turn, and "[no transcription]" stopped
    // meaning anything. A gap is only honest when we know the speaker actually made a sound.

    @Test
    fun `a blank turn from a speaker who never spoke is not a gap`() {
        val (t, sink) = transcriptAt()

        t.addUser("", spoke = false)
        t.addModel("   ", spoke = false)

        assertEquals("nothing happened, so nothing is recorded", 0, sink.seen.size)
    }

    @Test
    fun `a blank turn from a speaker who did speak is still a gap`() {
        val (t, sink) = transcriptAt()

        // Audio was heard; the words never arrived. That is the failure worth recording.
        t.addModel("", spoke = true)

        assertEquals(1, sink.seen.size)
        assertEquals("gap", sink.seen[0].kind)
        assertFalse(sink.seen[0].complete)
    }

    @Test
    fun `real text is recorded regardless of the spoke hint`() {
        val (t, sink) = transcriptAt()

        // The hint only decides what an ABSENCE means; it can never suppress actual words.
        t.addUser("open Instagram", spoke = false)

        assertEquals(1, sink.seen.size)
        assertEquals("speech", sink.seen[0].kind)
        assertEquals("User: open Instagram", t.render())
    }

    @Test
    fun `a gap does not pollute the summarizer transcript`() {
        // The summarizer feeds conversation memory. Handing it "[no transcription]" lines
        // would turn a transcription failure into a remembered fact about the user.
        val (t, _) = transcriptAt()

        t.addUser("remember I prefer short answers")
        t.addUser("")

        assertEquals("User: remember I prefer short answers", t.render())
        assertEquals(1, t.userTurnCount())
    }

    @Test
    fun `barge-in and lifecycle moments are recorded`() {
        val (t, sink) = transcriptAt()

        t.note(speaker = "system", text = "Live session started", kind = "lifecycle")
        t.addModel("Sure, I'll open You—", kind = "interrupted", complete = false)
        t.note(speaker = "system", text = "handed to action plane: open youtube", kind = "task")

        assertEquals(listOf("lifecycle", "interrupted", "task"), sink.seen.map { it.kind })
        assertFalse("an interrupted turn is not a complete one", sink.seen[1].complete)
    }

    @Test
    fun `recent returns only the last n turns, newest last`() {
        val (t, _) = transcriptAt()
        t.addUser("one"); t.addModel("two"); t.addUser("three"); t.addModel("four")

        assertEquals("User: three\nAURA: four", t.recent(2))
    }

    @Test
    fun `recent asking for more turns than exist returns everything`() {
        val (t, _) = transcriptAt()
        t.addUser("only one")
        assertEquals("User: only one", t.recent(10))
    }

    @Test
    fun `recent of zero or fewer returns nothing rather than everything`() {
        val (t, _) = transcriptAt()
        t.addUser("hello")
        assertEquals("", t.recent(0))
        assertEquals("", t.recent(-3))
    }

    @Test
    fun `reset clears the summarizer view but the sink has already persisted`() {
        // reset() exists so a NEW conversation does not inherit the last one's context. It
        // must not be able to erase the log — that is the whole point of writing through.
        val (t, sink) = transcriptAt()
        t.addUser("hello")

        t.reset()

        assertEquals("", t.render())
        assertEquals("the log must survive a conversation reset", 1, sink.seen.size)
    }
}
