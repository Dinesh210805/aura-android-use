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
 * - Contract: spans always go to files on this device ([DeviceSpanExporter]). They leave the
 *   device only when the user sets a collector ([RemoteTraces]); nothing is sent by default.
 *   Message text is not in spans: Koog masks it unless verbose is set, and this file never sets
 *   it. The words of a conversation live in `ThreadStore`, the timings and token counts live here.
 * - Fails: a failed export is dropped. Tracing never fails or slows a turn.
 * - Reads/Writes: `files/agentcore/traces/spans-<date>.jsonl`; network host [RemoteTraces.endpoint]
 *   when set.
 */
data class RemoteTraces(
    /** OTLP/HTTP traces endpoint, for example `https://collector.example/v1/traces`. */
    val endpoint: String,
    /** Auth headers for the collector. Held in memory only; store them in the secret store. */
    val headers: Map<String, String> = emptyMap(),
)

enum class LogKind { MODEL, TOOL, SCREEN, RUN, INTERNAL }

/** One step of a run: a finished span, or a screenshot that was sent to the model. */
data class LogEntry(
    val atMs: Long,
    val kind: LogKind,
    /** The tool's or the model's name, ready to show. */
    val label: String,
    val durationMs: Long,
    val ok: Boolean,
    val details: Map<String, String> = emptyMap(),
    val image: File? = null,
    val traceId: String = "",
) {
    val inputTokens: Long get() = tokens("input_tokens")
    val outputTokens: Long get() = tokens("output_tokens")

    private fun tokens(suffix: String): Long = details.entries.firstOrNull { it.key.endsWith(suffix) }?.value?.toLongOrNull() ?: 0
}

/** One run in the log: the model calls, tool calls and screenshots of one turn, oldest first. */
data class RunLog(val startMs: Long, val durationMs: Long, val steps: List<LogEntry>) {
    val modelCalls: Int get() = steps.count { it.kind == LogKind.MODEL }
    val toolCalls: Int get() = steps.count { it.kind == LogKind.TOOL }
    val tokens: Long get() = steps.sumOf { it.inputTokens + it.outputTokens }
    val failed: Boolean get() = steps.any { !it.ok }
}

class AgentTelemetry(
    private val tracesDir: File,
    private val appVersion: String,
    private val remote: () -> RemoteTraces? = { null },
) {
    private val device = DeviceSpanExporter(tracesDir)

    /**
     * Keep a screenshot that is being sent to the model, so the run log can show what it saw.
     * - Reads/Writes: `screen-<epoch ms>.png` beside the span files; pruned with them.
     * - Fails: silently. A log must never fail a turn.
     */
    fun saveScreen(png: ByteArray) {
        runCatching {
            tracesDir.mkdirs()
            File(tracesDir, "screen-${System.currentTimeMillis()}.png").writeBytes(png)
        }
    }

    /**
     * The newest [limit] runs, newest first, read from the two most recent day files. Each run is
     * one trace: its model calls and tool calls in order, with the screenshots sent in that time.
     * - Contract: blocking file I/O; call it off the main thread. Spans that only describe the
     *   loop's own structure ([LogKind.INTERNAL]) are left out.
     */
    fun recent(limit: Int = 30): List<RunLog> = runCatching {
        val files = tracesDir.listFiles().orEmpty()
        val screens = files.filter { it.name.startsWith("screen-") }.mapNotNull { file ->
            file.name.removePrefix("screen-").removeSuffix(".png").toLongOrNull()
                ?.let { LogEntry(it, LogKind.SCREEN, "Screenshot sent to the model", 0, true, image = file) }
        }
        files.filter { it.name.startsWith("spans-") }.sortedByDescending { it.name }.take(2)
            .flatMap { it.readLines() }.mapNotNull(::entry)
            .groupBy { it.traceId }.values
            .map { spans ->
                val start = spans.minOf { it.atMs }
                val end = spans.maxOf { it.atMs + it.durationMs }
                val shown = spans.filter { it.kind == LogKind.MODEL || it.kind == LogKind.TOOL }
                RunLog(start, end - start, (shown + screens.filter { it.atMs in start..end }).sortedBy { it.atMs })
            }
            .filter { it.steps.isNotEmpty() }
            .sortedByDescending { it.startMs }
            .take(limit)
    }.getOrDefault(emptyList())

    private fun entry(line: String): LogEntry? = runCatching {
        val span = Json.parseToJsonElement(line).jsonObject
        val start = span.getValue("start_ns").jsonPrimitive.long
        val name = span.getValue("name").jsonPrimitive.content
        val kind = kindOf(name)
        LogEntry(
            atMs = start / NS_PER_MS,
            kind = kind,
            label = name.substringAfter(' ', name),
            durationMs = (span.getValue("end_ns").jsonPrimitive.long - start) / NS_PER_MS,
            ok = !span.getValue("status").jsonPrimitive.content.contains("error", ignoreCase = true),
            details = span["attributes"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content },
            traceId = span.getValue("trace_id").jsonPrimitive.content,
        )
    }.getOrNull()

    /** Span names follow the OpenTelemetry GenAI conventions: an operation, then what it ran on. */
    private fun kindOf(spanName: String): LogKind = when (spanName.substringBefore(' ')) {
        "execute_tool" -> LogKind.TOOL
        "chat", "generate_content", "text_completion" -> LogKind.MODEL
        "invoke_agent" -> LogKind.RUN
        else -> LogKind.INTERNAL
    }

    /** Call inside `install(OpenTelemetry) { ... }` when building the agent for a turn. */
    fun configure(config: OpenTelemetryConfig) {
        config.setServiceInfo(SERVICE_NAME, appVersion)
        config.addSpanExporter(device)
        val collector = remote()?.takeIf { it.endpoint.startsWith("https://") }
        collector?.let { config.addSpanExporter(OtlpJsonSpanExporter(it.endpoint, it.headers)) }
        // Prompts, tool arguments and results are recorded only while traces stay on this phone.
        config.setVerbose(collector == null)
    }

    private companion object {
        const val SERVICE_NAME = "aura-agentcore"
        const val NS_PER_MS = 1_000_000L
    }
}

/**
 * Writes finished spans as JSON lines, one file per day, and deletes files older than
 * [keepDays].
 *
 * - Contract: every string attribute passes through [SecretPatterns.redact] before it is written.
 *   Safe to share across turns: [shutdown] flushes nothing and closes nothing.
 */
class DeviceSpanExporter(
    private val dir: File,
    private val keepDays: Long = 7,
    private val today: () -> LocalDate = LocalDate::now,
) : SpanExporter {

    private val lock = Mutex()

    override suspend fun export(telemetry: List<SpanData>): OperationResultCode = withContext(Dispatchers.IO) {
        runCatching {
            lock.withLock {
                dir.mkdirs()
                File(dir, "spans-${today()}.jsonl").appendText(telemetry.joinToString("") { line(it).toString() + "\n" })
                prune()
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
                        else -> JsonPrimitive(SecretPatterns.redact(value.toString()).take(MAX_ATTRIBUTE_CHARS))
                    }
                },
            ),
        )
    }

    private fun prune() {
        val oldest = "spans-${today().minusDays(keepDays)}.jsonl"
        dir.listFiles { file -> file.name.startsWith("spans-") && file.name < oldest }?.forEach { it.delete() }
        val cutoff = System.currentTimeMillis() - keepDays * MS_PER_DAY
        dir.listFiles { file -> file.name.startsWith("screen-") && file.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private companion object {
        const val MAX_ATTRIBUTE_CHARS = 2_000
        const val MS_PER_DAY = 86_400_000L
    }
}
