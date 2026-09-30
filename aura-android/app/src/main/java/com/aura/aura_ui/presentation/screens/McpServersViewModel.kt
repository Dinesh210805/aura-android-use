package com.aura.aura_ui.presentation.screens

import androidx.lifecycle.ViewModel
import com.aura.aura_ui.agent.mcpbridge.client.McpConnectionState
import com.aura.aura_ui.agent.mcpbridge.client.McpSecretStore
import com.aura.aura_ui.agent.mcpbridge.client.McpServerConfig
import com.aura.aura_ui.agent.mcpbridge.client.McpServerStore
import com.aura.aura_ui.agent.mcpbridge.client.McpTransportType
import com.aura.aura_ui.agent.mcpbridge.client.McpUrlValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerRow(val config: McpServerConfig, val state: McpConnectionState)
data class McpServersUiState(val servers: List<ServerRow> = emptyList(), val addError: String? = null)

class McpServersViewModel(
    private val store: McpServerStore,
    private val secretStore: McpSecretStore,
) : ViewModel() {
    private val _state = MutableStateFlow(McpServersUiState())
    val state: StateFlow<McpServersUiState> = _state.asStateFlow()

    init { refresh() }

    fun addServer(name: String, url: String, transport: McpTransportType): Boolean {
        val validation = McpUrlValidator.validate(url)
        if (validation.isFailure) {
            _state.value = _state.value.copy(addError = validation.exceptionOrNull()?.message ?: "Invalid URL")
            return false
        }
        val id = slug(name).ifBlank { slug(url) }
        store.upsert(McpServerConfig(id = id, displayName = name.trim(), url = url.trim(), transport = transport))
        _state.value = _state.value.copy(addError = null)
        refresh()
        return true
    }

    fun toggle(id: String, enabled: Boolean) { store.setEnabled(id, enabled); refresh() }

    fun delete(id: String) { store.delete(id); secretStore.clearToken(id); refresh() }

    private fun refresh() {
        _state.value = _state.value.copy(
            servers = store.list().map { ServerRow(it, McpConnectionState.Disabled) },
        )
    }

    private fun slug(s: String) = s.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
