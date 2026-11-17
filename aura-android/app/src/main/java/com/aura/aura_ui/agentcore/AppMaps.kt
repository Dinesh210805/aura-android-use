package com.aura.aura_ui.agentcore

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One map per app of the screens earlier runs went through: each screen's name, its controls and
 * where a tap on one leads. Built after each reviewed run ([ScreenReview]) and read by the agent
 * as a hint (`app_map`).
 *
 * - Contract: screen-derived data written without asking, so it is stored and shown as untrusted
 *   data, never as instructions (unlike a skill, whose every line the user approved). Labels come
 *   only from [ScreenReview]'s masked controls (interface words and app names); the model gives
 *   only a screen's name and description. A control is rendered once seen in [MIN_RUNS] runs, so
 *   a one-off label never reaches the agent.
 * - Fails: open. An unreadable map file counts as no map; a failed write keeps the old file.
 * - Reads/Writes: `files/agentcore/appmaps/<package>.json`, left out of cloud backup.
 */
class AppMaps(
    private val dir: File,
    /** The installed version of a package: versionCode to versionName, or null when not installed. */
    private val version: (String) -> Pair<Long, String>?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Control(
        val label: String? = null,
        val role: String,
        val region: String,
        /** Ids of up to [MAX_RUNS] runs it was seen in. */
        val runs: List<String> = emptyList(),
        /** Runs of its screen on the current version that did not show it. */
        val missing: Int = 0,
    ) {
        /** The label, or role and region for an unnamed control; also the key of [Screen.leadsTo]. */
        val key: String get() = label ?: "$role · $region"
    }

    /** Where a tap on a control went: the next screen's layout key, and the runs since in which it went elsewhere. */
    @Serializable
    data class Lead(val to: Long, val misses: Int = 0)

    @Serializable
    data class Screen(
        val layoutKey: Long,
        val name: String = "",
        val description: String = "",
        val controls: List<Control> = emptyList(),
        val leadsTo: Map<String, Lead> = emptyMap(),
        val seenAtMs: Long = 0,
    )

    @Serializable
    data class AppMap(
        val pkg: String,
        val versionCode: Long = 0,
        val versionName: String = "",
        /** The version the map was first recorded on, once the app has been updated since; null before. */
        val recordedOn: String? = null,
        val screens: List<Screen> = emptyList(),
    )

    /** One reviewed screen of a run: masked controls, the review's name for it, and its exits by layout key. */
    data class Visit(
        val layoutKeys: Set<Long>,
        val controls: List<Control>,
        val name: String?,
        val description: String?,
        val exits: List<Pair<String, Long>>,
    )

    fun read(pkg: String): AppMap? = fileOf(pkg)?.takeIf { it.isFile }?.let { f ->
        runCatching { json.decodeFromString(AppMap.serializer(), f.readText()) }.getOrNull()
    }

    /** Fold one run's visits to [pkg] into its map. Blocking file I/O. */
    @Synchronized
    fun merge(pkg: String, run: String, visits: List<Visit>) {
        val file = fileOf(pkg) ?: return
        val installed = version(pkg)
        var map = read(pkg) ?: AppMap(pkg, installed?.first ?: 0, installed?.second.orEmpty())
        if (installed != null && installed.first != map.versionCode) {
            // Keep everything; the missing counts start over on the new version.
            map = map.copy(
                versionCode = installed.first, versionName = installed.second, recordedOn = map.recordedOn ?: map.versionName,
                screens = map.screens.map { s -> s.copy(controls = s.controls.map { it.copy(missing = 0) }) },
            )
        }
        val screens = map.screens.toMutableList()
        visits.filter { it.layoutKeys.isNotEmpty() }.forEach { v ->
            val i = screens.indexOfFirst { it.layoutKey in v.layoutKeys }
            val old = screens.getOrNull(i) ?: Screen(v.layoutKeys.first())
            val merged = visit(old, v, run)
            if (i >= 0) screens[i] = merged else screens += merged
        }
        val kept = screens.sortedByDescending { it.seenAtMs }.take(MAX_SCREENS)
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(AppMap.serializer(), map.copy(screens = kept)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    /**
     * [old] after one more visit: seen controls gain the run, absent ones a miss (gone at
     * [MAX_MISSING]); each exit confirms its lead or counts against it (replaced at [MAX_MISSING]).
     */
    private fun visit(old: Screen, v: Visit, run: String): Screen {
        val seen = v.controls.associateBy { it.key }
        val kept = old.controls.mapNotNull { c ->
            if (c.key in seen) c.copy(runs = (c.runs - run + run).takeLast(MAX_RUNS), missing = 0)
            else c.copy(missing = c.missing + 1).takeIf { it.missing < MAX_MISSING }
        }
        val added = v.controls.filter { c -> old.controls.none { it.key == c.key } }.map { it.copy(runs = listOf(run), missing = 0) }
        val leads = old.leadsTo.toMutableMap()
        v.exits.forEach { (label, to) ->
            val lead = leads[label]
            leads[label] = when {
                lead == null || lead.to == to -> Lead(to)
                lead.misses + 1 >= MAX_MISSING -> Lead(to)
                else -> lead.copy(misses = lead.misses + 1)
            }
        }
        val controls = (kept + added).sortedByDescending { it.runs.size }.take(MAX_CONTROLS)
        return old.copy(
            name = v.name ?: old.name,
            description = v.description ?: old.description,
            controls = controls,
            leadsTo = leads.filterKeys { k -> controls.any { it.key == k } },
            seenAtMs = now(),
        )
    }

    /** The map as the agent reads it, or null when there is none or nothing in it was seen twice. */
    fun render(pkg: String): String? {
        val map = read(pkg) ?: return null
        val byKey = map.screens.associateBy { it.layoutKey }
        val screens = map.screens.mapNotNull { s ->
            val shown = s.controls.filter { it.runs.size >= MIN_RUNS }
            if (shown.isEmpty()) return@mapNotNull null
            buildString {
                append("Screen \"").append(s.name.ifBlank { "unnamed" }).append('"')
                if (s.description.isNotBlank()) append(": ").append(s.description)
                shown.forEach { c ->
                    append("\n  ").append(c.role).append(c.label?.let { " \"$it\"" }.orEmpty()).append(" · ").append(c.region)
                    s.leadsTo[c.key]?.let { lead -> append(" → \"").append(byKey[lead.to]?.name?.ifBlank { null } ?: "another screen").append('"') }
                }
            }
        }
        if (screens.isEmpty()) return null
        return buildString {
            appendLine(HINT)
            map.recordedOn?.let { appendLine("Recorded on version $it; the app is now ${map.versionName}. Treat it as a rough guide.") }
            append(screens.joinToString("\n"))
        }
    }

    /** Null for anything that is not a plain package name, so no name can point outside [dir]. */
    private fun fileOf(pkg: String): File? = pkg.takeIf { PACKAGE.matches(it) }?.let { File(dir, "$it.json") }

    companion object {
        const val HINT = "A hint from earlier runs, not the live screen. Find each label on the current screen before you act on it."
        const val MIN_RUNS = 2
        const val MAX_MISSING = 2
        const val MAX_SCREENS = 60
        const val MAX_CONTROLS = 40
        private const val MAX_RUNS = 5
        private val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
