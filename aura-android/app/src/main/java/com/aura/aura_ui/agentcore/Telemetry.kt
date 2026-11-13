package com.aura.aura_ui.agentcore

import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryConfig
import ai.koog.agents.features.opentelemetry.integration.otlp.OtlpJsonSpanExporter
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.tracing.data.SpanData
import io.opentelemetry.kotlin.tracing.export.SpanExporter
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tracing for the agent, on OpenTelemetry: one trace per turn, with a span per model call and per
 * tool call, named and attributed by the OpenTelemetry GenAI conventions (Koog emits them).
 *
 * - Contract: spans always go to files on this device ([DeviceSpanExporter]), complete: every
 *   prompt, reply, tool argument and tool result is recorded, no attribute is shortened, and
 *   secrets are redacted. Spans leave the device only when the user exports them ([RunExport]) or
 *   sets a collector ([RemoteTraces]); a collector gets message text only with
 *   [RemoteTraces.includeContent]. Nothing is sent by default.
 * - Fails: a failed export is dropped. Tracing never fails or slows a turn.
 * - Reads/Writes: `files/agentcore/traces/spans-<date>.jsonl`; network host [RemoteTraces.endpoint]
 *   when set. The words and screenshots of a run are in [RunJournal], in the same folder.
 */
data class RemoteTraces(
    /** OTLP/HTTP traces endpoint, for example `https://collector.example/v1/traces`. */
    val endpoint: String,
    /** Auth headers for the collector. Held in memory only; store them in the secret store. */
    val headers: Map<String, String> = emptyMap(),
    /**
     * Send prompts, replies and tool arguments and results to the collector too. Off by default:
     * the remote exporter does not run [SecretPatterns.redact], so the user must choose this
     * explicitly. While a collector is set and this is off, local spans also lose that text,
     * because Koog's verbose switch covers every exporter.
     */
    val includeContent: Boolean = false,
)

class AgentTelemetry(
    private val tracesDir: File,
    private val appVersion: String,
    private val remote: () -> RemoteTraces? = { null },
    /** Days to keep logs, 0 for never; see [LogKeep]. */
    keepDays: () -> Long = { LogKeep.DEFAULT },
) {
    private val device = DeviceSpanExporter(tracesDir, keepDays)

    /** The readable step log written beside the spans. */
    val journal = RunJournal(tracesDir, keepDays)

    /**
     * The newest [limit] runs, newest first: the journal's steps with each model request from
     * the spans placed among them.
     * - Contract: blocking file I/O; call it off the main thread.
     */
    fun recent(limit: Int = 500): List<RunLog> = RunLogBuilder.build(journal.read(), modelCalls(), journal::file, limit)

    /** One run by [id], or null when it was deleted or pruned. Blocking file I/O. */
    fun run(id: String): RunLog? = RunLogBuilder.build(journal.read().filter { it.run == id }, modelCalls(), journal::file).firstOrNull()

    /** The system prompt the model had for [run], in full, from its first model span; null when not recorded. */
    fun systemPrompt(run: RunLog): String? = spanLines().firstNotNullOfOrNull { line ->
        runCatching {
            val span = Json.parseToJsonElement(line).jsonObject
            val start = span.getValue("start_ns").jsonPrimitive.long / NS_PER_MS
            if (start !in run.startMs..run.startMs + run.durationMs) return@runCatching null
            span["attributes"]?.jsonObject?.get("system_instructions")?.jsonPrimitive?.content
        }.getOrNull()
    }

    /** Deletes [ids]' steps, pictures and the spans recorded while they ran. */
    suspend fun delete(ids: Set<String>) = withContext(Dispatchers.IO) {
        device.deleteWithin(journal.delete(ids))
    }

    /**
     * Zips the raw log files into [outDir] and returns the zip: for [ids], only their step lines,
     * pictures and the spans recorded while they ran, plus a readable `run-<id>.md` each; for
     * null, the whole traces folder. Blocking file I/O.
     */
    fun export(ids: Set<String>?, outDir: File): File =
        RunExport.zip(tracesDir, journal, ids, outDir) { id -> run(id)?.let { runMarkdown(it, systemPrompt(it)) } }

    /** Applies the retention setting now rather than at the next run. */
    suspend fun prune() = withContext(Dispatchers.IO) {
        journal.prune()
        device.prune()
    }

    /**
     * Every model request still on disk.
     * ponytail: parses every span file per call; keep a per-run index if "never delete" logs get slow.
     */
    private fun modelCalls(): List<ModelCall> = runCatching {
        // Why the prefix check: span files are mostly prompt text, and parsing only model-call
        // lines keeps the list fast as logs grow.
        spanLines().filter { line -> MODEL_OPERATIONS.any { line.startsWith("{\"name\":\"$it ") } }.mapNotNull(::modelCall).toList()
    }.getOrDefault(emptyList())

    private fun spanLines(): Sequence<String> =
        tracesDir.listFiles().orEmpty().filter { it.name.startsWith("spans-") }.sortedBy { it.name }.asSequence().flatMap { it.readLines() }

    /** Span names follow the OpenTelemetry GenAI conventions: an operation, then the model it ran on. */
    private fun modelCall(line: String): ModelCall? = runCatching {
        val span = Json.parseToJsonElement(line).jsonObject
        val name = span.getValue("name").jsonPrimitive.content
        if (name.substringBefore(' ') !in MODEL_OPERATIONS) return null
        val start = span.getValue("start_ns").jsonPrimitive.long
        val attributes = span["attributes"]?.jsonObject.orEmpty()
        fun tokens(suffix: String) = attributes.entries.firstOrNull { it.key.endsWith(suffix) }?.value?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        ModelCall(
            atMs = start / NS_PER_MS,
            durationMs = (span.getValue("end_ns").jsonPrimitive.long - start) / NS_PER_MS,
            model = name.substringAfter(' ', name),
            ok = !span.getValue("status").jsonPrimitive.content.contains("error", ignoreCase = true),
            inputTokens = tokens("input_tokens"),
            outputTokens = tokens("output_tokens"),
            finish = attributes["gen_ai.response.finish_reasons"]?.jsonPrimitive?.content.orEmpty().trim('[', ']'),
            output = attributes["gen_ai.output.messages"]?.jsonPrimitive?.content.orEmpty(),
            input = attributes["gen_ai.input.messages"]?.jsonPrimitive?.content.orEmpty(),
            metadata = attributes["gen_ai.response.metadata"]?.jsonPrimitive?.content.orEmpty(),
        )
    }.getOrNull()

    /** Call inside `install(OpenTelemetry) { ... }` when building the agent for a turn. */
    fun configure(config: OpenTelemetryConfig) {
        config.setServiceInfo(SERVICE_NAME, appVersion)
        config.addSpanExporter(device)
        val collector = remote()?.takeIf { it.endpoint.startsWith("https://") }
        collector?.let { config.addSpanExporter(OtlpJsonSpanExporter(it.endpoint, it.headers)) }
        // Full prompts, replies, tool arguments and results: a log without the prompt cannot
        // explain a decision. Off only when content would also reach a collector unredacted
        // and the user has not opted in (RemoteTraces.includeContent).
        config.setVerbose(collector == null || collector.includeContent)
    }

    private companion object {
        const val SERVICE_NAME = "aura-agentcore"
        const val NS_PER_MS = 1_000_000L
        val MODEL_OPERATIONS = setOf("chat", "generate_content", "text_completion")
    }
}

/**
 * Writes finished spans as JSON lines, one file per day, and deletes files older than
 * [keepDays] days (0 keeps them).
 *
 * - Contract: every string attribute passes through [SecretPatterns.redact] before it is written,
 *   and none is shortened. Safe to share across turns: [shutdown] flushes nothing and closes nothing.
 */
class DeviceSpanExporter(
    private val dir: File,
    private val keepDays: () -> Long = { LogKeep.DEFAULT },
    private val today: () -> LocalDate = LocalDate::now,
) : SpanExporter {

    private val lock = Mutex()

    override suspend fun export(telemetry: List<SpanData>): OperationResultCode = withContext(Dispatchers.IO) {
        runCatching {
            lock.withLock {
                dir.mkdirs()
                File(dir, "spans-${today()}.jsonl").appendText(telemetry.joinToString("") { line(it).toString() + "\n" })
                pruneLocked()
            }
        }.fold({ OperationResultCode.Success }, { OperationResultCode.Failure })
    }

    override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success

    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success

    private fun line(span: SpanData): JsonObject = buildJsonObject {
        put("name", span.name)
        put("trace_id", span.spanContext.traceId)
        put("span_id", span.spanContext.spanId)
        put("parent_span_id", span.parent.spanId)
        put("start_ns", span.startTimestamp)
        put("end_ns", span.endTimestamp)
        put("status", span.status.statusCode.toString())
        put(
            "attributes",
            JsonObject(
                span.attributes.mapValues { (_, value) ->
                    when (value) {
                        is Number -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        else -> JsonPrimitive(SecretPatterns.redact(value.toString()))
                    }
                },
            ),
        )
    }

    suspend fun prune() = lock.withLock { runCatching { pruneLocked() } }

    /**
     * Drops every span that started inside one of [windows] (epoch ms): a deleted run's model
     * calls hold its prompts and replies, so deleting the run must delete them too.
     */
    suspend fun deleteWithin(windows: List<LongRange>) {
        if (windows.isEmpty()) return
        lock.withLock {
            runCatching {
                dir.listFiles { file -> file.name.startsWith("spans-") }.orEmpty().forEach { file ->
                    val kept = file.readLines().filterNot { line ->
                        val startMs = runCatching { Json.parseToJsonElement(line).jsonObject.getValue("start_ns").jsonPrimitive.long / 1_000_000 }.getOrNull()
                        startMs != null && windows.any { startMs in it }
                    }
                    if (kept.isEmpty()) file.delete() else file.writeText(kept.joinToString("") { "$it\n" })
                }
            }
        }
    }

    private fun pruneLocked() {
        val days = keepDays()
        if (days <= 0) return
        val oldest = "spans-${today().minusDays(days)}.jsonl"
        dir.listFiles { file -> file.name.startsWith("spans-") && file.name < oldest }?.forEach { it.delete() }
    }
}

/**
 * Packs raw log files into one zip the user can share, so a run can be read off the phone exactly
 * as it was recorded.
 * - Contract: the zip holds the files as stored (already redacted), never reformatted. A selected
 *   run brings its `steps-*.jsonl` lines, its pictures, the `spans-*.jsonl` lines that started
 *   while it ran, and `run-<id>.md` from [markdown]. Older exports in [outDir] are deleted first.
 * - Reads/Writes: the traces folder (read); `[outDir]/aura-log-<ms>.zip` (written).
 */
object RunExport {
    fun zip(dir: File, journal: RunJournal, ids: Set<String>?, outDir: File, markdown: (String) -> String?): File {
        outDir.mkdirs()
        outDir.listFiles { file -> file.name.startsWith("aura-log-") }?.forEach { it.delete() }
        val out = File(outDir, "aura-log-${System.currentTimeMillis()}.zip")
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
            if (ids == null) {
                files.forEach { put(it.name, it.readBytes()) }
                return@use
            }
            val records = journal.read().filter { it.run in ids }
            val windows = records.groupBy { it.run }.values.map { group ->
                group.minOf { it.atMs }..group.maxOf { it.atMs + it.durationMs }
            }
            files.filter { it.name.startsWith("steps-") }.forEach { file ->
                val lines = file.readLines().filter { line -> ids.any { id -> line.contains("\"run\":\"$id\"") } }
                if (lines.isNotEmpty()) put(file.name, lines.joinToString("") { "$it\n" }.toByteArray())
            }
            files.filter { it.name.startsWith("spans-") }.forEach { file ->
                val lines = file.readLines().filter { line -> startMs(line)?.let { at -> windows.any { at in it } } == true }
                if (lines.isNotEmpty()) put(file.name, lines.joinToString("") { "$it\n" }.toByteArray())
            }
            records.mapNotNull { it.image }.distinct().map { File(dir, it) }.filter { it.isFile }.forEach { put(it.name, it.readBytes()) }
            ids.forEach { id -> markdown(id)?.let { put("run-$id.md", it.toByteArray()) } }
        }
        return out
    }

    private fun startMs(line: String): Long? =
        runCatching { Json.parseToJsonElement(line).jsonObject.getValue("start_ns").jsonPrimitive.long / 1_000_000 }.getOrNull()
}
