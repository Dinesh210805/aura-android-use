package com.aura.aura_ui.mcp

import android.content.Context
import android.content.Intent
import android.os.Build
import com.aura.aura_ui.services.AssistantForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-global registry of currently-connected MCP clients.
 *
 * The server-side [com.aura.mcp.bridge.ConnectionTracker] hook writes here
 * via [AppConnectionTracker]; the MCP Center screen reads from here.
 * Disconnect requests are dispatched as an intent to
 * [AssistantForegroundService] which calls
 * [com.aura.mcp.McpServerController.disconnectSession] with the session key.
 */
object McpConnectionRegistry {

    data class Client(
        val sessionKey: String,
        val tokenId: String,
        val agentLabel: String,
        val remoteAddr: String?,
        val connectedAtMillis: Long,
        val lastActivityMillis: Long,
        val clientVersion: String? = null,
        val host: String? = null,
        val platform: String? = null,
        val transport: String = "WebRTC",
    )

    private val _clients = MutableStateFlow<List<Client>>(emptyList())
    val clients: StateFlow<List<Client>> = _clients.asStateFlow()

    fun add(client: Client) {
        _clients.update { current -> current + client }
    }

    fun remove(sessionKey: String) {
        _clients.update { current -> current.filterNot { it.sessionKey == sessionKey } }
    }

    fun touch(sessionKey: String) {
        val now = System.currentTimeMillis()
        _clients.update { current ->
            current.map { if (it.sessionKey == sessionKey) it.copy(lastActivityMillis = now) else it }
        }
    }

    fun clearAll() {
        _clients.value = emptyList()
    }

    /** UI → service: kick this one session. The bearer token stays valid. */
    fun requestDisconnect(context: Context, sessionKey: String) {
        val intent = Intent(context, AssistantForegroundService::class.java).apply {
            this.action = AssistantForegroundService.ACTION_MCP_DISCONNECT
            putExtra(AssistantForegroundService.EXTRA_SESSION_KEY, sessionKey)
        }
        com.aura.aura_ui.compat.ForegroundServiceCompat.startSafely(context, intent)
    }
}
