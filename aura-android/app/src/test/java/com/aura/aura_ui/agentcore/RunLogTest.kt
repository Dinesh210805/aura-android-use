package com.aura.aura_ui.agentcore

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers how span files and screenshots on disk become the runs the log screen shows. */
class RunLogTest {
    private val dir: File = Files.createTempDirectory("traces").toFile()

    private fun span(name: String, trace: String, startMs: Long, endMs: Long, status: String = "Unset", attributes: String = "{}") =
        """{"name":"$name","trace_id":"$trace","span_id":"s","parent_span_id":"p",""" +
            """"start_ns":${startMs * 1_000_000},"end_ns":${endMs * 1_000_000},"status":"$status","attributes":$attributes}"""

    @Test
    fun `spans are grouped into runs with model calls, tool calls and screenshots in order`() {
        File(dir, "spans-2026-03-10.jsonl").writeText(
            listOf(
                span("invoke_agent aura", "t1", 1_000, 9_000),
                span("node loop", "t1", 1_000, 9_000),
                span("chat gemini-flash", "t1", 1_100, 2_000, attributes = """{"gen_ai.usage.input_tokens":900,"gen_ai.usage.output_tokens":40}"""),
                span("execute_tool tap", "t1", 2_100, 2_600, status = "Error"),
                span("chat gemini-flash", "t2", 20_000, 21_000),
            ).joinToString("\n"),
        )
        File(dir, "screen-2300.png").writeBytes(byteArrayOf(1))
        File(dir, "screen-50000.png").writeBytes(byteArrayOf(1))

        val runs = AgentTelemetry(dir, "test").recent()

        assertEquals(listOf(20_000L, 1_000L), runs.map { it.startMs })
        val first = runs.last()
        assertEquals(listOf(LogKind.MODEL, LogKind.TOOL, LogKind.SCREEN), first.steps.map { it.kind })
        assertEquals(listOf("gemini-flash", "tap"), first.steps.take(2).map { it.label })
        assertEquals(940L, first.tokens)
        assertEquals(8_000L, first.durationMs)
        assertTrue(first.failed)
    }
}
