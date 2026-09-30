package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.agent.memory.Commitment
import com.aura.aura_ui.agent.memory.MemoryEntry
import com.aura.aura_ui.agent.memory.MemoryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class GreetingBriefingTest {
    private val utc = ZoneId.of("UTC")
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 9, d, h, m, 0, 0, utc).toInstant().toEpochMilli()
    private val now = at(14, 8) // Mon 14 Sep, 08:00

    private fun log(id: String, text: String, t: Long, sensitive: Boolean = false) =
        MemoryEntry(id, MemoryType.ACTION_LOG, text, sensitive, t)

    @Test fun `nothing going on means no section and a plain hello`() {
        assertNull(GreetingBriefing.build(emptyList(), emptyList(), emptyList(), now, firstToday = true, zone = utc))
    }

    @Test fun `first open of the day carries yesterday, today's reminders and what is waiting`() {
        val out = GreetingBriefing.build(
            notable = listOf("2 unread: WhatsApp (Amma)"),
            history = listOf(
                log("a", "order biryani → Ordered.", at(13, 20)),
                log("b", "two days ago", at(12, 20)),
                log("c", "this morning already", at(14, 7)),
                log("s", "pay card → Paid.", at(13, 21), sensitive = true),
            ),
            pending = listOf(
                Commitment("r1", "send AndroidWorld results", at(14, 18)),
                Commitment("r2", "tomorrow's thing", at(15, 9)),
                Commitment("r3", "already due", at(14, 7)),
            ),
            nowEpochMs = now,
            firstToday = true,
            zone = utc,
        )!!
        assertTrue(out.startsWith(GreetingBriefing.HEADING))
        assertTrue(out.contains("morning"))
        assertTrue(out.contains("WhatsApp (Amma)"))
        assertTrue(out.contains("order biryani"))
        assertFalse("only yesterday", out.contains("two days ago") || out.contains("this morning already"))
        assertFalse("sensitive rows never go to the prompt", out.contains("pay card"))
        assertTrue(out.contains("send AndroidWorld results at 18:00"))
        assertFalse("only the rest of today", out.contains("tomorrow's thing") || out.contains("already due"))
    }

    @Test fun `later in the day yesterday is dropped`() {
        val out = GreetingBriefing.build(
            notable = emptyList(),
            history = listOf(log("a", "order biryani → Ordered.", at(13, 20))),
            pending = emptyList(),
            nowEpochMs = now,
            firstToday = false,
            zone = utc,
        )
        assertNull(out)
    }
}
