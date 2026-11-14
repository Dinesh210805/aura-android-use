package com.aura.aura_ui.agentcore

import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(kotlin.time.ExperimentalTime::class)
class UsageTest {
    private fun meta(input: Int?, output: Int?, metadata: JsonObject? = null) =
        ResponseMetaInfo.Empty.copy(inputTokensCount = input, outputTokensCount = output, metadata = metadata)

    @Test
    fun `a streamed reply keeps the token counts of its end frame`() {
        val reply = listOf(StreamFrame.TextDelta("Hi"), StreamFrame.End("stop", meta(900, 12))).toMessageResponse()
        assertEquals(Usage(900, 12, null, 128_000), Usage.of(reply.metaInfo, 128_000))
    }

    @Test
    fun `anthropic's cached tokens are added back to the input`() {
        val cache = JsonObject(mapOf("cacheReadInputTokens" to JsonPrimitive(3000), "cacheCreationInputTokens" to JsonPrimitive(500)))
        assertEquals(Usage(4000, 20, 3000, null), Usage.of(meta(500, 20, cache), null))
    }

    @Test
    fun `no counts, no reading`() {
        assertNull(Usage.of(meta(null, null), 128_000))
    }

    @Test
    fun `the meter fills against the model's window`() {
        assertEquals(0.25f, Usage(32_000, 100, null, 128_000).fill)
        assertNull(Usage(32_000, 100, null, null).fill)
    }
}
