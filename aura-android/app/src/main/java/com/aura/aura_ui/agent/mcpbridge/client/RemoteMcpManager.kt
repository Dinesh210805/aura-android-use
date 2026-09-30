package com.aura.aura_ui.agent.mcpbridge.client

import com.aura.aura_ui.agent.mcpbridge.McpToolSource

/**
 * Orchestrates the enabled remote servers for one agent run: connects them, exposes a composite
 * tool source (or null when none connected — preserving today's behavior), and assembles their
 * instructions for the dynamic prompt tail. Fail-closed: a server that does not reach Connected
 * simply contributes nothing.
 */
class RemoteMcpManager private constructor(
    private val store: McpServerStore?,
    private val secretStore: McpSecretStore?,
    private val connector: McpConnector?,
) {
    suspend fun connectEnabled(): List<RemoteMcpConnection> {
        val cfgs = store?.list()?.filter { it.enabled } ?: return emptyList()
        val conn = connector ?: return emptyList()
        return cfgs.map { RemoteMcpConnection(it, conn).also { c -> c.enable() } }
    }

    fun connectedSources(
        connections: List<RemoteMcpConnection>,
        inProcess: McpToolSource,
    ): CompositeMcpToolSource? {
        val connected = connections.filter { it.state.value is McpConnectionState.Connected }
        if (connected.isEmpty()) return null
        val remotes = connected.mapNotNull { c ->
            (connector as? KtorMcpConnector)?.lastClient?.let { RemoteMcpToolSource(it, c.config.id) }
        }
        if (remotes.isEmpty()) return null
        return CompositeMcpToolSource(listOf(inProcess) + remotes)
    }

    fun connectedInstructions(connections: List<RemoteMcpConnection>): String? =
        McpInstructionsAssembler.assemble(
            connections.mapNotNull { it.state.value as? McpConnectionState.Connected }
        )

    companion object {
        fun forApp(store: McpServerStore, secretStore: McpSecretStore, connector: McpConnector) =
            RemoteMcpManager(store, secretStore, connector)
        fun forTest(connections: List<RemoteMcpConnection>) = RemoteMcpManager(null, null, null)
    }
}
