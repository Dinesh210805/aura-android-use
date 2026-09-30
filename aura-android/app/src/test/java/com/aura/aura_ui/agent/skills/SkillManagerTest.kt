package com.aura.aura_ui.agent.skills

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillManagerTest {
    private class Fake(val skills: List<Skill>) : SkillSource { override suspend fun load() = skills }

    @Test fun `no skills yields null listing (today's path preserved)`() = runTest {
        val mgr = SkillManager(SkillRepository(listOf(Fake(emptyList()))))
        assertNull(mgr.listingOrNull())
    }

    @Test fun `with skills the listing is non-null`() = runTest {
        val s = Skill("a", "Alpha", "d", "", "body", SkillTrust.BUNDLED, "bundled")
        val mgr = SkillManager(SkillRepository(listOf(Fake(listOf(s)))))
        assertTrue(mgr.listingOrNull()!!.contains("Alpha"))
    }
}
