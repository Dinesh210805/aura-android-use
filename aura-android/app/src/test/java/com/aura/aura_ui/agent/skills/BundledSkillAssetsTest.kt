package com.aura.aura_ui.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses the REAL `.md` files under `src/main/assets/skills` (read from the source tree —
 * unit tests run with the module as the working directory). A skill whose frontmatter breaks
 * is silently skipped by [BundledSkillSource]; this turns that silent disappearance into a
 * failing test.
 */
class BundledSkillAssetsTest {

    private val skillFiles = File("src/main/assets/skills")
        .listFiles { f -> f.extension == "md" }.orEmpty().toList()

    @Test fun `every shipped skill asset parses - none silently skipped`() {
        assertTrue("expected bundled skill assets in src/main/assets/skills", skillFiles.isNotEmpty())
        skillFiles.forEach { f ->
            val skill = SkillFrontmatterParser.parse(f.readText(), SkillTrust.BUNDLED, "bundled")
            assertNotNull("${f.name} failed frontmatter parsing and would be dropped", skill)
        }
    }

    @Test fun `shipped skills have unique ids and complete metadata`() {
        val skills = skillFiles.mapNotNull { f ->
            SkillFrontmatterParser.parse(f.readText(), SkillTrust.BUNDLED, "bundled")
        }
        assertEquals(skills.size, skills.map { it.id }.toSet().size)
        skills.forEach { s ->
            assertTrue("${s.id} needs a whenToUse for discovery", s.whenToUse.isNotBlank())
            assertTrue("${s.id} needs a non-empty body", s.body.isNotBlank())
        }
    }
}
