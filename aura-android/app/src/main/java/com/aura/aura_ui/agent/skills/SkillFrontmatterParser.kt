package com.aura.aura_ui.agent.skills

/**
 * Parses a markdown skill: a leading `---` frontmatter block (name/description/whenToUse) + body.
 * Adversarial: missing name OR description → null (don't guess); a tiny line parser for the three
 * known keys (no third-party YAML lib), mirroring the SDK-tailored `McpToolSchemaParser` precedent.
 */
object SkillFrontmatterParser {
    fun parse(raw: String, trust: SkillTrust, source: String): Skill? {
        val text = raw.trim()
        if (!text.startsWith("---")) return null
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return null
        val front = text.substring(3, end).trim()
        val body = text.substring(end + 4).trim()
        val fields = front.lineSequence().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: return null
        val description = fields["description"]?.takeIf { it.isNotBlank() } ?: return null
        return Skill(
            id = slug(name),
            name = name,
            description = description,
            whenToUse = fields["whentouse"].orEmpty(),
            body = body,
            trust = trust,
            source = source,
        )
    }

    /** Public so callers can predict a skill's derived id (SK4 collision check at authoring time). */
    fun slug(s: String) = s.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
