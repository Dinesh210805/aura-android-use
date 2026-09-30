package com.aura.aura_ui.presentation.screens.trace

import com.aura.aura_ui.mcp.log.NarrationBeat
import com.aura.aura_ui.mcp.log.SessionLog
import com.aura.aura_ui.mcp.log.ToolInvocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TraceViewsTest {

    private fun inv(i: Int, tool: String, som: Boolean = false, shot: Boolean = false) =
        ToolInvocation(index = i, timestampMillis = i.toLong(), toolName = tool, argsJson = null, somImage = som, hasScreenshot = shot)

    // ── what was tapped ────────────────────────────────────────────────

    @Test fun `the crop maps a screen box onto a smaller image and keeps it inside`() {
        val box = TargetCrop.Box(500, 1000, 700, 1100) // screen px on a 1080x2400 phone
        val (crop, target) = TargetCrop.cropRegion(box, 1080, 2400, 540, 1200)
        assertEquals(TargetCrop.Box(250, 500, 350, 550), target)
        assertTrue("target inside crop", crop.l <= target.l && crop.r >= target.r && crop.t <= target.t && crop.b >= target.b)
        assertTrue("crop within image", crop.l >= 0 && crop.t >= 0 && crop.r <= 540 && crop.b <= 1200)
        assertTrue("a small icon still gets context around it", crop.w >= 540 * 0.5)
    }

    @Test fun `a box at the screen edge is clamped, not cut off`() {
        val (crop, _) = TargetCrop.cropRegion(TargetCrop.Box(0, 0, 40, 40), 1080, 2400, 1080, 2400)
        assertEquals(0, crop.l)
        assertEquals(0, crop.t)
    }

    @Test fun `the crop comes from the screen the som_id was chosen from`() {
        val calls = listOf(inv(0, "read_screen", shot = true), inv(1, "type_text", shot = true), inv(2, "tap", shot = true))
        val picked = TargetCrop.sourceFor(calls, 2, somFile = { null }, shotFile = { File("shot-$it") })
        assertEquals("shot-0", picked?.name)
    }

    @Test fun `an annotated look wins, and with no look the call's own shot is used`() {
        val calls = listOf(inv(0, "tap", som = true), inv(1, "tap", shot = true))
        assertEquals("som-0", TargetCrop.sourceFor(calls, 1, somFile = { File("som-$it") }, shotFile = { File("shot-$it") })?.name)
        assertEquals("shot-0", TargetCrop.sourceFor(listOf(inv(0, "tap", shot = true)), 0, { null }, { File("shot-$it") })?.name)
    }

    @Test fun `bounds that are not four ordered numbers are ignored`() {
        assertEquals(null, TargetCrop.parseBounds("1,2,3"))
        assertEquals(null, TargetCrop.parseBounds("10,10,5,5"))
        assertEquals(TargetCrop.Box(1, 2, 3, 4), TargetCrop.parseBounds("1,2,3,4"))
    }

    // ── the story ──────────────────────────────────────────────────────

    @Test fun `the story reads in chapters - a headline wherever the plan step changes`() {
        val s = SessionLog(sessionId = "s", startedAtMillis = 0, agentLabel = null, tokenId = null).apply {
            narration += NarrationBeat(1, "nav", "Opened WhatsApp", "Step 1 of 2 · Open WhatsApp")
            narration += NarrationBeat(2, "look", "On WhatsApp · Chats", "Step 1 of 2 · Open WhatsApp")
            narration += NarrationBeat(3, "act", "Tapped Amma → Chat", "Step 2 of 2 · Open the chat")
        }
        val shape = storyEvents(s).map {
            when (it) {
                is StoryEvent.Headline -> "H:${it.text}"
                is StoryEvent.Beat -> it.beat.text
                is StoryEvent.Said -> "said"
            }
        }
        assertEquals(
            listOf("H:Step 1 of 2 · Open WhatsApp", "Opened WhatsApp", "On WhatsApp · Chats", "H:Step 2 of 2 · Open the chat", "Tapped Amma → Chat"),
            shape,
        )
    }

    @Test fun `an old session with no narration still tells something`() {
        val s = SessionLog(sessionId = "s", startedAtMillis = 0, agentLabel = null, tokenId = null).apply {
            invocations += inv(0, "launch_app")
        }
        assertEquals("Did: launch app", (storyEvents(s).single() as StoryEvent.Beat).beat.text)
    }

    // ── raw bodies ─────────────────────────────────────────────────────

    @Test fun `a raw request is indented and its images folded, never shown as base64`() {
        val b64 = "A".repeat(4000)
        val text = readableText("{\"messages\":[{\"image\":\"data:image/jpeg;base64,$b64\"}]}")
        assertFalse(text.contains(b64))
        assertTrue(text.contains("‹base64 · 2 KB›"))
        assertTrue("indented", text.lines().size > 3)
    }

    @Test fun `a prompt inside a request reads as lines, not one line of escaped newlines`() {
        val text = readableText("{\"messages\":[{\"content\":\"line one\\nline two\"}]}")
        assertTrue(text.lines().any { it.trim() == "line two\"" })
    }
}
