package com.aura.aura_ui.agent.mcpbridge.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-server connection state machine. Pure logic over an injected [McpConnector] so it
 * unit-tests without a network. Reconnect/backoff scheduling is the caller's concern;
 * this owns only the legal state transitions (parent spec §3.2).
 */
class RemoteMcpConnection(
    val config: McpServerConfig,
    private val connector: McpConnector,
    private val maxAttempts: Int = 4,
) {
    private val _state = MutableStateFlow<McpConnectionState>(McpConnectionState.Disabled)
    val state: StateFlow<McpConnectionState> = _state.asStateFlow()

    private var attempt = 0

    suspend fun enable() = attemptConnect()

    suspend fun disable() {
        attempt = 0
        _state.value = McpConnectionState.Disabled
    }

    suspend fun onTokenSaved() = attemptConnect()

    private suspend fun attemptConnect() {
        _state.value = McpConnectionState.Connecting
        when (val out = connector.connect(config)) {
            is ConnectOutcome.Ok -> {
                attempt = 0
                _state.value = McpConnectionState.Connected(out.toolCount, out.instructions)
            }
            ConnectOutcome.Unauthorized -> {
                attempt = 0
                _state.value = McpConnectionState.NeedsAuth("Server requires authentication")
            }
            is ConnectOutcome.Error -> {
                attempt = (attempt + 1).coerceAtMost(maxAttempts)
                _state.value = McpConnectionState.Failed(out.reason, attempt)
            }
        }
    }
}
