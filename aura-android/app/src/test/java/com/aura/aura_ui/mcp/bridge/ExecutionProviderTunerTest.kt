package com.aura.aura_ui.mcp.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for [chooseBestProvider]. No ORT, no Android — these run on
 * the plain JVM (`./gradlew app:test`). The actual session benchmarking is
 * integration-only and verified on-device.
 */
class ExecutionProviderTunerTest {

    @Test
    fun `picks the lowest-latency provider`() {
        val results = mapOf(
            ExecutionProvider.XNNPACK to 210L,
            ExecutionProvider.NNAPI to 2400L,
            ExecutionProvider.CPU to 1800L,
        )
        assertEquals(ExecutionProvider.XNNPACK, chooseBestProvider(results))
    }

    @Test
    fun `NNAPI wins when it is genuinely fastest`() {
        val results = mapOf(
            ExecutionProvider.XNNPACK to 500L,
            ExecutionProvider.NNAPI to 120L,
            ExecutionProvider.CPU to 1800L,
        )
        assertEquals(ExecutionProvider.NNAPI, chooseBestProvider(results))
    }

    @Test
    fun `failed providers are absent and never chosen`() {
        // Only CPU built/ran successfully (NNAPI + XNNPACK threw and were excluded).
        val results = mapOf(ExecutionProvider.CPU to 1800L)
        assertEquals(ExecutionProvider.CPU, chooseBestProvider(results))
    }

    @Test
    fun `empty map falls back to CPU`() {
        assertEquals(ExecutionProvider.CPU, chooseBestProvider(emptyMap()))
    }

    @Test
    fun `on a tie an accelerator beats plain CPU`() {
        val results = mapOf(
            ExecutionProvider.XNNPACK to 300L,
            ExecutionProvider.CPU to 300L,
        )
        assertEquals(ExecutionProvider.XNNPACK, chooseBestProvider(results))
    }

    @Test
    fun `on a tie the earlier-declared accelerator wins`() {
        val results = mapOf(
            ExecutionProvider.NNAPI to 300L,
            ExecutionProvider.XNNPACK to 300L,
        )
        // XNNPACK is declared before NNAPI → preferred on an exact tie.
        assertEquals(ExecutionProvider.XNNPACK, chooseBestProvider(results))
    }
}
