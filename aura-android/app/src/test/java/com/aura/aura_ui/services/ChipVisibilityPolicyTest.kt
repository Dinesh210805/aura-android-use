package com.aura.aura_ui.services

import com.aura.aura_ui.services.ChipVisibilityPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class ChipVisibilityPolicyTest {

    @Test fun `idle never shows a chip, even with the app open`() {
        assertEquals(Mode.SILENT, ChipVisibilityPolicy.decide(automationPillActive = false, status = AuraStatus.Idle))
    }

    @Test fun `agent work shows the chip`() {
        assertEquals(Mode.CHIP, ChipVisibilityPolicy.decide(automationPillActive = false, status = AuraStatus.Acting))
    }

    @Test fun `the overlay automation pill wins so only one chip shows`() {
        assertEquals(Mode.SILENT, ChipVisibilityPolicy.decide(automationPillActive = true, status = AuraStatus.Acting))
    }
}
