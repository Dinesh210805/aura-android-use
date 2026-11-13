package com.aura.aura_ui.agentcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenHistoryTest {

    private val uberHome = "SEEN: com.ubercab | Where to? | Ride\nSCREEN 1080x2400\n| 1 Where to? |\n| 2 Ride |"
    private val uberFares = "SEEN: com.ubercab | UberGo ₹612 | Book\nSCREEN 1080x2400\n| 1 UberGo ₹612 |\n| 2 Book |"
    private val fareChanged = "SEEN: com.ubercab | UberGo ₹598\nSCREEN 1080x2400\nCHANGES since your last look; som_ids of unchanged elements are the same.\n~ 1 UberGo ₹598"

    @Test
    fun `new screens are commits and changes are diffs on them`() {
        val h = ScreenHistory()
        assertEquals("S1", h.record(uberHome))
        assertEquals("S2", h.record(uberFares))
        assertEquals("S2.1", h.record(fareChanged))
        assertEquals("S3", h.record(uberHome))
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
        val d = h.diff("S1", "S2")!!
        assertTrue(d.startsWith("--- S1\n+++ S2"))
        assertTrue("- | 1 Where to? |" in d && "+ | 1 UberGo ₹612 |" in d)
        assertNull(h.diff("S1", "S9"))
    }
}
