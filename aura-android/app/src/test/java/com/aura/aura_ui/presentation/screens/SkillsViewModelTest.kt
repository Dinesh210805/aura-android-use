package com.aura.aura_ui.presentation.screens

import android.content.Context
import com.aura.aura_ui.agent.skills.Skill
import com.aura.aura_ui.agent.skills.SkillSource
import com.aura.aura_ui.agent.skills.SkillTrust
import com.aura.aura_ui.agent.skills.UserSkillStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SkillsViewModelTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(bundled: SkillSource? = null) = SkillsViewModel(UserSkillStore(ctx), bundled)

    @Test fun `addSkill with valid fields succeeds and lists it`() {
        val vm = vm()
        assertTrue(vm.addSkill("Send Email", "Compose and send", "", "1. open 2. send"))
        assertEquals(1, vm.state.value.skills.size)
    }

    @Test fun `addSkill with blank name fails and sets error`() {
        val vm = vm()
        assertFalse(vm.addSkill("", "d", "", "body"))
        assertTrue(vm.state.value.addError != null)
        assertTrue(vm.state.value.skills.isEmpty())
    }

    @Test fun `delete removes the skill`() {
        val vm = vm()
        vm.addSkill("Send Email", "desc", "", "body")
        vm.delete(vm.state.value.skills.first().id)
        assertTrue(vm.state.value.skills.isEmpty())
    }

    @Test fun `new skills are enabled and toggle disables them`() {
        val vm = vm()
        vm.addSkill("Send Email", "desc", "", "body")
        val id = vm.state.value.skills.first().id
        assertTrue(id in vm.state.value.enabledIds)
        vm.toggle(id, false)
        assertFalse(id in vm.state.value.enabledIds)
    }

    @Test fun `updateSkill edits fields in place`() {
        val vm = vm()
        vm.addSkill("Send Email", "old desc", "", "old body")
        val id = vm.state.value.skills.first().id
        assertTrue(vm.updateSkill(id, "Send Email", "new desc", "when", "new body"))
        val updated = vm.state.value.skills.single()
        assertEquals(id, updated.id)
        assertEquals("new desc", updated.description)
        assertEquals("new body", updated.body)
    }

    @Test fun `updateSkill rename replaces the old skill and keeps enabled state`() {
        val vm = vm()
        vm.addSkill("Send Email", "desc", "", "body")
        val oldId = vm.state.value.skills.first().id
        vm.toggle(oldId, false)
        assertTrue(vm.updateSkill(oldId, "Send Mail", "desc", "", "body"))
        val skills = vm.state.value.skills
        assertEquals(1, skills.size)
        val newId = skills.single().id
        assertTrue(newId != oldId)
        // disabled state carried across the rename
        assertFalse(newId in vm.state.value.enabledIds)
    }

    @Test fun `SK4 - naming a skill after a bundled one is rejected with feedback`() = runTest(dispatcher) {
        val bundled = object : SkillSource {
            override suspend fun load() =
                listOf(Skill("send-email", "Send Email", "compose", "", "steps", SkillTrust.BUNDLED, "bundled"))
        }
        val vm = vm(bundled)
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.addSkill("Send Email", "my version", "", "my steps"))
        assertTrue(vm.state.value.addError!!.contains("built-in"))
        assertTrue(vm.state.value.skills.isEmpty())
    }

    @Test fun `SK3 - an oversized body is rejected with feedback`() {
        val vm = vm()
        assertFalse(vm.addSkill("Big", "desc", "", "x".repeat(20_000)))
        assertTrue(vm.state.value.addError!!.contains("too long"))
    }

    @Test fun `bundled skills load from the source`() = runTest(dispatcher) {
        val bundled = object : SkillSource {
            override suspend fun load() =
                listOf(Skill("whatsapp", "WhatsApp", "send messages", "", "steps", SkillTrust.BUNDLED, "bundled"))
        }
        val vm = vm(bundled)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.state.value.bundled.size)
        assertEquals("WhatsApp", vm.state.value.bundled.first().name)
    }
}
