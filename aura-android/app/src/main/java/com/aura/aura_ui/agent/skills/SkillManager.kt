package com.aura.aura_ui.agent.skills

/** Assembles the per-run skill listing + the proxy. Null listing = no skills = unchanged path. */
class SkillManager(private val repository: SkillRepository) {
    suspend fun listingOrNull(): String? = SkillDiscoveryListing.build(repository.all()).ifEmpty { null }
    fun proxy(): SkillProxy = SkillProxy(repository)
}
