package com.aura.mcp.server

import com.aura.mcp.bridge.ContactsBridge
import com.aura.mcp.bridge.DeepLinkBridge
import com.aura.mcp.bridge.FilesBridge
import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.bridge.McpAuditLogger
import com.aura.mcp.bridge.McpScope
import com.aura.mcp.bridge.MediaBridge
import com.aura.mcp.bridge.NoOpAuditLogger
import com.aura.mcp.bridge.NotificationBridge
import com.aura.mcp.bridge.PerceptionBridge
import com.aura.mcp.bridge.ScreenshotBridge
import com.aura.mcp.bridge.SystemIntentBridge
import com.aura.mcp.bridge.TokenPrincipal
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.bridge.BrowserBridge
import com.aura.mcp.bridge.WebSearchBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * In-process bridge between the on-device agent (an MCP **client**) and the
 * on-device MCP **server**, without a socket, TLS, or bearer auth.
 *
 * The MCP Kotlin SDK (0.8.3) ships only WebSocket/SSE transports, so we
 * implement the [Transport] interface as a linked pair: each end's [send]
 * delivers the message to the *other* end's registered `onMessage` handler.
 * The agent connects its [Client] to the [clientTransport]; a dedicated
 * [Server] (built from [McpServerBuilder] with the SAME app bridges as the
 * external SSE server) is connected to the server end.
 *
 * **Why this reuses the security model for free:** the server-end transport
 * delivers every inbound message inside a coroutine carrying an
 * [McpPrincipalElement]. MCP tool handlers are `suspend` lambdas that inherit
 * that context, so `scopedTool`'s `currentMcpPrincipalOrNull()` sees the
 * synthetic full-scope "internal-agent" principal — scope checks pass and the
 * [SensitivePolicy] gate + audit still run, unchanged. No SDK patching.
 */
class InMemoryMcpTransport internal constructor(
    private val deliveryContext: CoroutineContext,
    private val scope: CoroutineScope,
) : Transport {

    /** The opposite end of the pair; [send] hands messages to this peer. */
    internal lateinit var peer: InMemoryMcpTransport

    private var messageHandler: (suspend (JSONRPCMessage) -> Unit)? = null
    private var closeHandler: (() -> Unit)? = null

    @Suppress("unused")
    private var errorHandler: ((Throwable) -> Unit)? = null

    private val closed = AtomicBoolean(false)

    override suspend fun start() {
        // No handshake needed for an in-process pipe; handlers are wired by the
        // owning Client/Server via onMessage() before any traffic flows.
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        if (closed.get()) return
        peer.deliver(message)
    }

    /**
     * Deliver an inbound message to THIS end's consumer, on [scope] within
     * [deliveryContext]. Async launch (rather than inline invocation) prevents
     * request→response re-entrancy from recursing on a single coroutine.
     */
    private fun deliver(message: JSONRPCMessage) {
        val handler = messageHandler ?: return
        scope.launch(deliveryContext) { handler(message) }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeHandler?.invoke()
        if (!peer.closed.get()) peer.close()
    }

    override fun onClose(block: () -> Unit) {
        closeHandler = block
    }

    override fun onError(block: (Throwable) -> Unit) {
        errorHandler = block
    }

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        messageHandler = block
    }
}

/**
 * Handle for a live in-process MCP server bound to the agent. Hold it for the
 * agent session lifetime; [close] tears down the server + transport scope.
 */
class InProcessMcpHandle internal constructor(
    /** Give this to Koog's `McpToolRegistryProvider.fromTransport(...)`. */
    val clientTransport: Transport,
    private val server: Server,
    private val scope: CoroutineScope,
) {
    suspend fun close() {
        runCatching { server.close() }
        scope.cancel()
    }
}

/**
 * Builds a dedicated [Server] (sharing the supplied app bridges with the
 * external SSE server) and connects it to an in-memory transport pair.
 * Returns the client-side transport wrapped in an [InProcessMcpHandle].
 *
 * The single public entry point :app needs — everything `internal`
 * (McpServerBuilder, McpPrincipalElement, scope wiring) stays in this module.
 */
object InProcessMcpServer {

    private const val SERVER_NAME = "aura-inprocess"
    private const val SERVER_VERSION = "1.0.0"

    /** The trusted identity for first-party on-device agent calls. */
    private val internalPrincipal = TokenPrincipal(
        tokenId = "internal-agent",
        scopes = setOf(McpScope.READ, McpScope.WRITE),
        label = "on-device-agent",
    )

    suspend fun connect(
        deviceBridge: DeviceBridge,
        screenshotBridge: ScreenshotBridge,
        uiTreeBridge: UiTreeBridge,
        perceptionBridge: PerceptionBridge,
        webSearchBridge: WebSearchBridge,
        deepLinkBridge: DeepLinkBridge,
        notificationBridge: NotificationBridge,
        mediaBridge: MediaBridge,
        systemIntentBridge: SystemIntentBridge,
        contactsBridge: ContactsBridge? = null,
        filesBridge: FilesBridge? = null,
        browserBridge: BrowserBridge? = null,
        auditLogger: McpAuditLogger = NoOpAuditLogger,
        /** Host app package — its overlay's accessibility events are ignored while settling (E1). */
        ownPackageName: String? = null,
        /** Spec 2026-07-31 — control lock; forwarded to [McpServerBuilder.build]. */
        pausedByHuman: (() -> Boolean)? = null,
        /** Forwarded to [McpServerBuilder.build]. */
        readScreenImage: () -> Boolean = { false },
        /** Forwarded to [McpServerBuilder.build]. */
        trailSink: ToolTrailSink = ToolTrailSink.NOOP,
    ): InProcessMcpHandle {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val server = McpServerBuilder.build(
            serverName = SERVER_NAME,
            version = SERVER_VERSION,
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
            ownPackageName = ownPackageName,
            pausedByHuman = pausedByHuman,
            readScreenImage = readScreenImage,
            trailSink = trailSink,
        )

        // Client end runs in a plain context; server end carries the principal
        // so scopedTool() attributes calls and passes scope while SensitivePolicy
        // still hard-blocks sensitive actions.
        val clientTransport = InMemoryMcpTransport(EmptyCoroutineContext, scope)
        val serverTransport = InMemoryMcpTransport(McpPrincipalElement(internalPrincipal), scope)
        clientTransport.peer = serverTransport
        serverTransport.peer = clientTransport

        // Bind the server to its end. The SDK registers onMessage/onClose then
        // calls start(); returns once listening (does not block on the client).
        server.createSession(serverTransport)

        return InProcessMcpHandle(clientTransport, server, scope)
    }
}
