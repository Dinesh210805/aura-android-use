package com.aura.aura_ui.agent.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiStreamToolIndexRepairTest {

    private fun indexes(line: String): List<Int> =
        Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject["choices"]!!.jsonArray[0].jsonObject["delta"]!!
            .jsonObject["tool_calls"]!!.jsonArray.map { it.jsonObject["index"]!!.jsonPrimitive.int }

    // The shape Gemini's OpenAI-compatible endpoint streams: a whole call per item, no `index`.
    private val geminiChunk = """data: {"choices":[{"delta":{"role":"assistant","tool_calls":[{"function":{"arguments":"{\"query\":\"github\"}","name":"find_tools"},"id":"call_1","type":"function"}]},"finish_reason":"tool_calls","index":0}],"created":1,"id":"x","model":"gemini-3.5-flash","object":"chat.completion.chunk"}"""

    @Test
    fun `a tool call without index gets one, numbered across the stream`() {
        val next = GeminiStreamToolIndexRepair.Counter()
        assertEquals(listOf(0), indexes(GeminiStreamToolIndexRepair.withToolIndex(geminiChunk, next)))
        assertEquals(listOf(1), indexes(GeminiStreamToolIndexRepair.withToolIndex(geminiChunk, next)))
    }

    @Test
    fun `an item that has an index and other lines pass through unchanged`() {
        val next = GeminiStreamToolIndexRepair.Counter()
        val withIndex = """data: {"choices":[{"delta":{"tool_calls":[{"index":3,"id":"c","function":{"name":"x","arguments":"{}"}}]},"index":0}]}"""
        assertEquals(withIndex, GeminiStreamToolIndexRepair.withToolIndex(withIndex, next))
        val text = """data: {"choices":[{"delta":{"content":"hi"},"index":0}]}"""
        assertEquals(text, GeminiStreamToolIndexRepair.withToolIndex(text, next))
        assertEquals("data: [DONE]", GeminiStreamToolIndexRepair.withToolIndex("data: [DONE]", next))
        assertEquals("", GeminiStreamToolIndexRepair.withToolIndex("", next))
        assertEquals("data: {\"tool_calls\": broken", GeminiStreamToolIndexRepair.withToolIndex("data: {\"tool_calls\": broken", next))
    }
}
