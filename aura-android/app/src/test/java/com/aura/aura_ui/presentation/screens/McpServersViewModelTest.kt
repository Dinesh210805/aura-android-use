package com.aura.aura_ui.presentation.screens

import android.content.Context
import com.aura.aura_ui.agent.mcpbridge.client.McpSecretStore
import com.aura.aura_ui.agent.mcpbridge.client.McpServerStore
import com.aura.aura_ui.agent.mcpbridge.client.McpTransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class McpServersViewModelTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private fun vm() = McpServersViewModel(McpServerStore(ctx), McpSecretStore(ctx))

    @Test fun `addServer with https url succeeds and lists it`() {
        val vm = vm()
        val ok = vm.addServer("GitHub", "https://mcp.github.com", McpTransportType.STREAMABLE_HTTP)
        assertTrue(ok)
        assertEquals(1, vm.state.value.servers.size)
    }

    @Test fun `addServer with http url fails and sets error`() {
        val vm = vm()
        val ok = vm.addServer("Bad", "http://mcp.evil.com", McpTransportType.STREAMABLE_HTTP)
        assertFalse(ok)
        assertTrue(vm.state.value.addError != null)
        assertTrue(vm.state.value.servers.isEmpty())
    }

    @Test fun `delete removes the server`() {
        val vm = vm()
        vm.addServer("GitHub", "https://mcp.github.com", McpTransportType.STREAMABLE_HTTP)
        val id = vm.state.value.servers.first().config.id
        vm.delete(id)
        assertTrue(vm.state.value.servers.isEmpty())
    }
}
