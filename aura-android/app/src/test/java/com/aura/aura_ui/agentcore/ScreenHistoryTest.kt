package com.aura.aura_ui.agentcore

import com.aura.mcp.tools.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenHistoryTest {

    private val uberHome = "SEEN: com.ubercab | Where to? | Ride\nSCREEN 1080x2400\n| 1 Where to? |\n| 2 Ride |"
    private val uberFares = "SEEN: com.ubercab | UberGo ₹612 | Book\nSCREEN 1080x2400\n| 1 UberGo ₹612 |\n| 2 Book |"
    private val fareChanged = "SEEN: com.ubercab | UberGo ₹598\nSCREEN 1080x2400\nCHANGES since your last look; som_ids of unchanged elements are the same.\n~ 1 UberGo ₹598"

    private fun snap(text: String, content: Long, pkg: String = "com.ubercab", blind: Boolean = false) =
        ScreenSnapshot.Snapshot(pkg, text, content, layoutKey = 0, blind = blind)

    @Test
    fun `every screen is the next id on one timeline`() {
        val h = ScreenHistory()
        assertEquals("S1", h.record(uberHome)?.id)
        assertEquals("S2", h.record(uberFares)?.id)
        assertEquals("S3", h.record(fareChanged)?.id)
        assertEquals("S4", h.record(uberHome)?.id)
        assertNull(h.record("tap 3 -> ok"))
    }

    @Test
    fun `the log is searchable and a screen comes back exactly`() {
        val h = ScreenHistory().apply { record(uberHome); record(uberFares) }
        assertTrue(h.log(null).lines().size == 2)
        assertEquals("S2  com.ubercab | UberGo ₹612 | Book", h.log("612"))
        assertEquals(uberFares, h.show("s2"))
    }

    @Test
    fun `screen_diff shows git-style lines`() {
        val h = ScreenHistory().apply { record(uberHome); record(uberFares) }
        val d = requireNotNull(h.diff("S1", "S2"))
        assertTrue(d.startsWith("--- S1\n+++ S2"))
        assertTrue("- | 1 Where to? |" in d && "+ | 1 UberGo ₹612 |" in d)
        assertNull(h.diff("S1", "S9"))
    }

    @Test
    fun `passed screens share the timeline, and the same content twice in a row is one entry`() {
        val h = ScreenHistory()
        assertEquals("S1", h.passed(snap(uberHome, content = 1))?.id)
        assertNull(h.passed(snap(uberHome, content = 1)))
        assertEquals("S2", h.passed(snap(uberFares, content = 3))?.id)
        assertTrue("(passed, not read)" in h.log(null))
    }

    @Test
    fun `coming back to a screen links it to the earlier one`() {
        val h = ScreenHistory()
        h.passed(snap(uberHome, content = 1))
        h.passed(snap(uberFares, content = 3))
        val back = requireNotNull(h.passed(snap(uberHome, content = 1)))
        assertEquals("S3", back.id)
        assertEquals("S1", back.sameAs)
        assertTrue(h.log(null).lines().last().startsWith("S3 = S1 (passed, not read)"))
    }

    @Test
    fun `a model read of a screen AURA already recorded marks it read instead of copying it`() {
        val h = ScreenHistory()
        h.passed(snap(uberHome, content = 1))
        val read = requireNotNull(h.record(uberHome, snap(uberHome, content = 1)))
        assertEquals("S1", read.id)
        assertFalse(read.added)
        assertFalse("(passed, not read)" in h.log(null))
        assertEquals(1, h.log(null).lines().size)
    }

    @Test
    fun `a changes-only read can still be opened whole`() {
        val h = ScreenHistory()
        h.record(uberFares, snap(uberFares, content = 3))
        val id = h.record(fareChanged, snap(uberFares.replace("612", "598"), content = 4))?.id
        assertEquals("S2", id)
        assertTrue("UberGo ₹598 |" in requireNotNull(h.show("S2")))
    }

    @Test
    fun `only the model's reads count toward stuck detection`() {
        val h = ScreenHistory()
        h.passed(snap(uberHome, content = 1))
        h.passed(snap(uberFares, content = 3))
        assertEquals(0, h.distinct)
        h.record(uberHome)
        assertEquals(1, h.distinct)
    }

    @Test
    fun `blind screens of one app are one entry until something else shows`() {
        val h = ScreenHistory()
        assertEquals("S1", h.passed(snap("SEEN: game | (no readable elements)", 0, pkg = "game", blind = true))?.id)
        assertNull(h.passed(snap("SEEN: game | (no readable elements)", 5, pkg = "game", blind = true)))
        assertEquals("S2", h.passed(snap(uberHome, content = 1))?.id)
    }

    @Test
    fun `numbering goes on after the history is saved and loaded`() {
        val h = ScreenHistory("run1").apply { record(uberHome); record(uberFares); record(fareChanged) }
        val next = ScreenHistory("run2", ScreenHistory.decode(h.encode()))
        assertEquals("S4", next.record(uberHome)?.id)
    }

    @Test
    fun `a hidden entry is saved with no text and no note`() {
        val h = ScreenHistory("run1")
        h.record("SEEN: com.bank | Balance\nSCREEN 1x1\n| 1 Balance 4,000 |", hidden = true)
        h.annotate("checking the balance")
        val e = ScreenHistory.decode(h.encode()).single()
        assertTrue(e.hidden)
        assertEquals("", e.text)
        assertNull(e.note)
    }

    @Test
    fun `past 300 entries the oldest goes and ids are never reused`() {
        val h = ScreenHistory("run1")
        repeat(301) { h.passed(snap("SEEN: app | screen $it", content = it.toLong(), pkg = "app")) }
        val kept = ScreenHistory.decode(h.encode())
        assertEquals(ScreenHistory.MAX_KEPT, kept.size)
        assertEquals("S2", kept.first().id)
        assertEquals("S302", ScreenHistory("run2", kept).passed(snap("SEEN: app | new", content = 999, pkg = "app"))?.id)
    }

    @Test
    fun `an unreadable file loads as an empty history`() {
        assertTrue(ScreenHistory.decode("{not json").isEmpty())
        assertTrue(ScreenHistory.decode(null).isEmpty())
    }
}
