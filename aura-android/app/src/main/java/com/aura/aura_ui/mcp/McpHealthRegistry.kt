package com.aura.aura_ui.mcp

import android.content.Context
import android.content.Intent
import android.os.Build
import com.aura.aura_ui.services.AssistantForegroundService
import com.aura.mcp.McpServerHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-global mirror of [McpServerHealth].
 *
 * Why a registry and not just reading from [com.aura.mcp.McpServerController]?
 *   - The controller is owned by [AssistantForegroundService], which can die.
 *     When it dies the controller instance is gone but its `health` StateFlow
 *     stops being collected — UI loses its signal at the worst possible time.
 *   - Compose screens live in [com.aura.aura_ui.MainActivity], a different
 *     foreground concern. They have no clean way to reach into another
 *     service's private fields.
 *   - Same pattern is already used by `McpAuditRegistry.INSTANCE` for the
 *     audit log → keeps the architecture consistent.
 *
 * The service writes; screens read. When the service dies it publishes
 * [McpServerHealth.Stopped] in onDestroy so the UI is never lying.
 */
object McpHealthRegistry {

    private val _health = MutableStateFlow<McpServerHealth>(McpServerHealth.Stopped)
    val health: StateFlow<McpServerHealth> = _health.asStateFlow()

    private val _webRtcApprovalRequest = MutableStateFlow<com.aura.mcp.WebRtcApprovalRequest?>(null)
    val webRtcApprovalRequest: StateFlow<com.aura.mcp.WebRtcApprovalRequest?> = _webRtcApprovalRequest.asStateFlow()

    fun publish(state: McpServerHealth) {
        _health.value = state
    }

    fun publishApprovalRequest(request: com.aura.mcp.WebRtcApprovalRequest?) {
        _webRtcApprovalRequest.value = request
    }

    // ── Control intents — UI calls these; the service handles them ──────────
    // Keeping the wiring on this object means screens don't have to know
    // anything about Intent / startForegroundService boilerplate.

    fun requestStartWebRtc(context: Context) {
        sendIntent(context, AssistantForegroundService.ACTION_MCP_START_WEBRTC)
    }

    fun requestStop(context: Context) {
        sendIntent(context, AssistantForegroundService.ACTION_MCP_STOP)
    }

    fun requestRestart(context: Context) {
        sendIntent(context, AssistantForegroundService.ACTION_MCP_RESTART)
    }

    private fun sendIntent(context: Context, action: String) {
        val intent = Intent(context, AssistantForegroundService::class.java).apply {
            this.action = action
        }
        com.aura.aura_ui.compat.ForegroundServiceCompat.startSafely(context, intent)
    }
}
