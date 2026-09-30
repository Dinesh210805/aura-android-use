package com.aura.aura_ui.agent.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Crash fix: Gemini 3.x thought-only responses arrive with `choices[0]` missing
 * `finish_reason`; Koog's `OpenAIChoice` requires the field and kills the run with
 * "Field 'finishReason' is required … but it was missing at path: $.choices[0]"
 * (observed in on-device runs 1783008241421 / 1783007902441). The repair injects
 * `finish_reason: "stop"` so deserialization survives.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiFinishReasonRepairTest {

    @Test
    fun `injects stop when finish_reason is missing`() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant","content":"<thought>x</thought>"}}]}"""
        val out = GeminiFinishReasonRepair.withFinishReason(body)
        requireNotNull(out)
        val choice = JSONObject(out).getJSONArray("choices").getJSONObject(0)
        assertEquals("stop", choice.getString("finish_reason"))
    }

    @Test
    fun `injects stop when finish_reason is json null`() {
        val body = """{"choices":[{"index":0,"finish_reason":null,"message":{"role":"assistant","content":""}}]}"""
        val out = GeminiFinishReasonRepair.withFinishReason(body)
        requireNotNull(out)
        val choice = JSONObject(out).getJSONArray("choices").getJSONObject(0)
        assertEquals("stop", choice.getString("finish_reason"))
    }

    @Test
    fun `returns null when finish_reason is present`() {
        val body = """{"choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant"}}]}"""
        assertNull(GeminiFinishReasonRepair.withFinishReason(body))
    }

    @Test
    fun `repairs only the choices that need it`() {
        val body = """{"choices":[
            {"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"a"}},
            {"index":1,"message":{"role":"assistant","content":"b"}}
        ]}"""
        val out = GeminiFinishReasonRepair.withFinishReason(body)
        requireNotNull(out)
        val choices = JSONObject(out).getJSONArray("choices")
        assertEquals("stop", choices.getJSONObject(0).getString("finish_reason"))
        assertEquals("stop", choices.getJSONObject(1).getString("finish_reason"))
    }

    @Test
    fun `preserves the message payload it repairs around`() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant","content":"<thought>x</thought>"}}],"usage":{"total_tokens":9}}"""
        val out = GeminiFinishReasonRepair.withFinishReason(body)
        requireNotNull(out)
        val root = JSONObject(out)
        assertEquals("<thought>x</thought>", root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        assertEquals(9, root.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun `fails open on malformed json`() {
        assertNull(GeminiFinishReasonRepair.withFinishReason("not json"))
    }

    @Test
    fun `fails open when choices is absent`() {
        assertNull(GeminiFinishReasonRepair.withFinishReason("""{"error":{"message":"boom"}}"""))
    }
}
