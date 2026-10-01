package com.aura.mcp.server

import com.aura.mcp.bridge.TokenPrincipal
import com.aura.mcp.bridge.WebRtcTransport
import io.modelcontextprotocol.kotlin.sdk.shared.ReadBuffer
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.shared.serializeMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bridges the WebRTC DataChannel to the MCP SDK's [Transport] interface.
 *
 * **Framing.** The DataChannel delivers arbitrary byte chunks — a single send
 * may split or coalesce JSON-RPC frames, and large frames (base64 screenshots)
 * are chunked by [WebRtcTransport.sendFrame]. Inbound bytes are therefore fed
 * into the SDK's [ReadBuffer], which reassembles newline-delimited frames and
 * deserializes each with the SDK's polymorphic `McpJson` — the exact framing
 * the SDK's own stdio transport uses. Outbound messages go through
 * [serializeMessage] (JSON + trailing newline) and are chunked on the way out.
 *
 * **Authorization.** Each inbound message is dispatched inside a coroutine
 * carrying an [McpPrincipalElement]. Without it, `scopedTool`'s
 * `currentMcpPrincipalOrNull()` would return null and the per-tool scope check
 * would be skipped entirely (fail-open). The supplied [principal] is the
 * identity the device approved during the WebRTC handshake — approval grants
 * full device control, so it carries READ+WRITE. The [SensitivePolicy] gate and
 * audit logging then run unchanged, just like the in-process path.
 */
class WebRtcMcpTransport(
    private val webRtcTransport: WebRtcTransport,
    private val scope: CoroutineScope,
    private val principal: TokenPrincipal,
) : Transport {

    @Volatile private var messageHandler: (suspend (JSONRPCMessage) -> Unit)? = null
    private var closeHandler: (() -> Unit)? = null
    private var errorHandler: ((Throwable) -> Unit)? = null

    /**
     * SDK newline-framer, guarded by itself: bytes arrive on the DataChannel thread while the
     * SDK registers its handler on another.
     *
     * - Why frames stay buffered until [onMessage]: the session is bound the moment the PC is
     *   approved, and the PC sends `initialize` right after. A frame read out before the SDK
     *   has a handler would be lost, and the client would wait forever for its reply.
     */
    private val readBuffer = ReadBuffer()

    init {
        webRtcTransport.onBytesReceived = { bytes ->
            synchronized(readBuffer) { readBuffer.append(bytes) }
            drain()
        }

        webRtcTransport.onDisconnected = {
            synchronized(readBuffer) { readBuffer.clear() }
            closeHandler?.invoke()
        }
    }

    override suspend fun start() {
        // Signaling/handshake is owned by WebRtcTransport; nothing to do here.
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        try {
            webRtcTransport.sendFrame(serializeMessage(message).toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            errorHandler?.invoke(e)
        }
    }

    override suspend fun close() {
        closeHandler?.invoke()
    }

    override fun onClose(block: () -> Unit) {
        closeHandler = block
    }

    override fun onError(block: (Throwable) -> Unit) {
        errorHandler = block
    }

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        messageHandler = block
        drain()
    }

    /** Dispatches every complete frame, each in a coroutine carrying [principal]. */
    private fun drain() {
        val handler = messageHandler ?: return
        while (true) {
            val message = try {
                synchronized(readBuffer) { readBuffer.readMessage() }
            } catch (e: Exception) {
                errorHandler?.invoke(e)
                return
            } ?: return
            scope.launch(McpPrincipalElement(principal) + Dispatchers.Default) { handler(message) }
        }
    }
}
