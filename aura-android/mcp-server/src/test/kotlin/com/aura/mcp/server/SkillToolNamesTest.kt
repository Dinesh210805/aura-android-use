package com.aura.mcp.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The desktop bridge ships `aura-phone/SKILL.md`, which names this server's tools. A renamed or
 * removed tool left in it sends every agent that loads the skill to a tool that is not there.
 */
class SkillToolNamesTest {

    /** Identifiers the skill quotes that are fields or error codes, not tools. */
    private val notTools = setOf(
        "policy_blocked", "som_id", "el_id", "post_action_observation", "screen_changed",
        "seen_before", "app_name",
    )

    @Test
    fun `every snake_case name the skill quotes is a registered tool`() {
        val skill = File("../../aura-mcp-connect/skills/aura-phone/SKILL.md")
        assertTrue(skill.isFile, "skill not found at ${skill.absolutePath}")
        val registered = buildTestServer(Toolset.ALL).tools.keys
        val quoted = Regex("`([^`]+)`").findAll(skill.readText())
            .flatMap { Regex("[a-z]+(?:_[a-z]+)+").findAll(it.groupValues[1]) }
            .map { it.value }
            .toSet()
        val unknown = quoted - registered - notTools
        assertTrue(unknown.isEmpty(), "SKILL.md names tools that do not exist: $unknown")
    }
}
