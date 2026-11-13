package com.aura.aura_ui.services

import com.aura.mcp.McpServerHealth
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rule under test: [AuraStatus] means agent activity, never server health. */
class ChipStatusPolicyTest {

    private val listening = McpServerHealth.Listening(
        port = 4816,
        host = "127.0.0.1",
        reachableAddresses = emptyList(),
    )

    @Test
    fun `a stopped server reports no activity`() {
        assertEquals(AuraStatus.Idle, ChipStatusPolicy.decide(McpServerHealth.Stopped, AuraStatus.Acting))
    }

    @Test
    fun `a starting server is not reported as an activity`() {
        assertEquals(AuraStatus.Idle, ChipStatusPolicy.decide(McpServerHealth.Starting, AuraStatus.Thinking))
    }

    @Test
    fun `a crashed server reports no activity`() {
        assertEquals(
            AuraStatus.Idle,
            ChipStatusPolicy.decide(McpServerHealth.Crashed(reason = "port in use"), AuraStatus.Acting),
        )
    }

    @Test
    fun `tool activity shows when the server is listening`() {
        assertEquals(AuraStatus.Acting, ChipStatusPolicy.decide(listening, AuraStatus.Acting))
    }

    @Test
    fun `everything idle stays idle`() {
        assertEquals(AuraStatus.Idle, ChipStatusPolicy.decide(listening, AuraStatus.Idle))
    }

    @Test
    fun `a paused agent shows nothing`() {
        assertEquals(AuraStatus.Idle, ChipStatusPolicy.decide(listening, AuraStatus.Acting, pausedByHuman = true))
    }
}
