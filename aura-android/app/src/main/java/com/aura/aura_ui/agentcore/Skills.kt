package com.aura.aura_ui.agentcore

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.io.File
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
 * - Fails: an unreadable or malformed skill file is skipped, never an error.
 */
data class Skill(val name: String, val description: String)

object SkillPolicy {
    private val NAME = Regex("[a-z0-9][a-z0-9-]{1,39}")
    const val MAX_DESCRIPTION = 200
    const val MAX_BODY = 8_000
    const val MAX_SKILLS = 40

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

class SkillStore(private val dir: File) {

    fun list(): List<Skill> = dir.listFiles().orEmpty().sortedBy { it.name }.mapNotNull { folder ->
        val text = runCatching { File(folder, FILE).readText() }.getOrNull() ?: return@mapNotNull null
        frontmatter(text, "description")?.let { Skill(folder.name, it) }
    }

    /** The lines for the system prompt, or blank when there are no skills. */
    fun index(): String = list().joinToString("\n") { "${it.name}: ${it.description}" }

    fun read(name: String): String? =
        runCatching { File(File(dir, name.filter { it.isLetterOrDigit() || it == '-' }), FILE).readText() }.getOrNull()
            ?.substringAfter("\n---\n", "")?.trim()?.takeIf { it.isNotEmpty() }

    /** Returns why the skill was refused, or null when it was saved. */
    fun save(name: String, description: String, body: String): String? {
        SkillPolicy.rejection(name, description, body)?.let { return it }
        val folder = File(dir, name)
        if (!folder.exists() && list().size >= SkillPolicy.MAX_SKILLS) return "There are already ${SkillPolicy.MAX_SKILLS} skills. Delete one first."
        folder.mkdirs()
        File(folder, FILE).writeText("---\nname: $name\ndescription: ${description.trim()}\n---\n${body.trim()}\n")
        return null
    }

    fun delete(name: String): Boolean = File(dir, name.filter { it.isLetterOrDigit() || it == '-' }).deleteRecursively()

    private fun frontmatter(text: String, key: String): String? =
        text.substringAfter("---\n", "").substringBefore("\n---").lineSequence()
            .firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val FILE = "SKILL.md"
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
        skills.read(args.string("name"))?.let { SourceResult(it) } ?: SourceResult("No skill with that name.", isError = true)
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
