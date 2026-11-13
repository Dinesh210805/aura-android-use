package com.aura.aura_ui.agentcore

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectorRiskTest {
    private fun tool(name: String, annotations: ToolAnnotations? = null) =
        Tool(name = name, inputSchema = ToolSchema(), description = "", annotations = annotations)

    @Test
    fun `annotations decide, and a tool without them falls back to its name`() {
        assertEquals(ToolRisk.READ, ConnectorRisk.of(tool("anything", ToolAnnotations(readOnlyHint = true))))
        assertEquals(ToolRisk.ACT, ConnectorRisk.of(tool("create_label", ToolAnnotations(readOnlyHint = false, destructiveHint = false))))
        assertEquals(ToolRisk.COMMIT, ConnectorRisk.of(tool("list_things", ToolAnnotations(readOnlyHint = false))))
        assertEquals(ToolRisk.READ, ConnectorRisk.of(tool("list_issues")))
        assertEquals(ToolRisk.COMMIT, ConnectorRisk.of(tool("create_issue")))
    }

    @Test
    fun `a Composio multi-execute call is rated by the app tools it runs`() {
        val rate = ConnectorRisk.forCall("COMPOSIO_MULTI_EXECUTE_TOOL")!!
        fun call(vararg slugs: String) = Json.parseToJsonElement(
            """{"tools":[${slugs.joinToString { """{"tool_slug":"$it","arguments":{}}""" }}]}""",
        ) as JsonObject
        assertEquals(ToolRisk.READ, rate(call("GMAIL_FETCH_EMAILS", "GOOGLECALENDAR_LIST_EVENTS")))
        assertEquals(ToolRisk.COMMIT, rate(call("GMAIL_FETCH_EMAILS", "GMAIL_SEND_EMAIL")))
        assertEquals(ToolRisk.COMMIT, rate(call("GMAIL_LIST_AND_DELETE_THREADS")))
        assertEquals(ToolRisk.COMMIT, rate(call("SOMEAPP_DO_THING")))
        assertEquals(ToolRisk.COMMIT, rate(JsonObject(emptyMap())))
        assertNull(ConnectorRisk.forCall("GMAIL_SEND_EMAIL"))
        assertEquals(ToolRisk.READ, ConnectorRisk.of(tool("COMPOSIO_SEARCH_TOOLS")))
    }
}
