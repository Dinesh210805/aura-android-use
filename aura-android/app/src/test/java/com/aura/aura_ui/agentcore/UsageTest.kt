package com.aura.aura_ui.agentcore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTest {
    private fun cached(json: String) = Usage.cached(Json.parseToJsonElement(json))

    @Test
    fun `cached tokens are read under each provider's name`() {
        assertEquals(800, cached("""{"usage":{"prompt_tokens_details":{"cached_tokens":800}}}"""))
        assertEquals(1200, cached("""{"cache_read_input_tokens":1200,"cache_creation_input_tokens":40}"""))
        assertEquals(64, cached("""{"usageMetadata":{"cachedContentTokenCount":64}}"""))
        assertNull(cached("""{"usage":{"prompt_tokens":10}}"""))
        assertNull(Usage.cached(null))
    }

    @Test
    fun `the meter fills against the model's window`() {
        assertEquals(0.25f, Usage(32_000, 100, null, 128_000).fill)
        assertNull(Usage(32_000, 100, null, null).fill)
    }
}
