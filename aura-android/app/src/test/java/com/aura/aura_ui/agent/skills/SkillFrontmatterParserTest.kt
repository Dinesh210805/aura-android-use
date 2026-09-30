package com.aura.aura_ui.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillFrontmatterParserTest {
    private val valid = """
        ---
        name: Send Email
        description: Compose and send an email in the Gmail app
        whenToUse: when the user asks to email someone
        ---
        1. Open Gmail. 2. Tap compose. 3. Fill to/subject/body. 4. Send.
    """.trimIndent()

    @Test fun `parses frontmatter and body`() {
        val s = SkillFrontmatterParser.parse(valid, SkillTrust.BUNDLED, "bundled")!!
        assertEquals("Send Email", s.name)
        assertEquals("Compose and send an email in the Gmail app", s.description)
        assertTrue(s.whenToUse.contains("email someone"))
        assertTrue(s.body.contains("Tap compose"))
        assertEquals(SkillTrust.BUNDLED, s.trust)
        assertEquals("send-email", s.id) // slug of name
    }

    @Test fun `missing name returns null`() {
        val raw = "---\ndescription: x\n---\nbody"
        assertNull(SkillFrontmatterParser.parse(raw, SkillTrust.USER, "user"))
    }

    @Test fun `missing description returns null`() {
        val raw = "---\nname: X\n---\nbody"
        assertNull(SkillFrontmatterParser.parse(raw, SkillTrust.USER, "user"))
    }

    @Test fun `no frontmatter block returns null`() {
        assertNull(SkillFrontmatterParser.parse("just text", SkillTrust.USER, "user"))
    }

    @Test fun `mcp tier is untrusted`() {
        assertTrue(SkillTrust.BUNDLED.isTrusted)
        assertTrue(SkillTrust.USER.isTrusted)
        assertTrue(!SkillTrust.MCP.isTrusted)
    }
}
