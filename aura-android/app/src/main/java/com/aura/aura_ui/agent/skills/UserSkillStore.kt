package com.aura.aura_ui.agent.skills

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists user-authored skills (USER tier) as JSON in plain prefs. No secrets involved. */
class UserSkillStore(context: Context) : SkillSource {
    private val prefs = context.getSharedPreferences("aura_skills", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Dto(
        val id: String, val name: String, val description: String,
        val whenToUse: String, val body: String, val enabled: Boolean = true,
    )

    fun upsert(name: String, description: String, whenToUse: String, body: String): Skill? {
        // SK6 — these three fields have single-line semantics; an embedded newline would
        // inject stray frontmatter lines into the build-then-reparse round-trip below
        // (a description containing "\nname: other" silently changed the skill's id).
        val safeName = oneLine(name)
        val safeDescription = oneLine(description)
        val safeWhenToUse = oneLine(whenToUse)
        // SK3 — trusted-tier bodies were uncapped end-to-end; a huge pasted body
        // permanently broke every run that loaded it (Groq 30k-TPM ceiling).
        val safeBody = body.trim().take(SkillBudget.MAX_BODY_CHARS)
        val raw = buildString {
            append("---\n")
            append("name: ").append(safeName).append('\n')
            append("description: ").append(safeDescription).append('\n')
            if (safeWhenToUse.isNotBlank()) append("whenToUse: ").append(safeWhenToUse).append('\n')
            append("---\n").append(safeBody)
        }
        val parsed = SkillFrontmatterParser.parse(raw, SkillTrust.USER, "user") ?: return null
        val next = (read().filterNot { it.id == parsed.id }) + parsed.toDto(enabled = isEnabled(parsed.id, default = true))
        write(next)
        return parsed
    }

    fun listRaw(): List<Skill> = read().map { it.toSkill() }

    fun setEnabled(id: String, enabled: Boolean) {
        write(read().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun isEnabled(id: String): Boolean = isEnabled(id, default = true)

    fun delete(id: String) = write(read().filterNot { it.id == id })

    override suspend fun load(): List<Skill> = read().filter { it.enabled }.map { it.toSkill() }

    private fun oneLine(s: String) = s.replace(Regex("""\s*\r?\n\s*"""), " ").trim()

    private fun isEnabled(id: String, default: Boolean) = read().firstOrNull { it.id == id }?.enabled ?: default
    private fun read(): List<Dto> =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<List<Dto>>(it) }.getOrNull() } ?: emptyList()
    private fun write(list: List<Dto>) = prefs.edit().putString(KEY, json.encodeToString(list)).apply()

    private fun Skill.toDto(enabled: Boolean) = Dto(id, name, description, whenToUse, body, enabled)
    private fun Dto.toSkill() = Skill(id, name, description, whenToUse, body, SkillTrust.USER, "user")

    private companion object { const val KEY = "skills_json" }
}
