package com.aura.aura_ui.agentcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {
    private val all: (SlashCommand) -> Boolean = { true }

    @Test
    fun `a bare slash lists every available command`() {
        assertEquals(SlashCommands.ALL, SlashCommands.matching("/", all))
    }

    @Test
    fun `a prefix narrows the list`() {
        assertEquals(listOf("/goal"), SlashCommands.matching("/go", all).map { it.name })
    }

    @Test
    fun `typing past the command closes the menu`() {
        assertTrue(SlashCommands.matching("/goal buy", all).isEmpty())
    }

    @Test
    fun `plain text is not a command`() {
        assertTrue(SlashCommands.matching("hello", all).isEmpty())
    }

    @Test
    fun `an unavailable command is not listed`() {
        assertTrue(SlashCommands.matching("/stop", { it.action != SlashAction.STOP }).isEmpty())
    }

    @Test
    fun `text commands share their names with the parsers`() {
        val names = SlashCommands.ALL.filter { it.takesText }.map { it.name + " " }
        assertEquals(listOf(Goals.PREFIX, Btw.PREFIX), names)
    }
}
