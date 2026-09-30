package com.aura.aura_ui.mcp.log

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation plane wrote a full on-disk log from day one and **nothing read it**.
 * [SessionHtmlRenderer.buildTimeline] walked `invocations` and `llmCalls` only, so a
 * `source = "live"` session — 26 utterances of real speech on the device that reported
 * this — opened as an empty page headed "MCP (external) · 0 tools · 0 LLM calls".
 *
 * These tests pin the reader to the writer: whatever [Utterance] kinds the logger can
 * produce must appear in the trace, and a live session must not describe itself as an
 * external MCP one.
 */
class SessionHtmlRendererTest {

    private fun liveSession(vararg utterances: Utterance) = SessionLog(
        sessionId = "1786508178958-37a91294",
        startedAtMillis = 1_786_508_178_958,
        endedAtMillis = 1_786_508_250_698,
        endReason = "closed",
        agentLabel = "Gemini Live",
        tokenId = null,
        source = LiveConversationLogger.SOURCE_LIVE,
        utterances = utterances.toMutableList(),
    )

    private fun say(speaker: String, text: String, kind: String = "speech", at: Long = 1_786_508_180_000) =
        Utterance(atMillis = at, speaker = speaker, text = text, kind = kind)

    @Test
    fun `a live session renders what was actually said`() {
        val html = SessionHtmlRenderer.render(
            liveSession(
                say("user", "open Instagram and go to my profile", at = 1),
                say("aura", "On it — opening Instagram now.", at = 2),
            ),
            File("nonexistent"),
        )

        assertTrue("the user's words must appear", html.contains("open Instagram and go to my profile"))
        assertTrue("AURA's reply must appear", html.contains("On it — opening Instagram now."))
    }

    @Test
    fun `a live session is not labelled as an external MCP one`() {
        val html = SessionHtmlRenderer.render(liveSession(say("user", "hello")), File("nonexistent"))

        assertFalse("live is a third source, not MCP", html.contains("MCP (external)"))
        assertTrue(html.contains("Live conversation"))
        // "0 tools · 0 LLM calls" is true and useless for a conversation; count what it has.
        assertTrue("the header must count utterances", html.contains("1 utterance"))
    }

    @Test
    fun `speech, gaps, barge-ins and lifecycle notes are all rendered`() {
        val html = SessionHtmlRenderer.render(
            liveSession(
                say("system", "Live session started", kind = "lifecycle", at = 1),
                say("aura", "[no transcription — turn produced no text]", kind = "gap", at = 2),
                say("system", "user barge-in — AURA cut off mid-reply", kind = "interrupted", at = 3),
                say("user", "typed instead", kind = "text", at = 4),
            ),
            File("nonexistent"),
        )

        // A gap is the whole reason this log exists — it must be visible, not filtered out.
        assertTrue(html.contains("no transcription"))
        assertTrue(html.contains("Live session started"))
        assertTrue(html.contains("barge-in"))
        assertTrue(html.contains("typed instead"))
    }

    @Test
    fun `utterances and tool calls interleave on one clock`() {
        val session = liveSession(
            say("user", "first thing said", at = 100),
            say("aura", "last thing said", at = 300),
        )
        session.invocations += ToolInvocation(
            index = 0,
            timestampMillis = 200,
            toolName = "open_app",
            argsJson = """{"package":"com.instagram.android"}""",
        )

        val html = SessionHtmlRenderer.render(session, File("nonexistent"))

        val first = html.indexOf("first thing said")
        val tool = html.indexOf("open_app")
        val last = html.indexOf("last thing said")
        assertTrue("speech and tools must sort by timestamp", first in 0 until tool && tool < last)
    }

    @Test
    fun `an agent session is unchanged by the live path`() {
        val session = SessionLog(
            sessionId = "s",
            startedAtMillis = 0,
            agentLabel = null,
            tokenId = null,
            source = AgentRunLogger.SOURCE_AGENT,
            command = "play music",
        )
        val html = SessionHtmlRenderer.render(session, File("nonexistent"))

        assertTrue(html.contains("On-device agent"))
        assertTrue(html.contains("play music"))
    }
}
