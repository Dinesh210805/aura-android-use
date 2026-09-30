package com.aura.aura_ui.agent.skills

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillRepositoryTest {
    private fun skill(name: String, trust: SkillTrust) =
        Skill(name.lowercase(), name, "d", "", "body-${trust.name}", trust, trust.name.lowercase())
    private class Fake(val skills: List<Skill>) : SkillSource {
        override suspend fun load() = skills
    }

    @Test fun `merges sources`() = runTest {
        val repo = SkillRepository(listOf(Fake(listOf(skill("A", SkillTrust.BUNDLED))), Fake(listOf(skill("B", SkillTrust.USER)))))
        assertEquals(setOf("a", "b"), repo.all().map { it.id }.toSet())
    }

    @Test fun `mcp cannot shadow a bundled skill of the same id`() = runTest {
        val repo = SkillRepository(listOf(Fake(listOf(skill("Dup", SkillTrust.MCP))), Fake(listOf(skill("Dup", SkillTrust.BUNDLED)))))
        val dup = repo.all().single { it.id == "dup" }
        assertEquals(SkillTrust.BUNDLED, dup.trust)
    }

    @Test fun `find matches by name case-insensitively`() = runTest {
        val repo = SkillRepository(listOf(Fake(listOf(skill("Send Email", SkillTrust.USER)))))
        assertEquals("send email", repo.find("SEND EMAIL")?.name?.lowercase())
        assertNull(repo.find("nope"))
    }
}
