package com.aura.aura_ui.services

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepPillTest {

    @After
    fun reset() = StepPill.clear()

    private fun args(vararg kv: Pair<String, String>) = JsonObject(kv.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `the driver's own words win and are not overwritten by the tapped label`() {
        StepPill.onTool("tap", "Opening your Library", args("som_id" to "4"))
        assertFalse(StepPill.wantsTarget)
        StepPill.onTarget("Library")
        assertEquals("Opening your Library", StepPill.line.value)
    }

    @Test
    fun `without a status the line names the tapped element`() {
        StepPill.onTool("tap", null, args("som_id" to "4"))
        assertEquals("Tapping", StepPill.line.value)
        assertTrue(StepPill.wantsTarget)
        StepPill.onTarget("Library")
        assertEquals("Tapping Library", StepPill.line.value)
    }

    @Test
    fun `fallbacks read like a person, not a tool`() {
        assertEquals("Opening Spotify", StepPill.fallback("launch_app", args("app_name" to "Spotify"), null))
        assertEquals("Scrolling down", StepPill.fallback("scroll", args("direction" to "down"), null))
        assertEquals("Typing “lofi beats”", StepPill.fallback("type_text", args("text" to "lofi beats"), null))
        assertEquals("Run steps", StepPill.fallback("run_steps", null, null))
    }

    @Test
    fun `clear empties the line`() {
        StepPill.onTool("tap", "Sending it", null)
        StepPill.clear()
        assertEquals("", StepPill.line.value)
        assertFalse(StepPill.wantsTarget)
    }

    @Test
    fun `the trail keeps the refined line and folds repeats`() {
        StepPill.resetTrail()
        StepPill.onTool("tap", null, null)
        StepPill.onTarget("Library")
        StepPill.onTool("scroll", "Looking for the album", null)
        StepPill.onTool("scroll", "Looking for the album", null)
        assertEquals(listOf("Tapping Library", "Looking for the album"), StepPill.trail())
        StepPill.clear()
        assertEquals(2, StepPill.trail().size)
    }
}
