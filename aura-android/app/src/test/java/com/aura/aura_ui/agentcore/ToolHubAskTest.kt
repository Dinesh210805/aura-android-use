package com.aura.aura_ui.agentcore

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKoogJSONObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 of fix-safety-approvals: a card nobody answered is not a decline. */
class ToolHubAskTest {

    private val calls = mutableListOf<JsonObject>()

    private val phone = object : ToolSource {
        override val id = "phone"
        override suspend fun list() = listOf(
            ToolSpec(
                ToolDescriptor("tap", "Tap.", listOf(ToolParameterDescriptor("som_id", "Element id.", ToolParameterType.Integer)), emptyList()),
                ToolRisk.COMMIT, ToolOrigin.PHONE, ToolHub.SCREEN, Origin.SCREEN,
            ),
        )
        override suspend fun call(name: String, args: JsonObject, turn: TurnState): SourceResult {
            calls += args
            return SourceResult("tapped")
        }
    }

    /** A tap through the hub, with the card answered [answer] (null: nobody answered). */
    private fun tap(answer: String?): ToolOutcome = runBlocking {
        val broker = UserBroker { _, _, _ -> Result.success(answer) }
        val hub = ToolHub(sources = { listOf(phone) }, broker = broker).apply { refresh() }
        @Suppress("UNCHECKED_CAST")
        val tool = hub.registry(TurnState("t", RunMode.FOREGROUND, sink = {})).getTool("tap") as Tool<JSONObject, ToolOutcome>
        tool.execute(buildJsonObject { put("som_id", 3) }.toKoogJSONObject())
    }

    @Test
    fun `no answer tells the model nobody answered and runs nothing`() {
        val out = tap(null)
        assertTrue(out.isError)
        assertEquals(SafetyGate.NO_ANSWER, out.text)
        assertEquals(0, calls.size)
    }

    @Test
    fun `Deny is a decline`() {
        val out = tap(UserBroker.DENY)
        assertEquals(SafetyGate.DECLINED, out.text)
        assertEquals(0, calls.size)
    }

    @Test
    fun `Allow runs the step`() {
        val out = tap(UserBroker.ALLOW)
        assertTrue(out.text, !out.isError && "tapped" in out.text)
        assertEquals(1, calls.size)
    }
}
