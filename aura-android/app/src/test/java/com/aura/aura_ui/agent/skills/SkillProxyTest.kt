package com.aura.aura_ui.agent.skills

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillProxyTest {
    private fun skill(name: String, trust: SkillTrust, body: String) =
        Skill(name.lowercase().replace(' ', '-'), name, "do $name", "", body, trust, trust.name.lowercase())
    private class Fake(val skills: List<Skill>) : SkillSource { override suspend fun load() = skills }
    private fun proxy(vararg s: Skill) = SkillProxy(SkillRepository(listOf(Fake(s.toList()))))
    private fun text(r: CallToolResult) =
        r.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    @Test fun `listSkills renders names`() = runTest {
        val out = proxy(skill("Send Email", SkillTrust.BUNDLED, "b")).listSkills(null)
        assertTrue(out.contains("Send Email"))
    }

    @Test fun `useSkill returns the body for a known skill`() = runTest {
        val r = proxy(skill("Send Email", SkillTrust.BUNDLED, "OPEN GMAIL THEN SEND")).useSkill("send-email")
        assertTrue(r.isError == false)
        assertTrue(text(r).contains("OPEN GMAIL THEN SEND"))
    }

    @Test fun `unknown skill returns a model-readable error`() = runTest {
        val r = proxy(skill("Send Email", SkillTrust.BUNDLED, "b")).useSkill("nope")
        assertTrue(r.isError == true)
    }

    @Test fun `untrusted mcp skill body is labelled untrusted`() = runTest {
        val r = proxy(skill("Shady", SkillTrust.MCP, "do the thing")).useSkill("shady")
        assertTrue(text(r).contains("UNTRUSTED", ignoreCase = true))
    }

    @Test fun `SK3 - an oversized trusted body is capped at load with a marker`() = runTest {
        val huge = "y".repeat(SkillBudget.MAX_BODY_CHARS + 10_000)
        val r = proxy(skill("Big", SkillTrust.USER, huge)).useSkill("big")
        val body = text(r)
        assertTrue(body.length < huge.length)
        assertTrue(body.contains("truncated"))
    }
}
