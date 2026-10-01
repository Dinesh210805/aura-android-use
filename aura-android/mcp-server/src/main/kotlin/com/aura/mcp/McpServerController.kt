package com.aura.mcp

import android.util.Log
import com.aura.mcp.bridge.ConnectionTracker
import com.aura.mcp.bridge.ContactsBridge
import com.aura.mcp.bridge.DeepLinkBridge
import com.aura.mcp.bridge.FilesBridge
import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.bridge.McpAuditLogger
import com.aura.mcp.bridge.McpScope
import com.aura.mcp.bridge.MediaBridge
import com.aura.mcp.bridge.LearningsGateway
import com.aura.mcp.bridge.NoOpAuditLogger
import com.aura.mcp.bridge.NoOpLearningsGateway
import com.aura.mcp.bridge.NoOpConnectionTracker
import com.aura.mcp.bridge.NoOpSessionLogSink
import com.aura.mcp.bridge.NoOpToolPhaseSink
import com.aura.mcp.bridge.NotificationBridge
import com.aura.mcp.bridge.PerceptionBridge
import com.aura.mcp.bridge.ScreenshotBridge
import com.aura.mcp.bridge.SessionLogSink
import com.aura.mcp.bridge.SystemIntentBridge
import com.aura.mcp.bridge.TokenPrincipal
import com.aura.mcp.bridge.ToolPhaseSink
import com.aura.mcp.bridge.TrustedClientsStore
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.bridge.WebRtcTransport
import com.aura.mcp.bridge.BrowserBridge
import com.aura.mcp.bridge.WebSearchBridge
import com.aura.mcp.server.McpServerBuilder
import com.aura.mcp.server.WebRtcMcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

data class WebRtcApprovalRequest(
    val version: String,
    val clientName: String,
    val host: String? = null,
    val platform: String? = null,
    /** 6-digit code the PC also shows. The user must approve only when both match. */
    val verificationCode: String,
    val onApprove: () -> Unit,
    val onDeny: () -> Unit
)

/**
 * Public lifecycle handle for the on-device MCP server.
 *
 * The server is reached over the **local network only**: the `aura-mcp-connect` bridge on a PC
 * finds the phone on the same Wi-Fi (or hotspot, or private VPN such as Tailscale), pairs once
 * with the PIN shown here, and MCP JSON-RPC then flows over a DTLS-encrypted WebRTC DataChannel.
 * A new PC needs the user's approval on the phone; its token is remembered so reconnects are
 * silent. See [WebRtcTransport] for the protocol.
 *
 * Each approved client is bound to a full-scope [TokenPrincipal] so the
 * per-tool scope check, the sensitive-action policy gate, and audit logging in
 * `scopedTool(...)` all apply to remote calls.
 */
class McpServerController(
    private val deviceBridge: DeviceBridge,
    private val screenshotBridge: ScreenshotBridge,
    private val uiTreeBridge: UiTreeBridge,
    private val perceptionBridge: PerceptionBridge,
    private val webSearchBridge: WebSearchBridge,
    private val deepLinkBridge: DeepLinkBridge,
    private val notificationBridge: NotificationBridge,
    private val mediaBridge: MediaBridge,
    private val systemIntentBridge: SystemIntentBridge,
    private val contactsBridge: ContactsBridge? = null,
    private val filesBridge: FilesBridge? = null,
    private val browserBridge: BrowserBridge? = null,
    private val auditLogger: McpAuditLogger = NoOpAuditLogger,
    private val toolPhaseSink: ToolPhaseSink = NoOpToolPhaseSink,
    private val sessionLogSink: SessionLogSink = NoOpSessionLogSink,
    /** Spec 2026-07-17 — two-way shared memory; NoOp = pre-seam behavior. */
    private val learningsGateway: LearningsGateway = NoOpLearningsGateway,
    /**
     * Spec 2026-07-17 — device skills exposed as MCP prompts. Evaluated at each
     * server build, so new user skills appear on the next server (re)start.
     */
    private val promptSkillsProvider: () -> List<com.aura.mcp.server.PromptSkill> = { emptyList() },
    private val connectionTracker: ConnectionTracker = NoOpConnectionTracker,
    /** Trust ledger for WebRTC clients (token → metadata). */
    private val trustedClients: TrustedClientsStore,
    private val serverName: String = "aura-on-device",
    private val version: String = "0.8.0-phase8",
    /** Host app package — its overlay's accessibility events are ignored while settling (E1). */
    private val ownPackageName: String? = null,
    /** Spec 2026-07-31 — control lock; forwarded to [McpServerBuilder.build]. */
    private val pausedByHuman: (() -> Boolean)? = null,
    /** Forwarded to [McpServerBuilder.build]. */
    private val readScreenImage: () -> Boolean = { false },
    /**
     * Spec 2026-07-31 — *arms* that lock. Fires on every tool call this server serves.
     *
     * This controller builds the server remote clients talk to, which makes it the only
     * place that can observe "a PC is driving the phone right now" — a remote client has
     * no run lifecycle to announce, so its call traffic is the whole signal.
     */
    private val onToolDispatched: (() -> Unit)? = null,
) {

    /**
     * The live WebRTC transport + its coroutine scope, if a WebRTC session is
     * running. Held so stop()/restart can tear it down — otherwise a restart
     * leaves a zombie peer fighting the new one for the same signaling path.
     */
    private val webRtcTransportRef = AtomicReference<WebRtcTransport?>(null)
    private val webRtcScopeRef = AtomicReference<CoroutineScope?>(null)

    private val _health = MutableStateFlow<McpServerHealth>(McpServerHealth.Stopped)
    val webRtcApprovalRequest = MutableStateFlow<WebRtcApprovalRequest?>(null)

    /**
     * Single source of truth for server runtime state. The foreground service,
     * the status chip, and any in-app status badge all read from this.
     */
    val health: StateFlow<McpServerHealth> = _health.asStateFlow()

    val isRunning: Boolean
        get() = _health.value is McpServerHealth.Listening

    /**
     * Starts the LAN MCP server and returns the pairing PIN the UI shows.
     *
     * - Contract: idempotent. A second call tears the previous transport down first, so a
     *   restart never has two transports fighting over the signaling port. [health] then tracks
     *   PIN rotations and network changes.
     * - Fails: when the signaling port can't be bound, [health] becomes [McpServerHealth.Crashed]
     *   and this returns null.
     */
    fun startWebRtc(context: android.content.Context): String? {
        tearDownWebRtc()

        val webRtcTransport = WebRtcTransport(context, connectionTracker, trustedClients, auditLogger)

        webRtcTransport.onApprovalRequested = { version, clientName, host, platform, verificationCode, callback ->
            webRtcApprovalRequest.value = WebRtcApprovalRequest(
                version = version,
                clientName = clientName,
                host = host,
                platform = platform,
                verificationCode = verificationCode,
                onApprove = {
                    callback(true)
                    webRtcApprovalRequest.value = null
                },
                onDeny = {
                    callback(false)
                    webRtcApprovalRequest.value = null
                }
            )
        }

        // A new MCP session is bound per approved client (first connect and every
        // reconnect). Each `aura-mcp` process is its own MCP client with its own
        // `initialize` handshake, so it needs a fresh SDK session — the previous
        // client's session is closed when its peer drops and must never be reused,
        // or the new client's initialize goes unanswered.
        webRtcTransport.onNewClient = { client -> bindFreshMcpSession(webRtcTransport, client) }
        webRtcTransport.onStatusChanged = { status ->
            if (webRtcTransportRef.get() === webRtcTransport) _health.value = listening(status)
        }

        val status = try {
            webRtcTransport.startListening()
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the LAN MCP server", e)
            runCatching { webRtcTransport.close() }
            _health.value = McpServerHealth.Crashed("Couldn't open the local network port: ${e.message}", e)
            return null
        }

        webRtcTransportRef.set(webRtcTransport)
        _health.value = listening(status)
        Log.i(TAG, "Started the LAN MCP server on port ${status.port}")
        return status.pin
    }

    private fun listening(status: WebRtcTransport.LanStatus) = McpServerHealth.Listening(
        port = status.port,
        host = "LAN",
        reachableAddresses = status.addresses,
        webRtcPin = status.pin,
    )

    /**
     * Build and bind a fresh MCP SDK session for the [client] just approved on
     * [webRtcTransport]. Cancels the previous client's session scope first, so at
     * most one session is live at a time and a reconnect always gets a clean
     * server (fresh `initialize` handshake, fresh tool registration), mirroring
     * the exact construction the very first connection uses.
     */
    private fun bindFreshMcpSession(webRtcTransport: WebRtcTransport, client: WebRtcTransport.ApprovedClient) {
        // Retire the previous client's session before standing up the new one.
        webRtcScopeRef.getAndSet(null)?.let { prev -> runCatching { prev.cancel() } }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The device-approved client is granted full device control (the approval
        // dialog is the trust boundary), so it carries READ+WRITE. Binding this
        // principal makes scopedTool's scope check, the sensitive-action policy gate
        // and audit logging apply to remote calls, attributed to this PC's token id
        // — without it, the null-principal path would skip scope enforcement.
        val webRtcPrincipal = TokenPrincipal(
            tokenId = client.tokenId,
            scopes = setOf(McpScope.READ, McpScope.WRITE),
            label = client.name,
        )
        val mcpTransport = WebRtcMcpTransport(webRtcTransport, scope, webRtcPrincipal)

        val mcpServer = McpServerBuilder.build(
            serverName = serverName,
            version = version,
            deviceBridge = deviceBridge,
            screenshotBridge = screenshotBridge,
            uiTreeBridge = uiTreeBridge,
            perceptionBridge = perceptionBridge,
            webSearchBridge = webSearchBridge,
            deepLinkBridge = deepLinkBridge,
            notificationBridge = notificationBridge,
            mediaBridge = mediaBridge,
            systemIntentBridge = systemIntentBridge,
            contactsBridge = contactsBridge,
            filesBridge = filesBridge,
            browserBridge = browserBridge,
            auditLogger = auditLogger,
            toolPhaseSink = toolPhaseSink,
            sessionLogSink = sessionLogSink,
            learningsGateway = learningsGateway,
            promptSkills = runCatching { promptSkillsProvider() }.getOrDefault(emptyList()),
            ownPackageName = ownPackageName,
            pausedByHuman = pausedByHuman,
            readScreenImage = readScreenImage,
            onToolDispatched = onToolDispatched,
        )

        scope.launch { mcpServer.createSession(mcpTransport) }
        webRtcScopeRef.set(scope)
    }

    /**
     * Close the active WebRTC transport (if any) and cancel its scope. Frees the
     * signaling port, closes the peer connection, and unregisters the client
     * from the connection tracker. Idempotent.
     */
    private fun tearDownWebRtc() {
        webRtcTransportRef.getAndSet(null)?.let { transport ->
            runCatching { transport.close() }.onFailure { Log.w(TAG, "Error closing WebRTC transport", it) }
        }
        webRtcScopeRef.getAndSet(null)?.let { scope ->
            runCatching { scope.cancel() }
        }
    }

    /**
     * Disconnect the connected WebRTC client on user request. Closes the peer
     * and stops the server; the user re-arms it with Start server (a
     * previously trusted client auto-approves on its next connect). The
     * [sessionKey] is accepted for parity with the UI call site but the WebRTC
     * model only has one peer at a time.
     */
    fun disconnectSession(sessionKey: String) {
        if (webRtcTransportRef.get() != null) {
            Log.i(TAG, "Disconnecting WebRTC client $sessionKey on user request")
            tearDownWebRtc()
            _health.value = McpServerHealth.Stopped
        } else {
            Log.w(TAG, "disconnectSession: no active WebRTC client (already gone?)")
        }
    }

    /** Stop the WebRTC server and tear down the active client, if any. */
    fun stop() {
        tearDownWebRtc()
        _health.value = McpServerHealth.Stopped
        Log.i(TAG, "MCP (WebRTC) server stopped")
    }

    companion object {
        private const val TAG = "McpServerController"
    }
}
