package com.aura.aura_ui.presentation.screens

import android.content.Context
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CompanionViewModelTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Test fun `reflects no key and classic mode by default`() {
        ProviderKeyStore(ctx).clearKey(LlmEndpointCatalog.GEMINI_ID)
        val vm = CompanionViewModel(ctx)
        assertFalse(vm.state.value.hasKey)
        // Exactly one mode is always on; without a key that mode is TTS/STT.
        assertFalse(vm.state.value.liveMode)
    }

    @Test fun `saveKey persists to the gemini slot and updates hasKey`() {
        val vm = CompanionViewModel(ctx)
        vm.saveKey("AIza-xyz")
        assertTrue(vm.state.value.hasKey)
        assertTrue(ProviderKeyStore(ctx).isConfigured(LlmEndpointCatalog.GEMINI_ID))
    }

    @Test fun `setLiveMode requires a key`() {
        ProviderKeyStore(ctx).clearKey(LlmEndpointCatalog.GEMINI_ID)
        val vm = CompanionViewModel(ctx)
        // No key ⇒ Gemini Live cannot engage; stays in classic TTS/STT.
        vm.setLiveMode(true)
        assertFalse(vm.state.value.liveMode)
    }

    @Test fun `setLiveMode engages live once a key exists`() {
        val vm = CompanionViewModel(ctx)
        vm.saveKey("AIza-xyz")
        vm.setLiveMode(true)
        assertTrue(vm.state.value.liveMode)
        // Turning it back off returns to classic mode (one mode is always active).
        vm.setLiveMode(false)
        assertFalse(vm.state.value.liveMode)
    }
}
