package com.aura.aura_ui.mcp.bridge

import com.aura.aura_ui.contacts.ContactMatch
import com.aura.aura_ui.contacts.MatchStage
import com.aura.aura_ui.contacts.ResolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactResolutionMapperTest {

    private fun match(name: String = "Dinesh Kumar", score: Float = 0.95f) = ContactMatch(
        contactId = "42",
        displayName = name,
        phoneNumber = "+919876543210",
        score = score,
        matchStage = MatchStage.EXACT,
    )

    @Test
    fun `auto resolve maps to auto status with the single match`() {
        val r = ContactResolutionMapper.map(ResolveResult.AutoResolve(match()))
        assertEquals("auto", r.status)
        assertEquals(1, r.candidates.size)
        val c = r.candidates[0]
        assertEquals("42", c.contactId)
        assertEquals("Dinesh Kumar", c.displayName)
        assertEquals("+919876543210", c.phoneNumber)
        assertEquals(0.95f, c.score, 0.001f)
    }

    @Test
    fun `disambiguate maps all candidates in order`() {
        val r = ContactResolutionMapper.map(
            ResolveResult.Disambiguate(listOf(match("Dinesh A", 0.8f), match("Dinesh B", 0.72f))),
        )
        assertEquals("disambiguate", r.status)
        assertEquals(listOf("Dinesh A", "Dinesh B"), r.candidates.map { it.displayName })
    }

    @Test
    fun `manual entry maps to none with no candidates`() {
        val r = ContactResolutionMapper.map(ResolveResult.ManualEntry)
        assertEquals("none", r.status)
        assertTrue(r.candidates.isEmpty())
    }
}
