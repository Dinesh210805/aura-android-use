package com.aura.aura_ui.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

class MCPProtocolTest {

    @Test
    fun `buildGestureAck returns correct JSON on success`() {
        val commandId = "cmd_123"
        val jsonString = MCPProtocol.buildGestureAck(commandId, true, null).toString()

        assertTrue(jsonString.contains(""""type":"gesture_ack""""))
        assertTrue(jsonString.contains(""""command_id":"cmd_123""""))
        assertTrue(jsonString.contains(""""success":true"""))
        assertTrue(jsonString.contains(""""timestamp""""))
    }

    @Test
    fun `buildGestureAck returns correct JSON on failure`() {
        val commandId = "cmd_123"
        val errorMessage = "Failed to tap"
        val jsonString = MCPProtocol.buildGestureAck(commandId, false, errorMessage).toString()

        assertTrue(jsonString.contains(""""type":"gesture_ack""""))
        assertTrue(jsonString.contains(""""command_id":"cmd_123""""))
        assertTrue(jsonString.contains(""""success":false"""))
        assertTrue(jsonString.contains(""""error":"Failed to tap""""))
    }

    @Test
    fun `buildScreenshotResponse returns correct JSON on success`() {
        val requestId = "req_123"
        val base64 = "base64_string_here"
        val jsonString = MCPProtocol.buildScreenshotResponse(requestId, base64, 1080, 1920).toString()

        assertTrue(jsonString.contains(""""type":"screenshot_response""""))
        assertTrue(jsonString.contains(""""request_id":"req_123""""))
        assertTrue(jsonString.contains(""""screenshot_base64":"base64_string_here""""))
        assertTrue(jsonString.contains(""""screen_width":1080"""))
        assertTrue(jsonString.contains(""""screen_height":1920"""))
    }

    @Test
    fun `buildCommandResult returns correct JSON`() {
        val commandId = "cmd_123"
        val commandType = "launch_app"
        val jsonString = MCPProtocol.buildCommandResult(commandId, commandType, true, null).toString()

        assertTrue(jsonString.contains(""""type":"command_result""""))
        assertTrue(jsonString.contains(""""command_id":"cmd_123""""))
        assertTrue(jsonString.contains(""""command_type":"launch_app""""))
        assertTrue(jsonString.contains(""""success":true"""))
    }

    @Test
    fun `buildContactResolutionResult returns correct JSON`() {
        val requestId = "req_123"
        val contactName = "John Doe"
        val phoneNumber = "+123456789"
        val jsonString = MCPProtocol.buildContactResolutionResult(requestId, contactName, phoneNumber, true, null).toString()

        assertTrue(jsonString.contains(""""type":"contact_resolution_result""""))
        assertTrue(jsonString.contains(""""request_id":"req_123""""))
        assertTrue(jsonString.contains(""""contact_name":"John Doe""""))
        assertTrue(jsonString.contains(""""phone_number":"+123456789""""))
        assertTrue(jsonString.contains(""""success":true"""))
    }
}
