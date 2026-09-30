package com.aura.aura_ui.agent.mcpbridge.client

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.mcpSseTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpWebSocketTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport

/** Maps an [McpServerConfig] (+ resolved token) to a connected-ready MCP [Transport]. */
class McpClientTransportFactory(private val httpClient: HttpClient) {

    fun transportTypeOf(config: McpServerConfig): McpTransportType = config.transport

    /** The request-builder that injects bearer auth (the OAuth seam; SDK has no OAuth helper). */
    fun authBuilder(token: String?): HttpRequestBuilder.() -> Unit = {
        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
    }

    fun transportFor(config: McpServerConfig, token: String?): Transport {
        val rb = authBuilder(token)
        return when (config.transport) {
            McpTransportType.STREAMABLE_HTTP -> httpClient.mcpStreamableHttpTransport(config.url, requestBuilder = rb)
            McpTransportType.SSE -> httpClient.mcpSseTransport(config.url, requestBuilder = rb)
            McpTransportType.WEBSOCKET -> httpClient.mcpWebSocketTransport(config.url, requestBuilder = rb)
        }
    }
}
