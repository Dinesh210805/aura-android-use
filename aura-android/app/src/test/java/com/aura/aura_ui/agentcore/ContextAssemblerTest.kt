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
}
