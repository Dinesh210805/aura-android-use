package com.aura.aura_ui.agent.skills

enum class SkillTrust { BUNDLED, USER, MCP }

val SkillTrust.isTrusted: Boolean get() = this != SkillTrust.MCP

data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val whenToUse: String,
    val body: String,
    val trust: SkillTrust,
    val source: String,
)
