package com.aura.aura_ui.agentcore

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Skills: procedures AURA saves once and reads again when a task matches ("how to book a cab in
 * this city's app").
 *
 * - Contract: the system prompt lists each skill's name and one-line description; the body is
 *   read on demand with `skill_view`. Every save is confirmed by the user, which is why a skill's
 *   body is returned unfenced: it is the one model-written text allowed to instruct.
 * - Reads/Writes: `files/agentcore/skills/<name>/SKILL.md`, the agentskills.io layout (YAML
 *   frontmatter with `name` and `description`, then markdown), so skills can be copied in or out.
 *   Optional frontmatter: `app:` (the skill is hidden while that app is not installed) and
 *   `steps:` (one line of JSON, a [Step] list `do_steps` can run). Usage and curator state live
 *   in `skills/usage.json`; archived skills move to `skills-archive/`.
 * - Fails: an unreadable or malformed skill file is skipped, never an error.
 */
data class Skill(
    val name: String,
    val description: String,
    val app: String? = null,
    val steps: List<Step> = emptyList(),
    val usage: SkillUsage = SkillUsage(),
)

/** What the curator knows about one skill. */
@Serializable
data class SkillUsage(
    /** Last `skill_view` or `do_steps` with this skill; 0 until first used, then the save time counts. */
    val lastUsedMs: Long = 0,
    /** `do_steps` runs of this skill's steps that stopped early, in a row. */
    val failures: Int = 0,
    val pinned: Boolean = false,
) {
    /** Two chains in a row stopped early: the app changed and the steps need learning again. */
    val needsRelearning: Boolean get() = failures >= SkillPolicy.FAILURES_TO_RELEARN
}

object SkillPolicy {
    private val NAME = Regex("[a-z0-9][a-z0-9-]{1,39}")
    const val MAX_DESCRIPTION = 200
    const val MAX_BODY = 8_000
    const val MAX_SKILLS = 40
    const val FAILURES_TO_RELEARN = 2
    const val STALE_DAYS = 30L
    const val ARCHIVE_DAYS = 60L

    /** Why this skill may not be saved, or null when it may. */
    fun rejection(name: String, description: String, body: String): String? = when {
        !NAME.matches(name) -> "The name must be 2 to 40 characters: lower-case letters, digits and hyphens."
        description.isBlank() || description.length > MAX_DESCRIPTION || '\n' in description ->
            "The description must be one line of at most $MAX_DESCRIPTION characters saying when to use the skill."
        body.isBlank() || body.length > MAX_BODY -> "The body must be 1 to $MAX_BODY characters."
        SecretPatterns.containsSecret(body) -> "A skill must not contain passwords, codes, keys or card numbers."
        else -> null
    }
}

class SkillStore(
    private val dir: File,
    /** True when an app by this name is installed. */
    private val installed: (app: String) -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val usageFile = File(dir, USAGE)
    private val json = Json { ignoreUnknownKeys = true }
    private val usageSerializer = MapSerializer(String.serializer(), SkillUsage.serializer())

    /** Every saved skill, including ones whose app is not installed (the skills screen shows them all). */
    fun list(): List<Skill> {
        val usage = usage()
        return dir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.mapNotNull { folder ->
            val text = runCatching { File(folder, FILE).readText() }.getOrNull() ?: return@mapNotNull null
            val description = frontmatter(text, "description") ?: return@mapNotNull null
            Skill(folder.name, description, frontmatter(text, "app"), Step.decode(frontmatter(text, "steps")), usage[folder.name] ?: SkillUsage())
        }
    }

    /** The lines for the system prompt, or blank when there are none: skills whose app is installed, with their state. */
    fun index(): String = list().filter { s -> s.app == null || installed(s.app) }.joinToString("\n") { s ->
        val flag = when {
            s.usage.needsRelearning -> " [its steps stopped working; do the task by hand, then save the skill again]"
            s.steps.isNotEmpty() -> " [has steps for do_steps]"
            else -> ""
        }
        "${s.name}: ${s.description}$flag"
    }

    /** The body, then the `steps:` line when there is one, for `skill_view`. */
    fun read(name: String): String? {
        val text = runCatching { File(folder(name), FILE).readText() }.getOrNull() ?: return null
        val body = text.substringAfter("\n---\n", "").trim().takeIf { it.isNotEmpty() } ?: return null
        return frontmatter(text, "steps")?.let { "$body\n\nsteps: $it" } ?: body
    }

    /** Returns why the skill was refused, or null when it was saved. Saving resets its failure count. */
    fun save(name: String, description: String, body: String, app: String? = null, steps: List<Step> = emptyList()): String? {
        SkillPolicy.rejection(name, description, body)?.let { return it }
        if (app != null && ('\n' in app || app.length > SkillPolicy.MAX_DESCRIPTION)) return "The app must be its name, on one line."
        val folder = File(dir, name)
        if (!folder.exists() && list().size >= SkillPolicy.MAX_SKILLS) return "There are already ${SkillPolicy.MAX_SKILLS} skills. Delete one first."
        folder.mkdirs()
        val extra = buildString {
            app?.takeIf { it.isNotBlank() }?.let { append("app: ${it.trim()}\n") }
            if (steps.isNotEmpty()) append("steps: ${Step.encode(steps)}\n")
        }
        File(folder, FILE).writeText("---\nname: $name\ndescription: ${description.trim()}\n$extra---\n${body.trim()}\n")
        updateUsage(name) { it.copy(lastUsedMs = now(), failures = 0) }
        return null
    }

    fun delete(name: String): Boolean = folder(name).deleteRecursively().also { updateUsage(name) { null } }

    /** `skill_view` read this skill. */
    fun noteUsed(name: String) = updateUsage(name) { it.copy(lastUsedMs = now()) }

    /** A `do_steps` chain from this skill ran to the end ([ok]) or stopped early. */
    fun noteChain(name: String, ok: Boolean) =
        updateUsage(name) { if (ok) it.copy(lastUsedMs = now(), failures = 0) else it.copy(failures = it.failures + 1) }

    fun setPinned(name: String, pinned: Boolean) = updateUsage(name) { it.copy(pinned = pinned) }

    /**
     * The curator, deterministic and model-free: an unpinned skill unused for [SkillPolicy.ARCHIVE_DAYS]
     * moves to `skills-archive/`, out of the prompt. Returns the names archived.
     * - Why only archive, never delete: the user can move a folder back by hand.
     */
    fun curate(): List<String> {
        val day = 24 * 60 * 60 * 1000L
        val archive = File(dir.parentFile, ARCHIVE)
        return list().filter { s ->
            val last = s.usage.lastUsedMs.takeIf { it > 0 } ?: File(folder(s.name), FILE).lastModified()
            !s.usage.pinned && now() - last > SkillPolicy.ARCHIVE_DAYS * day
        }.mapNotNull { s ->
            archive.mkdirs()
            val to = File(archive, s.name).also { it.deleteRecursively() }
            if (folder(s.name).renameTo(to)) s.name.also { updateUsage(it) { null } } else null
        }
    }

    /** True when an unpinned skill has gone [SkillPolicy.STALE_DAYS] unused; the skills screen marks it. */
    fun isStale(skill: Skill): Boolean {
        val last = skill.usage.lastUsedMs.takeIf { it > 0 } ?: File(folder(skill.name), FILE).lastModified()
        return !skill.usage.pinned && now() - last > SkillPolicy.STALE_DAYS * 24 * 60 * 60 * 1000L
    }

    private fun folder(name: String) = File(dir, name.filter { it.isLetterOrDigit() || it == '-' })

    private fun usage(): Map<String, SkillUsage> =
        runCatching { json.decodeFromString(usageSerializer, usageFile.readText()) }.getOrDefault(emptyMap())

    @Synchronized
    private fun updateUsage(name: String, change: (SkillUsage) -> SkillUsage?) {
        val all = usage()
        val next = change(all[name] ?: SkillUsage())
        val updated = if (next == null) all - name else all + (name to next)
        runCatching { dir.mkdirs(); usageFile.writeText(json.encodeToString(usageSerializer, updated)) }
    }

    /**
     * Install the skills that ship in the APK, once each.
     * - Contract: [bundled] maps a skill name to its markdown file (YAML frontmatter with
     *   `description` and optional `whenToUse`, then the body). A skill is installed only the first
     *   time its name is seen, so one the user deleted or edited is never put back.
     * - Fails: a file that cannot be read or does not pass [SkillPolicy] is skipped.
     */
    fun seed(bundled: Map<String, () -> String>) {
        val seen = File(dir, SEEDED)
        val done = runCatching { seen.readLines().toSet() }.getOrDefault(emptySet())
        val added = bundled.filterKeys { it !in done }.mapNotNull { (name, read) ->
            val text = runCatching(read).getOrNull() ?: return@mapNotNull null
            val description = listOfNotNull(frontmatter(text, "description"), frontmatter(text, "whenToUse")?.let { "Use $it" })
                .joinToString(". ").take(SkillPolicy.MAX_DESCRIPTION)
            val body = text.substringAfter("\n---\n", "").trim()
            if (File(dir, name).exists() || save(name, description, body) == null) name else null
        }
        if (added.isNotEmpty()) runCatching { dir.mkdirs(); seen.appendText(added.joinToString("\n", postfix = "\n")) }
    }

    private fun frontmatter(text: String, key: String): String? =
        text.substringAfter("---\n", "").substringBefore("\n---").lineSequence()
            .firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val FILE = "SKILL.md"

        /** Names of bundled skills already installed once. A file, not a folder, so [list] skips it. */
        const val SEEDED = ".seeded"
        const val USAGE = "usage.json"
        const val ARCHIVE = "skills-archive"
    }
}

/** The skill tools: `skill_view`, `skill_save`, `skill_delete`. [confirm] asks the user. */
fun skillTools(skills: SkillStore, confirm: suspend (TurnState, String) -> Boolean): List<NativeTool> {
    fun spec(descriptor: ToolDescriptor, risk: ToolRisk) =
        ToolSpec(descriptor, risk, ToolOrigin.NATIVE, ToolHub.CORE, resultOrigin = null)

    fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun param(name: String, description: String) = ToolParameterDescriptor(name, description, ToolParameterType.String)

    val view = NativeTool(
        spec(
            ToolDescriptor(
                name = "skill_view",
                description = "Read a saved skill listed under <skills>.",
                requiredParameters = listOf(param("name", "The skill's name, exactly as listed.")),
                optionalParameters = emptyList(),
            ),
            ToolRisk.READ,
        ),
    ) { args, _ ->
        val name = args.string("name")
        skills.read(name)?.let { skills.noteUsed(name); SourceResult(it) } ?: SourceResult("No skill with that name.", isError = true)
    }

    val save = NativeTool(
        spec(
            ToolDescriptor(
                name = "skill_save",
                description = "Save a procedure that worked, so you can repeat it. Write the steps as a lesson that " +
                    "holds next time, not a log of this run. Saving under an existing name replaces it.",
                requiredParameters = listOf(
                    param("name", "Lower-case words joined by hyphens."),
                    param("description", "One line: when to use this skill."),
                    param("body", "The steps, in markdown."),
                ),
                optionalParameters = emptyList(),
            ),
            ToolRisk.ACT,
        ),
    ) { args, turn ->
        val name = args.string("name")
        val description = args.string("description")
        val body = args.string("body")
        // The whole body is shown: a saved skill is followed as instructions in later chats, so
        // the user must be able to see every line of it before it is stored.
        val warning = if (turn.taint.tainted) "\nThis task read a screen, page or file. Check these steps are what you want." else ""
        if (!confirm(turn, "Save the skill \"$name\"? $description$warning\n\n$body")) {
            return@NativeTool SourceResult("The user declined to save this skill.", isError = true)
        }
        skills.save(name, description, body)?.let { SourceResult(it, isError = true) }
            ?: SourceResult("Saved. It is listed from the next conversation turn.")
    }

    val delete = NativeTool(
        spec(
            ToolDescriptor(
                name = "skill_delete",
                description = "Delete a saved skill that is wrong or out of date.",
                requiredParameters = listOf(param("name", "The skill's name.")),
                optionalParameters = emptyList(),
            ),
            ToolRisk.ACT,
        ),
    ) { args, _ ->
        if (skills.delete(args.string("name"))) SourceResult("Deleted.") else SourceResult("No skill with that name.", isError = true)
    }

    return listOf(view, save, delete)
}
