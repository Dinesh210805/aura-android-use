package com.aura.aura_ui.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefixProbeTest {

    private fun body(tools: String, vararg msgs: String) =
        """{"model":"m","tools":[$tools],"messages":[${msgs.joinToString(",") { """{"role":"user","content":"$it"}""" }}]}"""

    @Test fun `first call has nothing to compare`() {
        assertNull(PrefixProbe.describe(null, body("1", "a")))
    }

    @Test fun `append-only history keeps the whole prefix`() {
        assertEquals("append-only (1→2 msgs); tools same", PrefixProbe.describe(body("1", "a"), body("1", "a", "b")))
    }

    @Test fun `names the first rewritten message and the tool list change`() {
        val out = PrefixProbe.describe(body("1", "sys", "perceive result"), body("2", "sys", "perceive placeholder", "x"))
        // Offset depends on org.json's key order, so pin the parts that carry the diagnosis.
        assertEquals(true, out!!.startsWith("msg 1/3 (user) differs @"))
        assertEquals(true, out.contains("\"result'}\" → \"placeholder'}\""))
        assertEquals(true, out.endsWith("; TOOLS CHANGED"))
    }

    @Test fun `a deleted message reads as a rewrite at its index`() {
        val out = PrefixProbe.describe(body("1", "sys", "ledger", "shot"), body("1", "sys", "shot"))
        assertEquals(true, out!!.startsWith("msg 1/2 (user) differs"))
    }
}
