package com.aura.aura_ui.eval

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/**
 * On-disk home for eval suite runs, kept entirely separate from `mcp_logs`.
 *
 * ### Why a separate root and not another folder under mcp_logs
 *
 * `mcp_logs` is an append-only firehose that the app never prunes: every agent run and every MCP
 * session the device has ever served, 90+ of them by 2026-08. Eval results living in there would
 * inherit that problem — a suite run would be something you had to *filter for* rather than
 * something you could point at. Here a suite run is one directory you can copy, delete, or export
 * whole. Traces stay where they are and are referenced by id.
 *
 * ### Layout
 *
 * ```
 * filesDir/eval_runs/
 *   2026-08-25-1430-baseline/
 *     manifest.json            EvalSuiteRun — build, model, timings
 *     in-app/
 *       task-001.json          EvalTaskResult
 *       task-005.json
 *     browser/
 *       task-012.json
 * ```
 *
 * Category subfolders are the user-visible reason this class exists, but they also make the shape
 * of a run legible on disk: `ls browser/ | wc -l` answers "did the browser plane actually get
 * tested?" without parsing anything.
 *
 * ### Durability
 *
 * Every mutation writes the whole task file immediately. A suite sitting is an hour of a human's
 * attention that cannot be recreated cheaply, and the failure this class exists to survive — the
 * provider cutting us off mid-run — is exactly the one where a buffered write would be lost. Writes
 * go via a temp file and rename so a kill mid-write cannot leave a half-parsed result.
 */
class EvalSuiteStore(context: Context) {

    private val appContext = context.applicationContext
    private val rootDir: File = File(appContext.filesDir, ROOT_DIR_NAME)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ── suite runs ───────────────────────────────────────────────────────────

    /** Every suite run on disk, newest first. Unreadable manifests are skipped, never fatal. */
    fun listRuns(): List<EvalSuiteRun> =
        rootDir.listFiles { f: File -> f.isDirectory }
            ?.mapNotNull { readManifest(it.name) }
            ?.sortedByDescending { it.startedAtMillis }
            ?: emptyList()

    fun readManifest(runId: String): EvalSuiteRun? {
        val file = File(runDir(runId), MANIFEST_NAME)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<EvalSuiteRun>(file.readText()) }
            .onFailure { Log.w(TAG, "unreadable manifest for $runId: ${it.message}") }
            .getOrNull()
    }

    /**
     * Create a run directory seeded with one PENDING result per task, so the full suite is visible
     * in the UI before anything has been attempted and progress is a real fraction from the start.
     */
    fun createRun(run: EvalSuiteRun, tasks: List<EvalTask>): EvalSuiteRun {
        runDir(run.id).mkdirs()
        val seeded = run.copy(taskCount = tasks.size)
        writeManifest(seeded)
        tasks.forEach { task ->
            writeResult(
                run.id,
                EvalTaskResult(
                    number = task.number,
                    goal = task.goal,
                    category = task.category,
                    truthCheck = task.truthCheck,
                    status = EvalStatus.PENDING,
                ),
            )
        }
        return seeded
    }

    fun writeManifest(run: EvalSuiteRun) {
        runDir(run.id).mkdirs()
        atomicWrite(File(runDir(run.id), MANIFEST_NAME), json.encodeToString(run))
    }

    fun deleteRun(runId: String): Boolean = runDir(runId).deleteRecursively()

    // ── task results ─────────────────────────────────────────────────────────

    /**
     * Every result in a run, ordered by category (suite order) then task number — the same order
     * the screen renders, so the UI never has to re-sort and the two can never disagree.
     */
    fun readResults(runId: String): List<EvalTaskResult> {
        val dir = runDir(runId)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f: File -> f.isDirectory }
            .orEmpty()
            .flatMap { categoryDir ->
                categoryDir.listFiles { f: File -> f.isFile && f.name.endsWith(".json") }
                    .orEmpty()
                    .mapNotNull { readResultFile(it) }
            }
            .sortedWith(compareBy({ EvalCategory.orderOf(it.category) }, { it.number }))
    }

    fun readResult(runId: String, number: Int): EvalTaskResult? =
        readResults(runId).firstOrNull { it.number == number }

    fun writeResult(runId: String, result: EvalTaskResult) {
        val dir = File(runDir(runId), safeSegment(result.category))
        dir.mkdirs()
        atomicWrite(File(dir, resultFileName(result.number)), json.encodeToString(result))
    }

    /**
     * Read-modify-write one result. Returns the updated value, or null when the file is missing —
     * which happens if a task was added to the catalog after this run was created. Silently doing
     * nothing is correct there: an old run describes the suite as it was.
     */
    fun updateResult(runId: String, number: Int, transform: (EvalTaskResult) -> EvalTaskResult): EvalTaskResult? {
        val current = readResult(runId, number) ?: return null
        val updated = transform(current)
        // The category is part of the path, so a transform that changed it would orphan the old
        // file. Nothing should do that, but silently leaving two copies would be worse than loud.
        if (updated.category != current.category) {
            File(File(runDir(runId), safeSegment(current.category)), resultFileName(number)).delete()
        }
        writeResult(runId, updated)
        return updated
    }

    // ── export ───────────────────────────────────────────────────────────────

    /**
     * Flatten a whole run into one JSON document for pulling off the device.
     *
     * Deliberately one file: the point of an export is to hand a single artifact to the aggregator
     * (`scripts/agent_eval.py`) or to a human on another machine, and a directory tree pulled over
     * adb loses its shape often enough to be worth avoiding.
     */
    fun exportRun(runId: String): File? {
        val manifest = readManifest(runId) ?: return null
        val results = readResults(runId)
        val payload = json.encodeToString(
            EvalExport.serializer(),
            EvalExport(run = manifest, tally = EvalTally.of(results).toWire(), results = results),
        )
        val out = File(runDir(runId), EXPORT_NAME)
        atomicWrite(out, payload)
        return out
    }

    /**
     * Every run on disk, flattened into one file.
     *
     * The 116-task AndroidWorld suite is what made this necessary. Exporting one run at a time and
     * pulling each `export.json` by hand is fine for a 30-task sitting you do once a fortnight; it
     * is not fine when a benchmark is re-run per build and someone has to compare six of them. One
     * file, one `adb pull`, and `agent_eval.py` gets every sitting at once.
     *
     * Traces are still referenced by `session_id` rather than copied — a suite's worth of
     * screenshots is hundreds of megabytes, and the point of an export is something you can move.
     */
    fun exportAll(): File? {
        val runs = listRuns()
        if (runs.isEmpty()) return null
        val payload = json.encodeToString(
            EvalExportBundle.serializer(),
            EvalExportBundle(
                exportedAtMillis = System.currentTimeMillis(),
                exports = runs.mapNotNull { run ->
                    val results = readResults(run.id)
                    EvalExport(run = run, tally = EvalTally.of(results).toWire(), results = results)
                },
            ),
        )
        rootDir.mkdirs()
        val out = File(rootDir, EXPORT_ALL_NAME)
        atomicWrite(out, payload)
        return out
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun runDir(runId: String) = File(rootDir, safeSegment(runId))

    private fun readResultFile(file: File): EvalTaskResult? =
        runCatching { json.decodeFromString<EvalTaskResult>(file.readText()) }
            .onFailure { Log.w(TAG, "unreadable result ${file.name}: ${it.message}") }
            .getOrNull()

    private fun resultFileName(number: Int) = "task-%03d.json".format(Locale.US, number)

    /**
     * Write via temp + rename so a process death mid-write cannot leave a truncated file that
     * fails to parse. The results this protects are a human hour that cannot be re-earned.
     */
    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // Rename can fail on some vendor filesystems when the target exists; fall back rather
            // than losing the write.
            target.writeText(content)
            tmp.delete()
        }
    }

    /** Keep ids and categories from escaping their directory or carrying separators. */
    private fun safeSegment(raw: String): String =
        raw.trim().ifEmpty { "unknown" }.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '-'
        }.joinToString("").removePrefix(".")

    companion object {
        private const val TAG = "EvalSuiteStore"
        const val ROOT_DIR_NAME = "eval_runs"
        private const val MANIFEST_NAME = "manifest.json"
        private const val EXPORT_NAME = "export.json"
        private const val EXPORT_ALL_NAME = "export-all.json"

        /** Human-sortable run id: sorting the directory listing sorts by time. */
        fun newRunId(nowMillis: Long, label: String): String {
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)
                .format(java.util.Date(nowMillis))
            val slug = label.trim().lowercase(Locale.US)
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .trim('-')
                .take(24)
            return if (slug.isEmpty()) stamp else "$stamp-$slug"
        }
    }
}

/** The shape [EvalSuiteStore.exportRun] writes. Field names match `scripts/agent_eval.py`. */
@kotlinx.serialization.Serializable
data class EvalExport(
    val run: EvalSuiteRun,
    val tally: Map<String, String>,
    val results: List<EvalTaskResult>,
)

/** What [EvalSuiteStore.exportAll] writes: every sitting on the device, newest first. */
@kotlinx.serialization.Serializable
data class EvalExportBundle(
    @kotlinx.serialization.SerialName("exported_at_millis") val exportedAtMillis: Long,
    val exports: List<EvalExport>,
)

/**
 * Flatten a tally for export. Stringly-typed on purpose: `verified_success_rate` is legitimately
 * absent (not zero) until something has been judged, and a JSON number cannot say "not yet"
 * without inviting a reader to treat null as 0.0 — the exact confusion that let an unscored run
 * masquerade as a measurement before.
 */
fun EvalTally.toWire(): Map<String, String> = mapOf(
    "total" to total.toString(),
    "scored" to scored.toString(),
    "judged" to judged.toString(),
    "passed" to passed.toString(),
    "failed" to failed.toString(),
    "refused" to refused.toString(),
    "refused_off_category" to refusedOffCategory.toString(),
    "invalid" to invalid.toString(),
    "errored" to errored.toString(),
    "rate_limited" to rateLimited.toString(),
    "verified_success_rate" to (verifiedSuccessRate?.let { "%.3f".format(Locale.US, it) } ?: "unscored"),
)
