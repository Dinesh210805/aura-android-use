package com.aura.aura_ui.agent.memory

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The contradiction path: a later claim about the same SUBJECT replaces the earlier one instead of
 * stacking beside it. Lexical overlap cannot see these contradictions — "vegetarian" and "eats
 * chicken now" share no tokens — which is exactly why the subject slot exists.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryContradictionTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private var now = 1000L
    private fun svc(name: String) = EncryptedMemoryService(EncryptedJsonStore(ctx, name)) { now }

    @Test fun `same subject replaces rather than appends`() = runTest {
        val s = svc("mem_contra_1")
        s.saveResolved(MemoryType.USER, "is vegetarian", subject = "diet")
        now = 2000L
        val out = s.saveResolved(MemoryType.USER, "eats chicken now", subject = "diet")

        assertEquals(EncryptedMemoryService.SaveKind.REPLACED, out.kind)
        assertEquals("is vegetarian", out.previousText)
        assertEquals(1, s.allEntries().size)
        assertEquals("eats chicken now", s.allEntries().single().text)
    }

    @Test fun `a replacement resets the evidence count`() = runTest {
        val s = svc("mem_contra_2")
        s.saveResolved(MemoryType.USER, "works at Acme", subject = "employer")
        s.saveResolved(MemoryType.USER, "works at Acme", subject = "employer") // restated
        assertEquals(2, s.allEntries().single().confirmations)

        s.saveResolved(MemoryType.USER, "works at Globex", subject = "employer")
        assertEquals(1, s.allEntries().single().confirmations)
    }

    @Test fun `restating the same fact still reinforces`() = runTest {
        val s = svc("mem_contra_3")
        s.saveResolved(MemoryType.USER, "lives in Coimbatore", subject = "home city")
        val out = s.saveResolved(MemoryType.USER, "lives in Coimbatore", subject = "home city")
        assertEquals(EncryptedMemoryService.SaveKind.REINFORCED, out.kind)
        assertEquals(2, out.entry.confirmations)
    }

    @Test fun `replacement keeps the id and the user's pin`() = runTest {
        val s = svc("mem_contra_4")
        val first = s.saveResolved(MemoryType.USER, "is vegetarian", subject = "diet").entry
        s.setPinned(first.id, true)
        val second = s.saveResolved(MemoryType.USER, "eats chicken now", subject = "diet").entry
        assertEquals(first.id, second.id)
        assertTrue(second.pinned)
    }

    @Test fun `different subjects stay separate memories`() = runTest {
        val s = svc("mem_contra_5")
        s.saveResolved(MemoryType.USER, "is vegetarian", subject = "diet")
        s.saveResolved(MemoryType.USER, "lives in Coimbatore", subject = "home city")
        assertEquals(2, s.allEntries().size)
    }

    @Test fun `supersedes by id wins over everything else`() = runTest {
        val s = svc("mem_contra_6")
        val target = s.saveResolved(MemoryType.USER, "is vegetarian", subject = "diet").entry
        val out = s.saveResolved(MemoryType.USER, "eats chicken now", supersedesId = target.id)
        assertEquals(target.id, out.entry.id)
        assertEquals(1, s.allEntries().size)
    }

    @Test fun `subjectless saves keep the old restatement guard`() = runTest {
        val s = svc("mem_contra_7")
        s.save(MemoryType.USER, "prefers dark mode always")
        s.save(MemoryType.USER, "prefers dark mode always")
        assertEquals(1, s.allEntries().size)
        s.save(MemoryType.USER, "has a sister in Coimbatore")
        assertEquals(2, s.allEntries().size)
    }

    @Test fun `subject does not merge across types`() = runTest {
        val s = svc("mem_contra_8")
        s.saveResolved(MemoryType.USER, "is vegetarian", subject = "diet")
        s.saveResolved(MemoryType.FEEDBACK, "never suggest meat", subject = "diet")
        assertEquals(2, s.allEntries().size)
    }
}
