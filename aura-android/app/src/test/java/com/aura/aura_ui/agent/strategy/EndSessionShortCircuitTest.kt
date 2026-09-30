package com.aura.aura_ui.agent.strategy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A successful `end_session` must END the Koog loop in the same turn — the
 * WhatsApp trace showed each end_session costing +1 full LLM round-trip because
 * the loop only terminated on a plain-text assistant turn (and the model, seeing
 * the loop continue, invented extra verification work on top).
 *
 * [EndSessionShortCircuit] is the pure decision: given a tool result from this
 * turn, produce the final spoken answer (the tool's own `reason`) or null.
 */
class EndSessionShortCircuitTest {

    private fun resultJson(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private val successResult = resultJson(
        """{"content":[{"type":"text","text":"{\"success\":true,\"reason\":\"Opened WhatsApp and verified it is in the foreground.\",\"hint\":\"Session closed.\"}"}]}""",
    )

    @Test
    fun `successful end_session yields its reason as the final answer`() {
        assertEquals(
            "Opened WhatsApp and verified it is in the foreground.",
            EndSessionShortCircuit.finalAnswerFrom("end_session", successResult),
        )
    }

    @Test
    fun `a denied end_session keeps the loop running`() {
        // ActionGuard 1e denial arrives as isError=true — the model must see it and react.
        val denied = resultJson(
            """{"content":[{"type":"text","text":"Do not end yet - confirm first."}],"isError":true}""",
        )
        assertNull(EndSessionShortCircuit.finalAnswerFrom("end_session", denied))
    }

    @Test
    fun `other tools never short-circuit`() {
        assertNull(EndSessionShortCircuit.finalAnswerFrom("launch_app", successResult))
        assertNull(EndSessionShortCircuit.finalAnswerFrom("perceive_screen", successResult))
    }

    @Test
    fun `missing or blank reason falls back to a generic answer`() {
        val noReason = resultJson("""{"content":[{"type":"text","text":"{\"success\":true}"}]}""")
        assertEquals(EndSessionShortCircuit.DEFAULT_FINAL_ANSWER, EndSessionShortCircuit.finalAnswerFrom("end_session", noReason))
    }

    @Test
    fun `null or malformed result does not short-circuit`() {
        assertNull(EndSessionShortCircuit.finalAnswerFrom("end_session", null))
        val junk = resultJson("""{"weird":true}""")
        assertEquals(
            "a result with no readable payload still ends the run honestly",
            EndSessionShortCircuit.DEFAULT_FINAL_ANSWER,
            EndSessionShortCircuit.finalAnswerFrom("end_session", junk),
        )
    }
}
