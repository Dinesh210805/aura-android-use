package com.aura.aura_ui.presentation.screens

import android.content.Context
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.agent.memory.EncryptedLearningsStore
import com.aura.aura_ui.agent.memory.EncryptedMemoryService
import com.aura.aura_ui.agent.memory.MemoryType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MemoryViewModelTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Test fun `lists stored entries and learnings`() = runTest {
        EncryptedMemoryService(EncryptedJsonStore(ctx, "aura_memory")).save(MemoryType.USER, "likes spotify")
        EncryptedLearningsStore(EncryptedJsonStore(ctx, "aura_learnings"))
            .recordVerifiedPath("com.x", "navigate", listOf("tap \"Menu\""))
        val vm = MemoryViewModel(ctx)
        assertTrue(vm.state.value.assistant.facts.isNotEmpty())
        assertTrue(vm.state.value.appLessons.isNotEmpty())
    }

    /** The screen groups by tier, so a fact and a past chat must never land in the same list. */
    @Test fun `assistant memory is grouped by tier`() = runTest {
        val mem = EncryptedMemoryService(EncryptedJsonStore(ctx, "aura_memory"))
        mem.save(MemoryType.USER, "name is Dinesh")
        mem.save(MemoryType.FEEDBACK, "keep replies short")
        mem.save(MemoryType.STYLE, "mixes Tamil and English")
        mem.appendEpisode("You asked about trains")
        mem.logAction("book a cab", "failed")

        val ui = MemoryViewModel(ctx).state.value.assistant
        assertEquals(listOf("name is Dinesh"), ui.facts.map { it.text })
        assertEquals(listOf("keep replies short"), ui.preferences.map { it.text })
        assertEquals(listOf("mixes Tamil and English"), ui.style.map { it.text })
        assertEquals(listOf("You asked about trains"), ui.episodes.map { it.text })
        assertEquals(listOf("book a cab → failed"), ui.other.map { it.text })
    }

    @Test fun `pin then delete round-trips through the view model`() = runTest {
        val mem = EncryptedMemoryService(EncryptedJsonStore(ctx, "aura_memory"))
        val entry = mem.save(MemoryType.USER, "allergic to peanuts")
        val vm = MemoryViewModel(ctx)

        vm.setMemoryPinned(entry.id, true)
        assertTrue(vm.state.value.assistant.facts.single().pinned)

        vm.deleteMemory(entry.id)
        assertTrue(vm.state.value.assistant.isEmpty)
    }

    @Test fun `clearAll empties both stores`() = runTest {
        EncryptedMemoryService(EncryptedJsonStore(ctx, "aura_memory")).save(MemoryType.USER, "x")
        val vm = MemoryViewModel(ctx)
        vm.clearAll()
        assertTrue(vm.state.value.assistant.isEmpty)
        assertTrue(vm.state.value.appLessons.isEmpty())
    }

    @Test fun `toggling learnings is reflected in state`() {
        val vm = MemoryViewModel(ctx)
        vm.setLearningsEnabled(false)
        assertFalse(vm.state.value.learningsEnabled)
    }
}
