package com.aura.aura_ui.agent.mcpbridge.client

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CancellationException

/**
 * Real [McpConnector]: builds a transport, connects a fresh [Client], counts tools, and reads
 * the server [instructions]. A 401 (auth) surfaces as [ConnectOutcome.Unauthorized] so the
 * state machine moves to NeedsAuth instead of crashing the agent (fail-closed, parent §4).
 */
class KtorMcpConnector(
    private val factory: McpClientTransportFactory,
    private val secretStore: McpSecretStore,
    private val clientName: String = "aura-agent",
    private val clientVersion: String = "1.0.0",
) : McpConnector {

    /** Exposed so [RemoteMcpToolSource] can reuse the same connected client. */
    var lastClient: Client? = null
        private set

    override suspend fun connect(config: McpServerConfig): ConnectOutcome {
        val token = secretStore.getToken(config.id)
        val client = Client(clientInfo = Implementation(name = clientName, version = clientVersion))
        return try {
            client.connect(factory.transportFor(config, token))
            val tools = client.listTools()?.tools.orEmpty()
            lastClient = client
            ConnectOutcome.Ok(tools.size, McpPayloadGuard.sanitizeInstructions(client.serverInstructions))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (t.isUnauthorized()) ConnectOutcome.Unauthorized
            else ConnectOutcome.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun Throwable.isUnauthorized(): Boolean =
        (message?.contains("401") == true) || (message?.contains("Unauthorized", ignoreCase = true) == true)
}
