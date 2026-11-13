package com.aura.aura_ui.agentcore

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers how the journal and the span files on disk become the runs the log screen shows. */
class RunLogTest {
    private val dir: File = Files.createTempDirectory("traces").toFile()
    private var clock = 1_000L

    private fun span(name: String, startMs: Long, endMs: Long, attributes: String = "{}") =
        """{"name":"$name","trace_id":"t","span_id":"s","parent_span_id":"p",""" +
            """"start_ns":${startMs * 1_000_000},"end_ns":${endMs * 1_000_000},"status":"Unset","attributes":$attributes}"""

    @Test
    fun `a run reads as its request, its steps in plain words, and its model calls in order`() {
        val telemetry = AgentTelemetry(dir, "test")
        val journal = RunJournal(dir, now = { clock })
        journal.start("r1", "send hi to mom", "Gemini / flash")
        clock = 2_600
        journal.tool("r1", "tap", """{"som_id":4}""", "ok", ok = false, durationMs = 500, shot = StepShot(listOf(0.1f, 0.2f, 0.3f, 0.4f), "Send") { byteArrayOf(1) })
        clock = 9_000
        journal.end("r1", TurnEnd.ANSWERED, "Sent.")
        clock = 20_000
        journal.start("r2", "what time is it", "Gemini / flash")
        File(dir, "spans-2026-03-10.jsonl").writeText(
            listOf(
                span("invoke_agent aura", 1_000, 9_000),
                span("chat gemini-flash", 1_100, 2_000, """{"gen_ai.usage.input_tokens":900,"gen_ai.usage.output_tokens":40}"""),
            ).joinToString("\n"),
        )

        val runs = telemetry.recent()

        assertEquals(listOf("what time is it", "send hi to mom"), runs.map { it.request })
        assertNull(runs.first().end)
        val run = runs.last()
        assertEquals(TurnEnd.ANSWERED, run.end)
        assertEquals(listOf(LogKind.MODEL, LogKind.ACTION), run.steps.map { it.kind })
        assertEquals("Tapped “Send”", run.steps.last().title)
        assertTrue(run.steps.last().image!!.exists())
        assertEquals(940L, run.tokens)
        assertEquals(1, run.failedSteps)
        assertEquals(8_000L, run.durationMs)
    }

    @Test
    fun `secrets are redacted before a step is written`() {
        RunJournal(dir).tool("r", "type_text", """{"text":"card 4111 1111 1111 1111"}""", "typed 4111 1111 1111 1111", true, 0, null)
        val written = File(dir, dir.list()!!.first { it.startsWith("steps-") }).readText()
        assertTrue(written.contains("[redacted]"))
        assertTrue(!written.contains("4111 1111"))
    }

    @Test
    fun `tool calls are phrased the way a person would say them`() {
        assertEquals("Typed “hello” into “Message”", StepPhrase.of("type_text", """{"text":"hello","som_id":2}""", "Message"))
        assertEquals("Opened “WhatsApp”", StepPhrase.of("launch_app", """{"app_name":"WhatsApp"}""", null))
        assertEquals("Github: create issue", StepPhrase.of("github__create_issue", "{}", null))
        assertEquals("Tapped the screen", StepPhrase.of("tap", "not json", null))
    }

    @Test
    fun `deleting a run removes its records, its pictures and the spans recorded while it ran`() {
        val telemetry = AgentTelemetry(dir, "test")
        val journal = RunJournal(dir, now = { clock })
        journal.start("gone", "delete me", "m")
        clock = 1_500
        journal.tool("gone", "tap", "{}", "ok", true, 100, StepShot(null, "OK") { byteArrayOf(1) })
        clock = 3_000
        journal.end("gone", TurnEnd.ANSWERED, null)
        clock = 50_000
        journal.start("kept", "keep me", "m")
        File(dir, "spans-2026-03-10.jsonl").writeText(listOf(span("chat a", 1_200, 1_300), span("chat b", 50_100, 50_200)).joinToString("\n"))
        val picture = dir.list()!!.first { it.startsWith("shot-") }

        kotlinx.coroutines.runBlocking { telemetry.delete(setOf("gone")) }

        assertEquals(listOf("keep me"), telemetry.recent().map { it.request })
        assertTrue(!File(dir, picture).exists())
        val spans = File(dir, "spans-2026-03-10.jsonl").readText()
        assertTrue(!spans.contains("chat a") && spans.contains("chat b"))
    }

    @Test
    fun `with never delete, old day files survive a prune`() {
        val old = File(dir, "steps-2000-01-01.jsonl").apply { writeText("") }
        RunJournal(dir, keepDays = { 0 }).prune()
        assertTrue(old.exists())
        RunJournal(dir, keepDays = { 7 }).prune()
        assertTrue(!old.exists())
    }

    @Test
    fun `nothing is shortened in the journal or the spans, and the model's input is shown`() {
        val long = "x".repeat(50_000)
        val journal = RunJournal(dir, now = { clock })
        journal.start("r", "read the screen", "m")
        journal.tool("r", "read_screen", "{}", long, true, 10, null)
        journal.note("r", "Model's thinking", long)
        val input = """[{"role":"user","content":"$long"}]"""
        File(dir, "spans-2026-03-10.jsonl").writeText(
            span("chat m", 1_000, 1_001, kotlinx.serialization.json.buildJsonObject { put("gen_ai.input.messages", kotlinx.serialization.json.JsonPrimitive(input)) }.toString()),
        )

        val run = AgentTelemetry(dir, "test").recent().single()

        assertEquals(long, run.steps.first { it.kind == LogKind.LOOK }.details.first { it.first == "got back" }.second)
        assertEquals(long, run.steps.first { it.kind == LogKind.NOTE }.details.single().second)
        assertEquals(input, run.steps.first { it.kind == LogKind.MODEL }.details.first { it.first == "model input" }.second)
    }

    @Test
    fun `a handed-off chat turn stays in the log`() {
        val journal = RunJournal(dir, now = { clock })
        journal.start("chat", "send hi", "m")
        journal.note("chat", "Handed the task to the overlay, which runs it as a new run")
        journal.end("chat", TurnEnd.HANDED_OFF, null)

        val run = AgentTelemetry(dir, "test").recent().single()

        assertEquals(TurnEnd.HANDED_OFF, run.end)
        assertEquals(LogKind.NOTE, run.steps.single().kind)
    }

    @Test
    fun `exporting a run zips only its records, pictures and spans, plus a readable summary`() {
        val telemetry = AgentTelemetry(dir, "test")
        val journal = RunJournal(dir, now = { clock })
        journal.start("one", "first", "m")
        clock = 1_500
        journal.tool("one", "tap", "{}", "ok", true, 100, StepShot(null, "OK") { byteArrayOf(1) })
        clock = 50_000
        journal.start("two", "second", "m")
        File(dir, "spans-2026-03-10.jsonl").writeText(listOf(span("chat a", 1_200, 1_300), span("chat b", 50_100, 50_200)).joinToString("\n"))
        val out = Files.createTempDirectory("export").toFile()

        val zip = telemetry.export(setOf("one"), out)

        val entries = java.util.zip.ZipFile(zip).use { file ->
            file.entries().toList().associate { it.name to file.getInputStream(it).readBytes().decodeToString() }
        }
        assertTrue(entries.keys.any { it.startsWith("shot-") })
        assertTrue(entries.getValue("run-one.md").contains("# AURA run one"))
        val steps = entries.filterKeys { it.startsWith("steps-") }.values.joinToString("")
        assertTrue(steps.contains("first") && !steps.contains("second"))
        val spans = entries.getValue("spans-2026-03-10.jsonl")
        assertTrue(spans.contains("chat a") && !spans.contains("chat b"))
        assertTrue(telemetry.export(null, out).let { java.util.zip.ZipFile(it).use { file -> file.size() } } >= 3)
    }

    @Test
    fun `a model call reads as the step it chose`() {
        val call = """[{"role":"Assistant","parts":[{"type":"tool_call","id":"c","name":"launch_app","arguments":{"app_name":"Maps"}}]}]"""
        assertEquals("Decided: Opened “Maps”", RunLogBuilder.decision(call))
        assertEquals("Wrote: “Done.”", RunLogBuilder.decision("""[{"role":"Assistant","parts":[{"type":"text","content":"Done."}]}]"""))
        assertEquals("Thought about the next step", RunLogBuilder.decision(""))
    }
}
