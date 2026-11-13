package com.aura.aura_ui.agentcore

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `find_tools` ranking, and what a big connector costs the prompt before and after `load_tools`. */
class ToolSearchTest {
    private fun spec(name: String, description: String, toolset: String = "gws") = ToolSpec(
        ToolDescriptor(name, description, emptyList(), emptyList()), ToolRisk.READ, ToolOrigin.CONNECTOR, toolset, Origin.CONNECTOR,
    )

    private val big = (1..30).map { spec("gws__DOCS_TOOL_$it", "Edits a document, part $it.") } + listOf(
        spec("gws__GMAIL_SEND_EMAIL", "Send an email to one or more recipients."),
        spec("gws__GMAIL_FETCH_EMAILS", "Fetch emails from the inbox."),
        spec("gws__GOOGLECALENDAR_CREATE_EVENT", "Create a calendar event."),
    )

    @Test
    fun `the query's words rank name matches first`() {
        val hits = ToolSearch.rank("send email", big)
        assertEquals("gws__GMAIL_SEND_EMAIL", hits.first().name)
        assertTrue(hits.size <= ToolSearch.MAX_HITS)
        assertTrue(ToolSearch.rank("", big).isEmpty())
        assertEquals("Send an email to one or more recipients", ToolSearch.hint("Send an email to one or more recipients. Supports cc."))
    }

    @Test
    fun `a big connector is a count in the menu, and load_tools appends one schema`() = runBlocking {
        val source = object : ToolSource {
            override val id = "gws"
            override suspend fun list() = big
            override suspend fun call(name: String, args: JsonObject, turn: TurnState) = SourceResult("ok")
        }
        val hub = ToolHub(sources = { listOf(source) }, broker = UserBroker())
        hub.refresh()
        assertTrue(hub.toolsetMenu(), "gws: 33 tools; search them with find_tools" in hub.toolsetMenu())
        val turn = TurnState("t", RunMode.FOREGROUND, sink = {})
        val before = hub.visible(turn).map { it.name }
        assertFalse(before.any { it.startsWith("gws__") })
        turn.loadTool("gws__GMAIL_SEND_EMAIL")
        val after = hub.visible(turn).map { it.name }
        assertEquals(before, after.dropLast(1))
        assertEquals("gws__GMAIL_SEND_EMAIL", after.last())
    }
}
