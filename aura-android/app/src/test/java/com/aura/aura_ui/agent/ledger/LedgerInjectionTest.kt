package com.aura.aura_ui.agent.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerInjectionTest {

    @Test fun `finds every prior ledger block by its marker`() {
        val texts = listOf(
            "goal text",                                   // 0: initial goal (user)
            null,                                          // 1: assistant/tool message
            RunLedgerRenderer.MARKER + " ... ===\nGoal: g", // 2: last turn's ledger
            "Current screen (annotated). ...",             // 3: screenshot note
            null,                                          // 4
        )
        assertEquals(setOf(2), LedgerInjection.staleLedgerIndices(texts))
    }

    @Test fun `matches multiple stale blocks and nothing else`() {
        val texts = listOf(
            RunLedgerRenderer.MARKER + " v1",
            "unrelated user text mentioning RUN LEDGER later on",
            RunLedgerRenderer.MARKER + " v2",
        )
        assertEquals(setOf(0, 2), LedgerInjection.staleLedgerIndices(texts))
    }

    @Test fun `empty history has no stale blocks`() {
        assertTrue(LedgerInjection.staleLedgerIndices(emptyList()).isEmpty())
    }
}
