package com.aura.aura_ui.agent.mcpbridge.client

sealed interface McpConnectionState {
    data object Disabled : McpConnectionState
    data object Connecting : McpConnectionState
    data class Connected(val toolCount: Int, val serverInstructions: String?) : McpConnectionState
    data class NeedsAuth(val reason: String) : McpConnectionState
    data class Failed(val reason: String, val attempt: Int) : McpConnectionState
}

sealed interface ConnectOutcome {
    data class Ok(val toolCount: Int, val instructions: String?) : ConnectOutcome
    data object Unauthorized : ConnectOutcome
    data class Error(val reason: String) : ConnectOutcome
}

interface McpConnector {
    suspend fun connect(config: McpServerConfig): ConnectOutcome
}
