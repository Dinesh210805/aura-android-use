package com.aura.aura_ui.agent.skills

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledSkillSourceTest {
    private val good = "---\nname: Send Email\ndescription: send an email\n---\nbody"
    private val bad = "no frontmatter here"

    @Test fun `parses good assets and skips bad ones`() = runTest {
        val src = BundledSkillSource(
            listFiles = { listOf("send-email.md", "broken.md") },
            read = { f -> if (f == "send-email.md") good else bad },
        )
        val skills = src.load()
        assertEquals(1, skills.size)
        assertEquals(SkillTrust.BUNDLED, skills.single().trust)
        assertTrue(skills.single().name == "Send Email")
    }

    @Test fun `missing asset content is skipped`() = runTest {
        val src = BundledSkillSource(listFiles = { listOf("x.md") }, read = { null })
        assertTrue(src.load().isEmpty())
    }
}
