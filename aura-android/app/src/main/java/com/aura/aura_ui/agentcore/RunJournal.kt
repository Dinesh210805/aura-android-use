package com.aura.aura_ui.agentcore

import android.content.Context
import java.io.File
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The readable half of the run log: what was asked, each thing AURA did, what it pressed, and how
 * the run ended. The OpenTelemetry spans in `Telemetry.kt` hold the timings and token counts;
 * this file holds the words and pictures a span cannot.
 *
 * - Contract: every string is passed through [SecretPatterns.redact] before it is written, and
 *   nothing is shortened: the log holds exactly what the model and the tools saw. Writing never
 *   throws and never fails a turn. Files stay on this phone unless the user exports them
 *   ([RunExport]).
 * - Reads/Writes: `files/agentcore/traces/steps-<date>.jsonl` (one [StepRecord] per line) and
 *   the images it names: `shot-<ms>.jpg` (the screen before a press) and `screen-<ms>.png` (the
 *   annotated screen sent to the model). All are deleted after [keepDays] days; 0 keeps them.
 * - Change together: `RunLogUi.kt` draws [RunLog]; `RunLogTest`.
 */
@Serializable
data class StepRecord(
    /** The turn this belongs to: [TurnState.runId]. */
    val run: String,
    val atMs: Long,
    /** One of the `TYPE_` constants in [RunJournal]. */
    val type: String,
    val tool: String? = null,
    val args: String? = null,
    val result: String? = null,
    val ok: Boolean = true,
    val durationMs: Long = 0,
    /** The request (start), the model's words (say), the question (ask), the answer (end) or a note's body. */
    val text: String? = null,
    /** Start: the model's label. End: the [TurnEnd] name. Note: its one-line title. */
    val detail: String? = null,
    /** Visible label of the element a gesture pressed. */
    val label: String? = null,
    /** That element on [image], as left, top, right, bottom fractions of the picture. */
    val box: List<Float>? = null,
    /** File name of a picture in the traces folder. */
    val image: String? = null,
)

/**
 * What a gesture pressed: the element's label and, when the screen could be captured, a picture
 * taken just before the press with the element's place on it.
 * - Contract: [jpeg] encodes the picture when called, after the gesture has run, so the press is
 *   not held up by image work. It returns null when there is no picture.
 */
class StepShot(val box: List<Float>?, val label: String?, val jpeg: () -> ByteArray? = { null })

class RunJournal(
    private val dir: File,
    /** Read on every prune, so a change in [LogKeep] applies at once. 0 means never delete. */
    private val keepDays: () -> Long = { LogKeep.DEFAULT },
    private val today: () -> LocalDate = LocalDate::now,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun start(run: String, request: String, model: String) {
        prune()
        write(StepRecord(run, now(), TYPE_START, text = request, detail = model))
    }

    fun say(run: String, words: String) = write(StepRecord(run, now(), TYPE_SAY, text = words))

    fun ask(run: String, question: String) = write(StepRecord(run, now(), TYPE_ASK, text = question))

    fun end(run: String, end: TurnEnd, answer: String?) =
        write(StepRecord(run, now(), TYPE_END, text = answer, detail = end.name, ok = end != TurnEnd.FAILED))

    /**
     * Something the harness did or decided that is not a tool call: a safety-gate verdict, the
     * model's thinking, a compaction, a nudge, steering, a pause, a raw error. [title] is one line;
     * [body] is the full text. [ok] false marks a refusal or an error.
     */
    fun note(run: String, title: String, body: String = "", ok: Boolean = true) =
        write(StepRecord(run, now(), TYPE_NOTE, text = body, detail = title, ok = ok))

    /** The annotated screen that is being sent to the model. */
    fun screen(run: String, png: ByteArray) {
        val name = save("screen", "png", png) ?: return
        write(StepRecord(run, now(), TYPE_SCREEN, image = name))
    }

    fun tool(run: String, tool: String, args: String, result: String, ok: Boolean, durationMs: Long, shot: StepShot?) {
        val at = now() - durationMs
        write(
            StepRecord(
                run = run,
                atMs = at,
                type = TYPE_TOOL,
                tool = tool,
                args = args,
                result = result,
                ok = ok,
                durationMs = durationMs,
                label = shot?.label,
                box = shot?.box,
                image = shot?.let { runCatching(it.jpeg).getOrNull() }?.let { save("shot", "jpg", it) },
            ),
        )
    }

    /**
     * Every record still on disk, oldest first. Blocking file I/O.
     * ponytail: reads every day file each time; index by run if "never delete" logs grow past a few MB.
     */
    fun read(): List<StepRecord> = runCatching {
        stepFiles().sortedBy { it.name }.flatMap { it.readLines() }.mapNotNull(::decode)
    }.getOrDefault(emptyList())

    /**
     * Removes every record of [runs] and the pictures they name.
     * - Contract: returns each removed run's time window, first record to last step's end, so the
     *   caller can drop the spans recorded in it. Blocking file I/O; never throws.
     */
    @Synchronized
    fun delete(runs: Set<String>): List<LongRange> {
        val windows = mutableMapOf<String, LongRange>()
        runCatching {
            stepFiles().forEach { file ->
                val (gone, kept) = file.readLines().partition { line -> decode(line)?.run?.let { it in runs } == true }
                if (gone.isEmpty()) return@forEach
                gone.mapNotNull(::decode).forEach { record ->
                    record.image?.let { File(dir, it).delete() }
                    val end = record.atMs + record.durationMs
                    val window = windows[record.run]
                    windows[record.run] = if (window == null) record.atMs..end else minOf(window.first, record.atMs)..maxOf(window.last, end)
                }
                if (kept.isEmpty()) file.delete() else file.writeText(kept.joinToString("") { "$it\n" })
            }
        }
        return windows.values.toList()
    }

    private fun stepFiles(): List<File> = dir.listFiles().orEmpty().filter { it.name.startsWith("steps-") }

    private fun decode(line: String): StepRecord? = runCatching { json.decodeFromString(StepRecord.serializer(), line) }.getOrNull()

    fun file(name: String): File = File(dir, name)

    @Synchronized
    private fun write(record: StepRecord) {
        runCatching {
            dir.mkdirs()
            val safe = record.copy(
                args = record.args?.let(SecretPatterns::redact),
                result = record.result?.let(SecretPatterns::redact),
                text = record.text?.let(SecretPatterns::redact),
                detail = record.detail?.let(SecretPatterns::redact),
            )
            File(dir, "steps-${today()}.jsonl").appendText(json.encodeToString(StepRecord.serializer(), safe) + "\n")
        }
    }

    private fun save(prefix: String, extension: String, bytes: ByteArray): String? = runCatching {
        dir.mkdirs()
        "$prefix-${now()}.$extension".also { File(dir, it).writeBytes(bytes) }
    }.getOrNull()

    /** Deletes day files and pictures older than [keepDays]. Runs at each run's start and when the setting changes. */
    @Synchronized
    fun prune() {
        val days = keepDays()
        if (days <= 0) return
        runCatching {
            val oldest = "steps-${today().minusDays(days)}.jsonl"
            val cutoff = now() - days * MS_PER_DAY
            dir.listFiles().orEmpty().forEach { file ->
                val old = when {
                    file.name.startsWith("steps-") -> file.name < oldest
                    file.name.startsWith("shot-") || file.name.startsWith("screen-") -> file.lastModified() < cutoff
                    else -> false
                }
                if (old) file.delete()
            }
        }
    }

    companion object {
        const val TYPE_START = "start"
        const val TYPE_TOOL = "tool"
        const val TYPE_SAY = "say"
        const val TYPE_ASK = "ask"
        const val TYPE_SCREEN = "screen"
        const val TYPE_END = "end"
        const val TYPE_NOTE = "note"
        private const val MS_PER_DAY = 86_400_000L
    }
}

enum class LogKind {
    /** AURA changed something: a tap, typing, opening an app. */
    ACTION,

    /** AURA read something: the screen, notifications, the web. */
    LOOK,

    /** What the model wrote between steps. */
    SAY,

    /** A question put to the user. */
    ASK,

    /** The annotated screen sent to the model. */
    SCREEN,

    /** One request to the model, from the OpenTelemetry span. */
    MODEL,

    /** What the harness did or decided: a gate verdict, thinking, a compaction, a nudge, an error. */
    NOTE,
}

/** One line of a run, ready to draw. */
data class LogStep(
    val atMs: Long,
    val kind: LogKind,
    /** A plain sentence: "Tapped Send", "Opened WhatsApp". */
    val title: String,
    val ok: Boolean = true,
    val durationMs: Long = 0,
    val image: File? = null,
    val box: List<Float>? = null,
    /** Raw values for the "details" fold: tool name, arguments, result, token counts. */
    val details: List<Pair<String, String>> = emptyList(),
)

/** One turn in the log, oldest step first. */
data class RunLog(
    val id: String,
    val startMs: Long,
    val durationMs: Long,
    val request: String,
    val model: String,
    /** Null while the run is still going, or when the app was killed before it ended. */
    val end: TurnEnd?,
    val answer: String?,
    val steps: List<LogStep>,
) {
    val actions: Int get() = steps.count { it.kind == LogKind.ACTION }
    val modelCalls: Int get() = steps.count { it.kind == LogKind.MODEL }
    val failedSteps: Int get() = steps.count { !it.ok }
    val tokens: Long get() = steps.sumOf { step -> step.details.filter { it.first.endsWith("tokens") }.sumOf { it.second.toLongOrNull() ?: 0 } }
}

/**
 * One model request read from a span.
 * - Contract: [input] is the raw `gen_ai.input.messages` JSON (every message the model was sent,
 *   in full), [output] the raw `gen_ai.output.messages` JSON (what it answered or which tools it
 *   called); each is empty when not recorded. [metadata] is `gen_ai.response.metadata`, which
 *   carries cached-token counts on providers that report them.
 */
data class ModelCall(
    val atMs: Long,
    val durationMs: Long,
    val model: String,
    val ok: Boolean,
    val inputTokens: Long,
    val outputTokens: Long,
    val finish: String = "",
    val output: String = "",
    val input: String = "",
    val metadata: String = "",
)

/**
 * How long run logs stay on this phone: the steps, the pictures and the spans.
 * - Contract: [days] 0 means never delete.
 * - Reads/Writes: prefs `agentcore_logs`, key `keep_days`.
 */
object LogKeep {
    const val DEFAULT = 7L
    val CHOICES = listOf(1L, 7L, 30L, 90L, 0L)

    fun days(context: Context): Long = prefs(context).getLong(KEY, DEFAULT)

    fun set(context: Context, days: Long) = prefs(context).edit().putLong(KEY, days).apply()

    fun label(days: Long): String = when (days) {
        0L -> "Never delete"
        1L -> "1 day"
        else -> "$days days"
    }

    private const val KEY = "keep_days"
    private fun prefs(context: Context) = context.getSharedPreferences("agentcore_logs", Context.MODE_PRIVATE)
}

object RunLogBuilder {

    /**
     * Group journal records into runs, newest first, and place each model call in the run whose
     * time span contains it.
     * - Contract: pure. A run with no start record (its day file was pruned) is left out. A chat
     *   turn that handed its request to the overlay is kept, so what it did before the hand-off
     *   (a deep link, a question) is visible; the overlay's run follows it as its own run.
     */
    fun build(records: List<StepRecord>, calls: List<ModelCall>, image: (String) -> File, limit: Int = 30): List<RunLog> =
        records.groupBy { it.run }.values.mapNotNull { group ->
            val start = group.firstOrNull { it.type == RunJournal.TYPE_START } ?: return@mapNotNull null
            val end = group.lastOrNull { it.type == RunJournal.TYPE_END }
            val last = group.maxOf { it.atMs + it.durationMs }
            val model = calls.filter { it.atMs in start.atMs..last }.map {
                LogStep(
                    atMs = it.atMs,
                    kind = LogKind.MODEL,
                    title = decision(it.output),
                    ok = it.ok,
                    durationMs = it.durationMs,
                    details = listOfNotNull(
                        "model" to it.model,
                        "input tokens" to it.inputTokens.toString(),
                        "output tokens" to it.outputTokens.toString(),
                        it.finish.takeIf(String::isNotBlank)?.let { finish -> "finish" to finish },
                        it.metadata.takeIf(String::isNotBlank)?.let { meta -> "response metadata" to meta },
                        it.output.takeIf(String::isNotBlank)?.let { output -> "model output" to output },
                        it.input.takeIf(String::isNotBlank)?.let { input -> "model input" to input },
                    ),
                )
            }
            val steps = group.mapNotNull { step(it, image) }
            RunLog(
                id = start.run,
                startMs = start.atMs,
                durationMs = last - start.atMs,
                request = start.text.orEmpty(),
                model = start.detail.orEmpty(),
                end = end?.detail?.let { name -> TurnEnd.entries.firstOrNull { it.name == name } },
                answer = end?.text,
                steps = (steps + model).sortedBy { it.atMs },
            )
        }.sortedByDescending { it.startMs }.take(limit)

    /**
     * The model's choice in plain words: the tools it called, phrased like the steps, or the start
     * of what it wrote. Pure; falls back to a neutral line when the output was not recorded.
     */
    fun decision(output: String): String {
        val parts = runCatching {
            (Json.parseToJsonElement(output) as JsonArray).flatMap { (it.jsonObject["parts"] as? JsonArray).orEmpty() }.map { it.jsonObject }
        }.getOrNull().orEmpty()
        val calls = parts.filter { it["type"]?.jsonPrimitive?.contentOrNull == "tool_call" }.map { part ->
            StepPhrase.of(part["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), part["arguments"]?.toString().orEmpty(), null)
        }
        if (calls.isNotEmpty()) return "Decided: " + calls.joinToString("; ")
        val text = parts.firstNotNullOfOrNull { part -> part["content"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }
        return text?.let { "Wrote: " + StepPhrase.quoted(it) } ?: "Thought about the next step"
    }

    private fun step(record: StepRecord, image: (String) -> File): LogStep? = when (record.type) {
        RunJournal.TYPE_TOOL -> {
            val tool = record.tool.orEmpty()
            LogStep(
                atMs = record.atMs,
                kind = if (tool in StepPhrase.LOOKS) LogKind.LOOK else LogKind.ACTION,
                title = StepPhrase.of(tool, record.args.orEmpty(), record.label),
                ok = record.ok,
                durationMs = record.durationMs,
                image = record.image?.let(image),
                box = record.box,
                details = listOfNotNull(
                    "tool" to tool,
                    record.args?.takeIf { it.length > 2 }?.let { "sent" to it },
                    record.result?.takeIf { it.isNotBlank() }?.let { (if (record.ok) "got back" else "error") to it },
                ),
            )
        }
        RunJournal.TYPE_SAY -> LogStep(record.atMs, LogKind.SAY, record.text.orEmpty())
        RunJournal.TYPE_ASK -> LogStep(record.atMs, LogKind.ASK, "Asked you: ${record.text.orEmpty()}")
        RunJournal.TYPE_SCREEN -> LogStep(record.atMs, LogKind.SCREEN, "Looked at the screen", image = record.image?.let(image))
        RunJournal.TYPE_NOTE -> LogStep(
            atMs = record.atMs,
            kind = LogKind.NOTE,
            title = record.detail.orEmpty(),
            ok = record.ok,
            details = listOfNotNull(record.text?.takeIf(String::isNotBlank)?.let { (if (record.ok) "detail" else "error") to it }),
        )
        else -> null
    }
}

/**
 * Turns a tool call into the sentence a person would use for it.
 * - Contract: pure, never throws; an unknown tool reads as its name with spaces.
 */
object StepPhrase {
    /** Tools that only read. They are drawn quieter than actions. */
    val LOOKS = setOf(
        "read_screen", "perceive_screen", "get_screenshot", "read_notifications", "get_device_status", "get_media_sessions",
        "web_search", "find_files", "resolve_contact", "lookup_app", "list_app_deeplinks", "resolve_deeplink", "wait_for",
        "verify_action", "watch_device_events", "browser_read", "browser_find", "browser_extract", "browser_tabs",
        "browser_screenshot", "search_history", "skill_view", "list_schedules", "load_toolset", "update_plan",
    )

    private const val MAX_QUOTE = 60

    fun of(tool: String, argsJson: String, label: String?): String {
        val args = runCatching { Json.parseToJsonElement(argsJson).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        fun arg(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { args[it]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }?.let(::quote)
        val target = label?.takeIf { it.isNotBlank() }?.let(::quote)
        return when (tool) {
            "tap" -> target?.let { "Tapped $it" } ?: "Tapped the screen"
            "long_press" -> target?.let { "Pressed and held $it" } ?: "Pressed and held"
            "double_tap" -> target?.let { "Double-tapped $it" } ?: "Double-tapped"
            "type_text", "set_value" -> listOfNotNull("Typed", arg("text", "value"), target?.let { "into $it" }).joinToString(" ")
            "press_enter" -> "Pressed Enter"
            "press_key" -> "Pressed ${arg("key") ?: "a key"}"
            "scroll", "swipe" -> "Scrolled ${args["direction"]?.jsonPrimitive?.contentOrNull ?: "the screen"}"
            "launch_app" -> "Opened ${arg("app_name", "name", "package") ?: "an app"}"
            "open_deeplink" -> "Opened a direct link ${arg("uri", "url").orEmpty()}".trim()
            "system_intent" -> "Ran a phone action ${arg("action", "intent", "kind").orEmpty()}".trim()
            "open_file" -> "Opened a file"
            "media_control" -> "Media: ${args["action"]?.jsonPrimitive?.contentOrNull ?: "control"}"
            "notification_action" -> "Used a notification action"
            "dismiss_notification" -> "Dismissed a notification"
            "clipboard" -> "Used the clipboard"
            "volume" -> "Changed the volume"
            "read_screen" -> "Read the screen"
            "perceive_screen", "get_screenshot" -> "Looked at the screen"
            "read_notifications" -> "Read your notifications"
            "get_device_status" -> "Checked the phone's status"
            "web_search" -> "Searched the web for ${arg("query") ?: "something"}"
            "find_files" -> "Looked for files ${arg("query").orEmpty()}".trim()
            "resolve_contact" -> "Looked up ${arg("name") ?: "a contact"}"
            "lookup_app" -> "Looked up ${arg("name", "app_name") ?: "an app"}"
            "list_app_deeplinks", "resolve_deeplink" -> "Checked for a direct link"
            "wait_for" -> "Waited for the screen"
            "verify_action" -> "Checked the result"
            "load_toolset" -> "Got ${args["name"]?.jsonPrimitive?.contentOrNull ?: "more"} tools ready"
            "update_plan" -> "Updated the plan"
            "ask_user" -> "Asked you a question"
            MEMORY_TOOL -> "Changed memory"
            "search_history" -> "Searched past chats"
            "skill_view" -> "Read the skill ${arg("name").orEmpty()}".trim()
            "skill_save" -> "Saved a skill"
            "schedule_task" -> "Scheduled a task"
            "browser_open" -> "Opened a page ${arg("url").orEmpty()}".trim()
            "browser_read" -> "Read the page"
            "browser_act" -> "Acted on the page"
            else -> tool.replace("__", ": ").replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    /** [text] on one line, in quotes, cut to a readable length. */
    fun quoted(text: String): String = quote(text)

    private fun quote(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return "“" + (if (flat.length > MAX_QUOTE) flat.take(MAX_QUOTE) + "…" else flat) + "”"
    }
}
