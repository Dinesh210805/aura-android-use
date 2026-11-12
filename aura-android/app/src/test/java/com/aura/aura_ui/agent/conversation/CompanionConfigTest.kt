package com.aura.aura_ui.agent.conversation

import android.content.Context
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CompanionConfigTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Test fun `companion is disabled by default`() {
        assertFalse(CompanionConfig(ctx).enabled)
    }

    @Test fun `null byok key when gemini is unconfigured`() {
        ProviderKeyStore(ctx).clearKey(LlmEndpointCatalog.GEMINI_ID)
        assertNull(CompanionConfig(ctx).byokKey())
    }

    @Test fun `byok key reads from the gemini provider slot`() {
        ProviderKeyStore(ctx).setKey(LlmEndpointCatalog.GEMINI_ID, "AIza-test")
        assertEquals("AIza-test", CompanionConfig(ctx).byokKey())
    }

    @Test fun `enabled toggle round-trips`() {
        val c = CompanionConfig(ctx); c.enabled = true
        assertTrue(CompanionConfig(ctx).enabled)
    }

    @Test fun `default voice is Achird`() {
        assertEquals("Achird", CompanionConfig(ctx).voice())
    }
}
