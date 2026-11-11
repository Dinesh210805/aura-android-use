package com.aura.aura_ui.agentcore

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Scheduled tasks: prompts AURA runs on its own, later or repeatedly ("every morning at 8, tell me
 * what is on my calendar").
 *
 * - Contract: a scheduled run is a [RunMode.BACKGROUND] turn in its own thread, so it can read
 *   but never tap, send or change anything. Its answer becomes a notification, unless the answer
 *   is [SILENT]. Creating a schedule always asks the user.
 * - Fails: a run that fails is recorded on the job and tried again at its next slot. The worker
 *   runs about every 15 minutes, so a job fires up to that late.
 * - Reads/Writes: table `jobs` in `agentcore.db`.
 */
@Entity(tableName = "jobs")
data class JobRow(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    val prompt: String,
    /** Repeat interval, or null for a daily job. */
    val everyMinutes: Int? = null,
    /** Minute of the day (0 to 1439) in the device's zone, or null for an interval job. */
    val dailyAtMinute: Int? = null,
    val nextRunMs: Long,
    val lastResult: String? = null,
)

object Schedule {
    /** WorkManager cannot wake the app more often than this. */
    const val MIN_EVERY_MINUTES = 15
    const val MAX_JOBS = 20
    const val SILENT = "[SILENT]"

    /** "08:30" to 510, or null when it is not a time. */
    fun parseDailyAt(text: String): Int? {
        val (h, m) = Regex("""(\d{1,2}):(\d{2})""").matchEntire(text.trim())?.destructured ?: return null
        return (h.toInt() * 60 + m.toInt()).takeIf { h.toInt() in 0..23 && m.toInt() in 0..59 }
    }

    /** The first run strictly after [afterMs]. Exactly one of the two schedule fields is set. */
    fun next(afterMs: Long, everyMinutes: Int?, dailyAtMinute: Int?, zone: ZoneId): Long {
        if (dailyAtMinute == null) return afterMs + (everyMinutes ?: MIN_EVERY_MINUTES).coerceAtLeast(MIN_EVERY_MINUTES) * 60_000L
        val after = Instant.ofEpochMilli(afterMs).atZone(zone)
        val today = after.toLocalDate().atStartOfDay(zone).plusMinutes(dailyAtMinute.toLong())
        return (if (today.isAfter(after)) today else today.plusDays(1)).toInstant().toEpochMilli()
    }
}

class JobStore(
    private val dao: AgentDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun list(): List<JobRow> = dao.jobs()

    /** Returns the job, or null when there are already [Schedule.MAX_JOBS]. */
    suspend fun add(prompt: String, everyMinutes: Int?, dailyAtMinute: Int?): JobRow? {
        if (dao.jobs().size >= Schedule.MAX_JOBS) return null
        val job = JobRow(
            prompt = prompt,
            everyMinutes = everyMinutes,
            dailyAtMinute = dailyAtMinute,
            nextRunMs = Schedule.next(now(), everyMinutes, dailyAtMinute, zone()),
        )
        return job.copy(rowid = dao.insertJob(job))
    }

    suspend fun cancel(id: Long): Boolean = dao.deleteJob(id) > 0

    suspend fun due(): List<JobRow> = dao.jobs().filter { it.nextRunMs <= now() }

    suspend fun finished(job: JobRow, result: String?) =
        dao.updateJob(job.copy(nextRunMs = Schedule.next(now(), job.everyMinutes, job.dailyAtMinute, zone()), lastResult = result))
}

/** Runs the jobs that are due. Call from the periodic worker. */
class JobRunner(
    private val engine: AgentEngine,
    private val threads: ThreadStore,
    private val jobs: JobStore,
    /** Show the result to the user: (title, text, threadId). */
    private val notify: (String, String, String) -> Unit,
) {
    suspend fun runDue() {
        jobs.due().forEach { job ->
            val thread = threads.create(job.prompt.take(TITLE_CHARS))
            val answer = runCatching {
                engine.runTurn(thread.id, PREAMBLE + job.prompt, mode = RunMode.BACKGROUND).toList()
                    .filterIsInstance<AgentEvent.TurnEnded>().lastOrNull()?.message
            }.getOrNull()
            jobs.finished(job, answer)
            if (answer.isNullOrBlank() || Schedule.SILENT in answer) threads.delete(thread.id) else notify(thread.title, answer, thread.id)
        }
    }

    private companion object {
        const val TITLE_CHARS = 40
        const val PREAMBLE =
            "This is a task the user scheduled. Nobody is watching, so you can read but not change anything. " +
                "If there is nothing worth telling them, reply with exactly ${Schedule.SILENT}.\n\nTask: "
    }
}

/** The schedule tools: `schedule_task`, `list_schedules`, `cancel_schedule`. */
fun scheduleTools(jobs: JobStore): List<NativeTool> {
    fun spec(descriptor: ToolDescriptor, risk: ToolRisk) =
        ToolSpec(descriptor, risk, ToolOrigin.NATIVE, ToolHub.CORE, resultOrigin = null)

    fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun describe(job: JobRow): String {
        val whenText = job.dailyAtMinute?.let { "daily at %02d:%02d".format(it / 60, it % 60) } ?: "every ${job.everyMinutes} minutes"
        return "[${job.rowid}] $whenText: ${job.prompt}"
    }

    val schedule = NativeTool(
        spec(
            ToolDescriptor(
                name = "schedule_task",
                description = "Run a prompt on your own, daily or at an interval, and notify the user with the answer. " +
                    "Scheduled runs can read (notifications, calendar, web, connected services) but cannot change anything. " +
                    "Give exactly one of daily_at or every_minutes.",
                requiredParameters = listOf(
                    ToolParameterDescriptor("prompt", "What to do each time, written as a complete instruction.", ToolParameterType.String),
                ),
                optionalParameters = listOf(
                    ToolParameterDescriptor("daily_at", "Local time as HH:mm, for a daily task.", ToolParameterType.String),
                    ToolParameterDescriptor(
                        "every_minutes",
                        "Interval in minutes, at least ${Schedule.MIN_EVERY_MINUTES}.",
                        ToolParameterType.Integer,
                    ),
                ),
            ),
            // Asks the user: a schedule keeps spending their model quota after this conversation ends.
            ToolRisk.COMMIT,
        ),
    ) { args, _ ->
        val prompt = args.string("prompt")
        val dailyAt = args.string("daily_at").takeIf { it.isNotBlank() }?.let(Schedule::parseDailyAt)
        val every = args["every_minutes"]?.jsonPrimitive?.longOrNull?.toInt()
        when {
            prompt.isBlank() -> SourceResult("A prompt is required.", isError = true)
            (dailyAt == null) == (every == null) -> SourceResult("Give exactly one of daily_at (HH:mm) or every_minutes.", isError = true)
            every != null && every < Schedule.MIN_EVERY_MINUTES ->
                SourceResult("The shortest interval is ${Schedule.MIN_EVERY_MINUTES} minutes.", isError = true)
            else -> jobs.add(prompt, every, dailyAt)?.let { SourceResult("Scheduled: ${describe(it)}") }
                ?: SourceResult("There are already ${Schedule.MAX_JOBS} scheduled tasks. Cancel one first.", isError = true)
        }
    }

    val list = NativeTool(
        spec(
            ToolDescriptor(
                name = "list_schedules",
                description = "List the scheduled tasks.",
                requiredParameters = emptyList(),
                optionalParameters = emptyList(),
            ),
            ToolRisk.READ,
        ),
    ) { _, _ ->
        SourceResult(jobs.list().joinToString("\n", transform = ::describe).ifBlank { "Nothing is scheduled." })
    }

    val cancel = NativeTool(
        spec(
            ToolDescriptor(
                name = "cancel_schedule",
                description = "Cancel a scheduled task.",
                requiredParameters = listOf(
                    ToolParameterDescriptor("id", "The number in brackets from list_schedules.", ToolParameterType.Integer),
                ),
                optionalParameters = emptyList(),
            ),
            ToolRisk.ACT,
        ),
    ) { args, _ ->
        val id = args["id"]?.jsonPrimitive?.longOrNull
        if (id != null && jobs.cancel(id)) SourceResult("Cancelled.") else SourceResult("No scheduled task with that id.", isError = true)
    }

    return listOf(schedule, list, cancel)
}
