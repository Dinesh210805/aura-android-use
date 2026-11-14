package com.aura.aura_ui.agentcore

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers the pure rules behind scheduled tasks, saved skills and tool-output pruning. */
class SchedulerAndSkillsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(hour: Int, minute: Int) = ZonedDateTime.of(2026, 3, 10, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `a time of day parses to minutes and rejects nonsense`() {
        assertEquals(510, Schedule.parseDailyAt("08:30"))
        assertEquals(0, Schedule.parseDailyAt(" 0:00 "))
        assertNull(Schedule.parseDailyAt("24:00"))
        assertNull(Schedule.parseDailyAt("8.30"))
        assertNull(Schedule.parseDailyAt("every morning"))
    }

    @Test
    fun `a daily task runs later today or else tomorrow`() {
        assertEquals(at(8, 30), Schedule.next(at(7, 0), null, 510, zone))
        assertEquals(at(8, 30) + 24 * 3_600_000L, Schedule.next(at(8, 30), null, 510, zone))
    }

    @Test
    fun `an interval is never shorter than the minimum`() {
        assertEquals(at(7, 0) + 15 * 60_000L, Schedule.next(at(7, 0), 1, null, zone))
        assertEquals(at(7, 0) + 60 * 60_000L, Schedule.next(at(7, 0), 60, null, zone))
    }

    @Test
    fun `a heartbeat wakes only for a notification that shares a real word`() {
        val condition = "My Swiggy order is out for delivery"
        assertEquals(true, Heartbeat.matches(condition, "Swiggy Your order is on the way"))
        assertEquals(true, Heartbeat.matches(condition, "Delivery partner assigned"))
        assertEquals(false, Heartbeat.matches(condition, "WhatsApp Amma: when are you coming"))
        assertEquals(false, Heartbeat.matches("tell me when that is done", "Tell me what you think"))
    }

    @Test
    fun `a skill needs a plain name, a one-line description and no secrets`() {
        assertNull(SkillPolicy.rejection("order-coffee", "Use when ordering coffee.", "Open the app, pick the usual."))
        assertNotNull(SkillPolicy.rejection("Order Coffee", "Use when ordering coffee.", "body"))
        assertNotNull(SkillPolicy.rejection("order-coffee", "line one\nline two", "body"))
        assertNotNull(SkillPolicy.rejection("order-coffee", "Use when ordering coffee.", "the password is hunter22"))
    }

    @Test
    fun `old long tool results are cleared and recent ones kept`() {
        val long = "x".repeat(500)
        val messages = prompt("t") {
            user("go")
            repeat(3) { i ->
                toolCall("read_screen", "{}", "$i")
                toolResult("read_screen", "$i$long", "$i")
            }
        }.messages
        val results = ContextAssembler.pruneOldToolResults(messages, keepRecent = 1)
            .flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
        assertEquals(listOf(ContextAssembler.PRUNED_RESULT, ContextAssembler.PRUNED_RESULT, "2$long"), results.map { it.output })
    }

    @Test
    fun `bundled skills are installed once and a deleted one stays deleted`() {
        val store = SkillStore(java.nio.file.Files.createTempDirectory("skills").toFile())
        val bundled = mapOf(
            "send-email" to { "---\nname: Send Email\ndescription: Send an email from Gmail\nwhenToUse: when asked to email\n---\n# Steps\n1. Open Gmail" },
        )

        store.seed(bundled)
        assertEquals(listOf("send-email"), store.list().map { it.name })
        assertEquals("Send an email from Gmail. Use when asked to email", store.list().single().description)
        assertEquals("# Steps\n1. Open Gmail", store.read("send-email"))

        store.delete("send-email")
        store.seed(bundled)
        assertEquals(emptyList<Skill>(), store.list())
    }
}
