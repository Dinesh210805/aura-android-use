package com.aura.aura_ui.mcp.log

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The spoken/transcript line at the end of a handed-off task said `proof 1/0` on a run that
 * proved one finding against no required count — `target` is null for goal types the count gate
 * does not apply to, and it was being rendered as `0`. "1 out of 0" is not a thing.
 */
class ProofSummaryTest {

    @Test
    fun `a counted goal shows proven over target`() {
        val verdict = RunVerdict(verdict = "partial", claimed = "success", proven = 4, target = 10)
        assertEquals(" (verdict partial, proof 4/10)", proofSummaryOf(verdict))
    }

    @Test
    fun `an uncounted goal shows the proof count alone`() {
        val verdict = RunVerdict(verdict = "success", claimed = "success", proven = 1, target = null)
        assertEquals(" (verdict success, proof 1)", proofSummaryOf(verdict))
    }

    @Test
    fun `an uncounted goal with nothing proven still reads sensibly`() {
        val verdict = RunVerdict(verdict = "unverified", claimed = "success", proven = 0, target = null)
        assertEquals(" (verdict unverified, proof 0)", proofSummaryOf(verdict))
    }
}
