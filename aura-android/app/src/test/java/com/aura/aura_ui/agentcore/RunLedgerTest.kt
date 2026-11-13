package com.aura.aura_ui.agentcore

import ai.koog.prompt.dsl.prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLedgerTest {

    @Test
    fun `the ledger shows the plan and folds old actions into a count`() {
        val ledger = RunLedger()
        ledger.plan(listOf("check Uber", "check Ola", "book cheaper"), done = 2)
        (1..15).forEach { ledger.action("tap {\"som_id\":$it} -> ok") }
        val text = ledger.render("book the cheaper cab", step = 23, minutes = 6, screens = "S1..S9")
        assertTrue(text.startsWith(ContextAssembler.LEDGER))
        assertTrue("[x] check Uber  [x] check Ola  [ ] book cheaper" in text)
        assertTrue("(3 earlier not listed)" in text)
        assertTrue("som_id\":15" in text && "som_id\":3}" !in text)
        assertTrue(text.endsWith("Step 23 · 6 min"))
        assertEquals(2, ledger.planDone)
    }

    @Test
    fun `only the newest ledger stays in the history`() {
        val messages = prompt("t") {
            user("task")
            user("${ContextAssembler.LEDGER} old")
            user("${ContextAssembler.LEDGER} new")
        }.messages
        val kept = ContextAssembler.evictStaleScreens(messages).map { it.textContent() }
        assertEquals(listOf("task", "${ContextAssembler.LEDGER} new"), kept)
    }

    @Test
    fun `the ledger and the stuck note are not saved for the next turn`() {
        val messages = prompt("t") {
            user("task")
            user("${ContextAssembler.LEDGER} Task: task")
            user("${ContextAssembler.STUCK} change route")
        }.messages
        assertEquals(listOf("task"), ContextAssembler.forStorage(messages).map { it.textContent() })
    }

    @Test
    fun `going back and forth between two screens adds no distinct screens`() {
        val h = ScreenHistory()
        val a = "SEEN: app | A\nSCREEN 1x1\n| 1 A |"
        val b = "SEEN: app | B\nSCREEN 1x1\n| 1 B |"
        listOf(a, b, a, b, a).forEach { h.record(it) }
        assertEquals(2, h.distinct)
        h.record("SEEN: app | B\nSCREEN 1x1\nNO CHANGES since your last look.")
        assertEquals(2, h.distinct)
        assertEquals("S1..S5.1", h.span())
    }

    @Test
    fun `the digest summary keeps the plan and never the raw action arguments`() {
        val ledger = RunLedger()
        ledger.plan(listOf("Open WhatsApp", "Send the message"), done = 1)
        ledger.action("type_text {\"text\":\"my PIN is 4821\"} -> ok")
        val summary = ledger.summary()
        org.junit.Assert.assertTrue(summary, summary.startsWith("Plan: [x] Open WhatsApp"))
        org.junit.Assert.assertFalse(summary, "4821" in summary)
    }
}
