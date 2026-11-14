package com.aura.aura_ui.agentcore

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAssemblerTest {

    @Test
    fun `only the newest screen observation survives`() {
        assertEquals(setOf(0, 2), ContextAssembler.staleIndices(listOf("read_screen", null, "perceive_screen", null, "read_screen")))
        assertEquals(emptySet<Int>(), ContextAssembler.staleIndices(listOf(null, "read_screen")))
    }

    @Test
    fun `diffs keep the full screen they are relative to`() {
        val tools = listOf("read_screen", null, "read_screen", "read_screen", null, "read_screen")
        // full, -, full, diff, -, diff: the second full screen and both diffs after it stay.
        val diff = listOf(false, false, false, true, false, true)
        assertEquals(setOf(0), ContextAssembler.staleIndices(tools, diff))
        // A new full screen makes the old full screen and its diffs stale.
        assertEquals(setOf(0, 2, 3, 5), ContextAssembler.staleIndices(tools + "read_screen", diff + false))
        // Only diffs in reach: keep the newest.
        assertEquals(setOf(0), ContextAssembler.staleIndices(listOf("read_screen", "read_screen"), listOf(true, true)))
    }

    @Test
    fun `a changes-only payload is recognised as a diff`() {
        assertTrue(ContextAssembler.isDiffScreen("SCREEN 1080x2400 x\nCHANGES since your last look; som_ids of unchanged elements are the same."))
        assertTrue(ContextAssembler.isDiffScreen("NO CHANGES since your last look."))
        assertFalse(ContextAssembler.isDiffScreen("SCREEN 1080x2400 x\n+----+\n| 1 Send |"))
    }

    @Test
    fun `stale screens are replaced in a real message history`() {
        val messages = prompt("t") {
            user("open settings")
            toolCall("read_screen", "{}", "1")
            toolResult("read_screen", "FIRST SCREEN", "1")
            toolCall("tap", "{}", "2")
            toolResult("tap", "tapped", "2")
            toolCall("read_screen", "{}", "3")
            toolResult("read_screen", "SECOND SCREEN", "3")
        }.messages

        val text = ContextAssembler.evictStaleScreens(messages).joinToString("\n") { it.toString() }

        assertFalse(text.contains("FIRST SCREEN"))
        assertTrue(text.contains(ContextAssembler.STALE_SCREEN))
        assertTrue(text.contains("SECOND SCREEN"))
        assertTrue(text.contains("tapped"))
    }

    @Test
    fun `a stored context decodes to what was encoded and never holds the system prompt`() {
        val messages = prompt("t") {
            system("rules")
            user("hello")
            assistant("hi there")
            toolCall("web_search", """{"q":"weather"}""", "1")
            toolResult("web_search", "sunny", "1")
        }.messages

        val stored = ContextAssembler.forStorage(messages)
        val decoded = ContextCodec.decode(ContextCodec.encode(stored))

        assertTrue(stored.none { it is Message.System })
        assertEquals(stored.map { it.textContent() }, decoded.map { it.textContent() })
        assertEquals(emptyList<Message>(), ContextCodec.decode("not json"))
    }

    @Test
    fun `taint survives storage`() {
        val origins = setOf(Origin.SCREEN, Origin.WEB)
        assertEquals(origins, ContextCodec.decodeTaint(ContextCodec.encodeTaint(origins)))
        assertEquals(emptySet<Origin>(), ContextCodec.decodeTaint(""))
    }

    @Test
    fun `the device block says only what can change an answer`() {
        val quiet = DeviceContext.render(DeviceSnapshot(nowMs = 0, zoneId = "UTC", batteryPercent = 80))
        assertEquals("<device_context>\nnow: Thu 1 Jan 1970, 00:00 (UTC)\n</device_context>", quiet)

        val busy = DeviceContext.render(
            DeviceSnapshot(
                nowMs = 0,
                zoneId = "UTC",
                foregroundApp = "Chat <b>App</b>",
                mediaPlaying = true,
                batteryPercent = 12,
                online = false,
                waitingNotifications = 3,
            ),
        )
        assertTrue(busy.contains("app in front: Chat  b App"))
        assertTrue(busy.contains("media: playing"))
        assertTrue(busy.contains("battery: 12%, not charging"))
        assertTrue(busy.contains("network: offline"))
        assertTrue(busy.contains("notifications waiting: 3"))
    }

    @Test
    fun `the compaction threshold is bounded on both sides`() {
        assertEquals(8_000, ContextBudget.forWindow(4_000).compactAtTokens)
        assertEquals(64_000, ContextBudget.forWindow(128_000).compactAtTokens)
        assertEquals(64_000, ContextBudget.forWindow(1_048_576).compactAtTokens)
        assertEquals(16_000, ContextBudget.forWindow(null).compactAtTokens)
    }

    @Test
    fun `a long result is cut on a line and says so`() {
        val text = (1..100).joinToString("\n") { "line $it" }
        val cut = ContextAssembler.clamp(text, 200)
        assertTrue(cut.length < text.length)
        assertTrue(cut.contains("truncated"))
        assertEquals("short", ContextAssembler.clamp("short", 200))
    }

    @Test
    fun `the system prompt carries the profile and the toolset menu`() {
        val built = SystemPrompt.build(profile = "Name: Dinesh", toolsetMenu = "screen: tap, read_screen")
        assertTrue(built.startsWith("You are AURA"))
        assertTrue(built.contains("<user_profile>\nName: Dinesh\n</user_profile>"))
        assertTrue(built.contains("<toolsets>\nscreen: tap, read_screen\n</toolsets>"))
        assertFalse(SystemPrompt.build(null, "").contains("<user_profile>"))
    }

    private val tapped = "Tapped Send\n{\"post_action_observation\":{\"settled\":true,\"settle_ms\":420,\"screen_changed\":true,\"top_labels\":[\"Chats\"],\"hint\":\"Verify from this\"}}"

    @Test
    fun `the settle summary is cut to settled and changed when the harness reads the screen`() {
        val out = ContextAssembler.withoutSummary(tapped)
        assertEquals("Tapped Send\n{\"post_action_observation\":{\"settled\":true,\"screen_changed\":true}}", out)
        val sensitive = "ok\n{\"post_action_observation\":{\"sensitive_foreground\":true,\"hint\":\"leave\"}}"
        assertEquals(sensitive, ContextAssembler.withoutSummary(sensitive))
    }

    @Test
    fun `a confident no-change skips the read, a low-confidence one does not`() {
        assertFalse(ContextAssembler.provedUnchanged(tapped))
        assertTrue(ContextAssembler.provedUnchanged("{\"post_action_observation\":{\"screen_changed\":false}}"))
        assertFalse(ContextAssembler.provedUnchanged("{\"post_action_observation\":{\"screen_changed\":false,\"screen_changed_confidence\":\"low\"}}"))
        assertFalse(ContextAssembler.provedUnchanged("Tapped"))
    }

    private val longTask = prompt("t") {
        system("rules")
        user("send Amma the Ola fare to the airport")
        (1..10).forEach { i ->
            toolCall("tap", """{"som_id":$i}""", "$i")
            toolResult("tap", "step $i done " + "x".repeat(400), "$i")
        }
        toolCall("read_screen", "{}", "last")
        toolResult("read_screen", "LAST SCREEN Ola fare 548", "last")
    }.messages

    @Test
    fun `compaction keeps the request and a tail that never starts on an orphan result`() {
        val range = ContextAssembler.compactionRange(longTask, keepTailTokens = 300)!!
        assertEquals(2, range.first) // after the system message and the request
        assertTrue(longTask[range.last + 1].parts.none { it is ai.koog.prompt.message.MessagePart.Tool.Result })

        val out = ContextAssembler.withSummary(longTask, range, "Done: tapped 1-8")
        assertEquals("send Amma the Ola fare to the airport", out[1].textContent())
        assertTrue(out[2].textContent().startsWith(ContextAssembler.SUMMARY_HEADER))
        assertTrue(out.last().toString().contains("LAST SCREEN Ola fare 548"))
    }

    @Test
    fun `a short conversation has nothing to compact`() {
        assertEquals(null, ContextAssembler.compactionRange(longTask.take(4), keepTailTokens = 300))
    }

    @Test
    fun `the transcript tags who said what`() {
        val t = ContextAssembler.transcript(longTask.subList(1, 4))
        assertTrue(t.startsWith("USER: send Amma"))
        assertTrue("ASSISTANT: tap({\"som_id\":1})" in t)
        assertTrue("RESULT tap: step 1 done" in t)
    }

    @Test
    fun `a tool call written as text is caught, prose naming a tool is not`() {
        val tools = setOf("tap", "read_screen", "end_session")
        assertTrue(ContextAssembler.writtenToolCall("<tool_call>{\"name\":\"tap\"}</tool_call>", tools))
        assertTrue(ContextAssembler.writtenToolCall("{\"name\": \"read_screen\", \"arguments\": {}}", tools))
        assertTrue(ContextAssembler.writtenToolCall("Now tap({\"som_id\": 12})", tools))
        assertTrue(ContextAssembler.writtenToolCall("tap(som_id=12)", tools))
        assertFalse(ContextAssembler.writtenToolCall("I'll tap (the blue button) next. Done!", tools))
        assertFalse(ContextAssembler.writtenToolCall("Sent it. {\"name\": \"Amma\"}", tools))
    }

    @Test
    fun `the default talk style leaves the system prompt unchanged and the others add one block`() {
        val plain = SystemPrompt.build("likes tea", "menu", "skills", "Pixel 9, Android 16")
        assertEquals(plain, SystemPrompt.build("likes tea", "menu", "skills", "Pixel 9, Android 16", TalkStyle.BALANCED))
        val brief = SystemPrompt.build("likes tea", "menu", "skills", "Pixel 9, Android 16", TalkStyle.BRIEF)
        assertEquals(1, Regex("<how_you_talk>").findAll(brief).count())
        assertEquals(plain.length + 2 + TalkStyle.BRIEF.block.length, brief.length)
    }
}
