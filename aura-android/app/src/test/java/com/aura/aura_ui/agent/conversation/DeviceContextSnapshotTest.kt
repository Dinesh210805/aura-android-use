package com.aura.aura_ui.agent.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceContextSnapshotTest {
    @Test
    fun `summary mentions battery time and now playing when present`() {
        val s = DeviceContextSnapshot(
            timeText = "3:42 PM, Thursday", batteryPercent = 62, isCharging = false,
            foregroundApp = "WhatsApp", nowPlaying = "Bohemian Rhapsody by Queen",
            wifiOn = true, bluetoothOn = false, dndOn = false,
        )
        val out = s.toSpokenSummary()
        assertTrue(out.contains("62%"))
        assertTrue(out.contains("3:42 PM"))
        assertTrue(out.contains("Bohemian Rhapsody"))
    }

    @Test
    fun `summary omits missing signals gracefully`() {
        val s = DeviceContextSnapshot(
            timeText = "9:00 AM", batteryPercent = null, isCharging = false,
            foregroundApp = null, nowPlaying = null, wifiOn = null, bluetoothOn = null, dndOn = null,
        )
        val out = s.toSpokenSummary()
        assertTrue(out.contains("9:00 AM"))
        assertFalse("no battery signal → not mentioned", out.contains("battery"))
    }
}
