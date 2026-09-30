package com.aura.aura_ui.agent.conversation

import com.aura.mcp.bridge.MediaBridge
import com.aura.mcp.bridge.MediaCommand
import com.aura.mcp.bridge.MediaCommandResult
import com.aura.mcp.bridge.MediaSessionSnapshot
import com.aura.mcp.bridge.SpecialAccessState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionToolRouterTest {
    private class RecordingHandler(val label: String) : CompanionToolHandler {
        var lastTool: String? = null
        var sawEscalationSink = false
        override suspend fun handle(call: CompanionToolCall, escalate: EscalationSink): CompanionToolResult {
            lastTool = call.name
            sawEscalationSink = escalate !== EscalationSink.NONE
            return CompanionToolResult("$label:${call.name}")
        }
    }

    private fun directTools() = CompanionDirectTools(
        media = object : MediaBridge {
            override fun accessState() = SpecialAccessState.ENABLED
            override suspend fun activeSessions() = emptyList<MediaSessionSnapshot>()
            override suspend fun sendCommand(packageName: String?, command: MediaCommand) =
                MediaCommandResult(true, packageName, null)
        },
        deviceContext = object : DeviceContextProvider {
            override suspend fun snapshot() =
                DeviceContextSnapshot("now", null, false, null, null, null, null, null)
        },
        deviceControls = object : DeviceControls {
            override suspend fun applySetting(setting: String, state: String?) = ControlResult("ok", true)
            override suspend fun doAction(action: String) = ControlResult("ok", true)
        },
    )

    @Test
    fun `direct tools go to CompanionDirectTools, everything else to primary`() = runTest {
        val primary = RecordingHandler("primary")
        val router = CompanionToolRouter(primary, directTools())

        router.handle(
            CompanionToolCall("device_settings", JsonObject(mapOf("setting" to JsonPrimitive("flashlight")))),
        )
        assertNull("primary NOT invoked for a direct tool", primary.lastTool)

        router.handle(CompanionToolCall("ask_aura", JsonObject(emptyMap())))
        assertEquals("ask_aura", primary.lastTool)
    }

    /**
     * Only the primary handler can escalate. The direct lane is every-tool-in-milliseconds, so
     * handing it a live sink would be offering a capability nothing there can use.
     */
    @Test
    fun `the escalation sink reaches the primary handler`() = runTest {
        val primary = RecordingHandler("primary")
        val router = CompanionToolRouter(primary, directTools())

        router.handle(CompanionToolCall("ask_aura", JsonObject(emptyMap()))) { Escalation.ACKED }

        assertEquals(true, primary.sawEscalationSink)
    }
}
