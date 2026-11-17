package com.aura.aura_ui.agentcore

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKoogJSONObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 8e: `app_map` returns the map as untrusted screen text and taints the turn. */
class ToolHubAppMapTest {

    private val map = AppMaps.HINT + "\nScreen \"Settings\": All settings.\n  button \"Add account\" · bottom-centre"

    private suspend fun call(app: String, turn: TurnState): ToolOutcome {
        val hub = ToolHub(sources = { emptyList() }, broker = UserBroker(), appMap = { if (it == "Instagram") map else null })
        hub.refresh()
        @Suppress("UNCHECKED_CAST")
        val tool = hub.registry(turn).getTool(ToolHub.APP_MAP) as Tool<JSONObject, ToolOutcome>
        return tool.execute(buildJsonObject { put("app", app) }.toKoogJSONObject())
    }

    @Test
    fun `the map comes back fenced as screen text and taints the turn`() = runBlocking {
        val turn = TurnState("t", RunMode.FOREGROUND, sink = {})
        assertFalse(Origin.SCREEN in turn.taint.origins)
        val out = call("Instagram", turn)
        assertFalse(out.text, out.isError)
        assertTrue(out.text, out.text.startsWith("<untrusted source=\"screen\">\n"))
        assertTrue(out.text, AppMaps.HINT in out.text && "button \"Add account\"" in out.text)
        assertTrue(Origin.SCREEN in turn.taint.origins)
    }

    @Test
    fun `an app with no map says so`() = runBlocking {
        val out = call("Swiggy", TurnState("t", RunMode.FOREGROUND, sink = {}))
        assertEquals("<untrusted source=\"screen\">\nNo map for Swiggy yet.\n</untrusted>", out.text)
    }
}
