package com.aura.aura_ui.mcp

import com.aura.mcp.bridge.ClientInfo
import com.aura.mcp.bridge.ConnectionTracker
import java.util.UUID

/**
 * App-side [ConnectionTracker] — translates server-side connection lifecycle
 * callbacks into entries on [McpConnectionRegistry]. The registry is what
 * the MCP Center UI subscribes to, so the user can see exactly who is
 * connected (client name, version, host, transport).
 *
 * Session keys are random UUID prefixes — opaque to the server, used only
 * to identify a specific session when the user requests a disconnect.
 */
class AppConnectionTracker : ConnectionTracker {

    override fun onConnect(info: ClientInfo): String {
        val key = UUID.randomUUID().toString().substringBefore('-')
        val now = System.currentTimeMillis()
        McpConnectionRegistry.add(
            McpConnectionRegistry.Client(
                sessionKey = key,
                tokenId = info.tokenId,
                agentLabel = info.agentLabel,
                remoteAddr = info.remoteAddr,
                connectedAtMillis = now,
                lastActivityMillis = now,
                clientVersion = info.clientVersion,
                host = info.host,
                platform = info.platform,
                transport = info.transport,
            ),
        )
        com.aura.aura_ui.telemetry.AuraAnalytics.mcpSessionStarted(info.platform)
        return key
    }

    override fun onActivity(sessionKey: String) {
        McpConnectionRegistry.touch(sessionKey)
    }

    override fun onDisconnect(sessionKey: String) {
        McpConnectionRegistry.remove(sessionKey)
        com.aura.aura_ui.telemetry.AuraAnalytics.mcpSessionEnded()
    }
}
