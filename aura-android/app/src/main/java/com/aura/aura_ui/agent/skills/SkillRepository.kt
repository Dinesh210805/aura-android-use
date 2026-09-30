package com.aura.aura_ui.agent.skills

/** One origin of skills (bundled assets, user store, or — reserved — an MCP server). */
interface SkillSource {
    suspend fun load(): List<Skill>
}

/**
 * Fans skill discovery across [sources] and de-duplicates by [Skill.id]. Collision rule mirrors
 * #2's no-shadow: the **more-trusted** skill wins (BUNDLED > USER > MCP), so an untrusted MCP
 * skill can never replace a bundled/user skill of the same id.
 */
class SkillRepository(private val sources: List<SkillSource>) {
    suspend fun all(): List<Skill> {
        val byId = LinkedHashMap<String, Skill>()
        for (s in sources.flatMap { it.load() }) {
            val existing = byId[s.id]
            if (existing == null || rank(s.trust) < rank(existing.trust)) byId[s.id] = s
        }
        return byId.values.toList()
    }

    suspend fun find(name: String): Skill? {
        val key = name.trim().lowercase()
        return all().firstOrNull { it.id == key || it.name.lowercase() == key }
    }

    private fun rank(t: SkillTrust) = when (t) {
        SkillTrust.BUNDLED -> 0
        SkillTrust.USER -> 1
        SkillTrust.MCP -> 2
    }
}
