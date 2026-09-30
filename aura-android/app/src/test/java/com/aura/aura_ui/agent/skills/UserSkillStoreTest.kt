package com.aura.aura_ui.agent.skills

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UserSkillStoreTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Test fun `upsert then list round-trips a user skill`() {
        val store = UserSkillStore(ctx)
        val s = store.upsert("Send Email", "Compose and send an email", "when asked to email", "1. open 2. send")
        assertEquals("send-email", s?.id)
        assertEquals(SkillTrust.USER, store.listRaw().single().trust)
    }

    @Test fun `invalid skill (blank name) is rejected`() {
        val store = UserSkillStore(ctx)
        assertNull(store.upsert("", "d", "", "body"))
        assertTrue(store.listRaw().isEmpty())
    }

    @Test fun `disabled skill is excluded from load`() = runTest {
        val store = UserSkillStore(ctx)
        store.upsert("Send Email", "desc", "", "body")
        store.setEnabled("send-email", false)
        assertFalse(store.isEnabled("send-email"))
        assertTrue(store.load().isEmpty())
    }

    @Test fun `delete removes the skill`() {
        val store = UserSkillStore(ctx)
        store.upsert("Send Email", "desc", "", "body")
        store.delete("send-email")
        assertTrue(store.listRaw().isEmpty())
    }

    @Test fun `SK6 - a newline in the description cannot inject frontmatter or change the id`() {
        val store = UserSkillStore(ctx)
        val s = store.upsert("My Skill", "harmless\nname: hijacked", "", "body")
        assertEquals("my-skill", s?.id) // last-duplicate-key hijack neutralized
        assertEquals("harmless name: hijacked", s?.description)
    }

    @Test fun `SK6 - a frontmatter terminator inside a field cannot break parsing`() {
        val store = UserSkillStore(ctx)
        val s = store.upsert("My Skill", "line one\n---\nline two", "", "the real body")
        assertEquals("my-skill", s?.id)
        assertEquals("the real body", s?.body)
    }

    @Test fun `SK3 - an oversized body is capped at upsert`() {
        val store = UserSkillStore(ctx)
        val s = store.upsert("Big", "desc", "", "x".repeat(SkillBudget.MAX_BODY_CHARS + 5_000))
        assertEquals(SkillBudget.MAX_BODY_CHARS, s?.body?.length)
    }
}
