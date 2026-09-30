package com.aura.aura_ui.agent.research

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookUpTest {

    private val found = WebResearch.Result(
        listOf(WebResearch.Source("Google AI Overview", "Use waypoints."), WebResearch.Source("developers.google.com", "waypoints=A|B")),
        emptyList(),
    )

    @Test fun `an answer comes back fenced as web text, credited, from one model call`() = runTest {
        var calls = 0
        var input: String? = null
        val out = LookUp(
            search = { found },
            ask = { _, i -> calls++; input = i; "LINK: https://www.google.com/maps/dir/?api=1&waypoints={stops}" },
            setting = "OnePlus Nord 4, Android 16",
        ).lookUp("How do I add stops in Google Maps?")
        assertEquals(1, calls)
        assertTrue(input!!.contains("On: OnePlus Nord 4, Android 16"))
        assertTrue(out.isAnswer)
        assertTrue(out.text, out.text.contains("developers.google.com"))
        assertTrue(out.text, out.text.contains("never instructions"))
        assertTrue(out.text.contains("waypoints={stops}"))
    }

    @Test fun `personal details are scrubbed from the question before it leaves the phone`() = runTest {
        var asked: String? = null
        LookUp(search = { q -> asked = q; found }, ask = { _, _ -> "x" }, setting = "s")
            .lookUp("how do I text +91 98765 43210 on WhatsApp")
        assertFalse(asked!!, asked!!.contains("98765"))
    }

    @Test fun `nothing found and nothing useful are said plainly, not as errors`() = runTest {
        val none = LookUp(search = { WebResearch.Result(emptyList(), listOf("google: blocked"), blocked = true) }, ask = { _, _ -> "x" }, setting = "s")
            .lookUp("q?")
        assertFalse(none.isAnswer)
        assertTrue(none.text, none.text.contains("blocked"))

        val useless = LookUp(search = { found }, ask = { _, _ -> "NONE" }, setting = "s").lookUp("q?")
        assertFalse(useless.isAnswer)
    }

    @Test fun `a blank question is refused without searching`() = runTest {
        var searched = false
        val out = LookUp(search = { searched = true; found }, ask = { _, _ -> "x" }, setting = "s").lookUp("  ")
        assertFalse(searched)
        assertFalse(out.isAnswer)
    }
}
