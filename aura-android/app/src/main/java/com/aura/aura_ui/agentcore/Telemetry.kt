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

/** One line of the run log: a finished span, or a screenshot that was sent to the model. */
data class LogEntry(
    val atMs: Long,
    val name: String,
    val durationMs: Long,
    val ok: Boolean,
    val details: Map<String, String> = emptyMap(),
    val image: File? = null,
)

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
     * The newest [limit] log entries, newest first: spans from the two most recent day files,
     * with the screenshots taken in that time placed among them.
     * - Contract: blocking file I/O; call it off the main thread.
     */
    fun recent(limit: Int = 300): List<LogEntry> = runCatching {
        val files = tracesDir.listFiles().orEmpty()
        val spans = files.filter { it.name.startsWith("spans-") }.sortedByDescending { it.name }.take(2)
            .flatMap { it.readLines() }.mapNotNull(::entry)
        val oldest = spans.minOfOrNull { it.atMs } ?: Long.MAX_VALUE
        val screens = files.filter { it.name.startsWith("screen-") }.mapNotNull { file ->
            file.name.removePrefix("screen-").removeSuffix(".png").toLongOrNull()
                ?.takeIf { it >= oldest }?.let { LogEntry(it, "Screenshot sent to the model", 0, true, image = file) }
        }
        (spans + screens).sortedByDescending { it.atMs }.take(limit)
    }.getOrDefault(emptyList())

    private fun entry(line: String): LogEntry? = runCatching {
        val span = Json.parseToJsonElement(line).jsonObject
        val start = span.getValue("start_ns").jsonPrimitive.long
        LogEntry(
            atMs = start / NS_PER_MS,
            name = span.getValue("name").jsonPrimitive.content,
            durationMs = (span.getValue("end_ns").jsonPrimitive.long - start) / NS_PER_MS,
            ok = !span.getValue("status").jsonPrimitive.content.contains("error", ignoreCase = true),
            details = span["attributes"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content },
        )
    }.getOrNull()

    /** Call inside `install(OpenTelemetry) { ... }` when building the agent for a turn. */
    fun configure(config: OpenTelemetryConfig) {
        config.setServiceInfo(SERVICE_NAME, appVersion)
        config.addSpanExporter(device)
        remote()?.takeIf { it.endpoint.startsWith("https://") }?.let {
            config.addSpanExporter(OtlpJsonSpanExporter(it.endpoint, it.headers))
        }
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
