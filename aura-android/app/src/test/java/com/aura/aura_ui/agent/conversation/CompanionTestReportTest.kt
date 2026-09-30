package com.aura.aura_ui.agent.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionTestReportTest {
    @Test fun `encodes a successful tool dispatch into parseable json`() {
        val report = CompanionTestReport.encode(
            action = "tool", request = "drive_phone", ok = true,
            summary = "opened spotify", toolCalls = listOf("drive_phone"),
            elapsedMs = 1234, nowMs = 99,
        )
        val o = Json.parseToJsonElement(report).jsonObject
        assertEquals("tool", o["action"]!!.jsonPrimitive.content)
        assertEquals("drive_phone", o["request"]!!.jsonPrimitive.content)
        assertTrue(o["ok"]!!.jsonPrimitive.boolean)
        assertEquals("opened spotify", o["summary"]!!.jsonPrimitive.content)
        assertEquals("drive_phone", o["toolCalls"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(1234L, o["elapsedMs"]!!.jsonPrimitive.long)
        assertEquals(99L, o["ts"]!!.jsonPrimitive.long)
    }

    @Test fun `error outcome still produces parseable json`() {
        val report = CompanionTestReport.encode(
            action = "live", request = "what did you just do?", ok = false,
            summary = "connect failed", toolCalls = emptyList(), elapsedMs = 0, nowMs = 0,
        )
        val o = Json.parseToJsonElement(report).jsonObject
        assertFalse(o["ok"]!!.jsonPrimitive.boolean)
        assertTrue(o["toolCalls"]!!.jsonArray.isEmpty())
    }
}
