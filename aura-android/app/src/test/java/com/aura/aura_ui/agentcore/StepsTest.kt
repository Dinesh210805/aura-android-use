package com.aura.aura_ui.agentcore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepsTest {
    private fun args(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun `a trace step keeps the label and plain args, never the som_id`() {
        val s = Step.of("scroll", args("""{"som_id":12,"direction":"down"}"""), "Rides")
        assertEquals(Step("scroll", "Rides", args = mapOf("direction" to "down")), s)
        assertEquals(listOf(s), Step.decode(Step.encode(listOf(s))))
    }

    @Test
    fun `a skill keeps no typed text, links or personal details`() {
        val trace = listOf(
            Step.of("type_text", args("""{"som_id":3,"text":"12 MG Road"}"""), "Where to?"),
            Step.of("open_deeplink", args("""{"url":"https://wa.me/919876543210"}"""), null),
            Step.of("tap", args("{}"), "Call 9876543210"),
        )
        val skill = Step.forSkill(trace)
        assertEquals(Step.TEXT_SLOT, skill[0].text)
        assertEquals("Where to?", skill[0].target)
        assertNull(skill[1].args["url"])
        assertEquals(false, skill[2].target.orEmpty().contains("9876543210"))
    }
}
