package com.aura.aura_ui.services

import com.aura.aura_ui.services.ChipVisibilityPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class ChipVisibilityPolicyTest {

    private fun decide(
        automation: Boolean = false,
        foreground: Boolean = false,
        clients: Int = 0,
        tool: AuraStatus = AuraStatus.Idle,
        voice: AuraStatus = AuraStatus.Idle,
    ) = ChipVisibilityPolicy.decide(automation, foreground, clients, tool, voice)

    @Test fun `backgrounded idle with no clients hides the pill`() {
        // The user's complaint: "AURA ready" pinned forever while nothing happens.
        assertEquals(Mode.SILENT, decide())
    }

    @Test fun `app in foreground shows the chip`() {
        assertEquals(Mode.CHIP, decide(foreground = true))
    }

    @Test fun `a connected MCP client shows the chip even in background`() {
        assertEquals(Mode.CHIP, decide(clients = 1))
    }

    @Test fun `live tool activity shows the chip`() {
        assertEquals(Mode.CHIP, decide(tool = AuraStatus.Acting))
    }

    @Test fun `voice activity shows the chip`() {
        assertEquals(Mode.CHIP, decide(voice = AuraStatus.Listening))
    }

    @Test fun `the overlay automation pill always wins - chip goes silent`() {
        // Double-pill fix: during automation the overlay posts the richer pill
        // (progress + Cancel); this chip must yield even with the app open.
        assertEquals(Mode.SILENT, decide(automation = true, foreground = true, clients = 2, tool = AuraStatus.Acting))
    }
}
