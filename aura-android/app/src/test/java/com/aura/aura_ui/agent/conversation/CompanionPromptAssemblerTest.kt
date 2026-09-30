package com.aura.aura_ui.agent.conversation

import android.content.Context
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.agent.memory.EncryptedMemoryService
import com.aura.aura_ui.agent.memory.MemoryType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CompanionPromptAssemblerTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private fun memory(name: String) = EncryptedMemoryService(EncryptedJsonStore(ctx, name))

    @Test fun `assembled instruction carries the persona sentinel`() = runTest {
        val out = CompanionPromptAssembler(memory("asm_sentinel")).assemble(0L)
        assertTrue(out.contains(Persona.SENTINEL))
    }

    @Test fun `persona prefix precedes the dynamic memory tail`() = runTest {
        val mem = memory("asm_order"); mem.save(MemoryType.USER, "name is Dinesh")
        val out = CompanionPromptAssembler(mem).assemble(0L)
        assertTrue(out.indexOf(Persona.SENTINEL) < out.indexOf("Dinesh"))
    }

    @Test fun `a sensitive memory is excluded from the instruction but reachable via recall`() = runTest {
        val mem = memory("asm_priv")
        mem.save(MemoryType.USER, "the message said: meet at five")  // sensitive (PiiFirewall)
        val out = CompanionPromptAssembler(mem).assemble(0L)
        assertFalse(out.contains("meet at five"))
        assertTrue(mem.recall("meet").isNotEmpty())
    }

    @Test fun `a pending resume offer is surfaced so the model can proactively continue it`() = runTest {
        val out = CompanionPromptAssembler(memory("asm_resume"))
            .assemble(0L, pendingResume = "You have an unfinished task: add AirPods to cart. Want me to continue it?")
        assertTrue(out.contains("add AirPods to cart"))
        assertTrue("must point the model at the resume route", out.contains("resume"))
    }

    @Test fun `no pending resume means no unfinished-task section`() = runTest {
        val out = CompanionPromptAssembler(memory("asm_noresume")).assemble(0L, pendingResume = null)
        assertFalse(out.contains("Unfinished task"))
    }
}
