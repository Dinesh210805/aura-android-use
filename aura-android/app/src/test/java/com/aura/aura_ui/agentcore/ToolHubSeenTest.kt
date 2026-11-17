package com.aura.aura_ui.agentcore

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKoogJSONObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 6: a phone action's `seen` note goes on the newest screen and nowhere else. */
class ToolHubSeenTest {

    private val tapDescriptor = ToolDescriptor(
        "tap", "Tap an element.",
        requiredParameters = listOf(ToolParameterDescriptor("som_id", "Element id.", ToolParameterType.Integer)),
        optionalParameters = emptyList(),
    )

    private val received = mutableListOf<JsonObject>()
    private var tapFails = false

    private val phone = object : ToolSource {
        override val id = "phone"
        override suspend fun list() = listOf(
            ToolSpec(ToolDescriptor("read_screen", "Look.", emptyList(), emptyList()), ToolRisk.READ, ToolOrigin.PHONE, ToolHub.SCREEN, Origin.SCREEN),
            ToolSpec(PhoneToolSource.withSeen(tapDescriptor), ToolRisk.ACT, ToolOrigin.PHONE, ToolHub.SCREEN, Origin.SCREEN),
        )
        override suspend fun call(name: String, args: JsonObject, turn: TurnState): SourceResult {
            if (name == "read_screen") return SourceResult("SEEN: Pro Expense | Add expense\nSCREEN 1x1\n| 3 Name |")
            received += args
            return SourceResult(if (tapFails) "no such element" else "tapped", isError = tapFails)
        }
    }

    private val turn = TurnState("t", RunMode.FOREGROUND, sink = {})

    private suspend fun call(name: String, args: JsonObject): ToolOutcome {
        val hub = ToolHub(sources = { listOf(phone) }, broker = UserBroker()).apply { refresh() }
        @Suppress("UNCHECKED_CAST")
        val tool = hub.registry(turn).getTool(name) as Tool<JSONObject, ToolOutcome>
        return tool.execute(args.toKoogJSONObject())
    }

    private fun tap(note: String?) = buildJsonObject {
        put("som_id", 3)
        note?.let { put("seen", it) }
    }

    @Test
    fun `withSeen adds an optional seen and leaves the required parameters alone`() {
        val d = PhoneToolSource.withSeen(tapDescriptor)
        assertEquals(tapDescriptor.requiredParameters, d.requiredParameters)
        assertEquals(listOf("seen"), d.optionalParameters.map { it.name })
        assertEquals(d, PhoneToolSource.withSeen(d))
    }

    @Test
    fun `a tap's note reaches the screen history but not the source`() = runBlocking {
        call("read_screen", JsonObject(emptyMap()))
        val out = call("tap", tap("empty add-expense form, filling name"))
        assertFalse(out.text, out.isError)
        assertEquals(JsonObject(mapOf("som_id" to JsonPrimitive(3))), received.single())
        assertTrue(turn.screens.log(null), turn.screens.log(null).endsWith("· note: empty add-expense form, filling name"))
    }

    @Test
    fun `a tap without a note still runs`() = runBlocking {
        val out = call("tap", tap(null))
        assertFalse(out.text, out.isError)
        assertEquals(1, received.size)
    }

    @Test
    fun `two failing taps with different notes are the same repeated call`() = runBlocking {
        tapFails = true
        call("tap", tap("first try"))
        call("tap", tap("second try, different words"))
        assertEquals(2, turn.failuresOf("tap" + tap(null)))
    }
}
