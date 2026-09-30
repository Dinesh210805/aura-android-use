package com.aura.mcp.bridge

import android.content.Context
import android.util.Log
import com.aura.mcp.lan.ConnectAuthorizer
import com.aura.mcp.lan.LanNetwork
import com.aura.mcp.lan.LanPolicy
import com.aura.mcp.lan.LanSignalingServer
import com.aura.mcp.lan.PinGate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The phone end of the PC link: a WebRTC DataChannel that carries MCP, set up over the local
 * network only.
 *
 * How a PC connects:
 * 1. It finds the phone ([LanNetwork.advertise], or its saved address, or a subnet scan) and
 *    posts its offer to [LanSignalingServer] with either the pairing PIN or a known token hash
 *    ([ConnectAuthorizer]).
 * 2. [answerOffer] answers with every ICE candidate included, so there is no trickle and no
 *    third-party server. There is no STUN or TURN, mobile-data interfaces are ignored, and
 *    [LanPolicy.filterSdp] strips any non-LAN candidate from both offer and answer.
 * 3. On the DataChannel, the pairing handshake runs ([handleControlMessage]); MCP bytes flow only
 *    after it approves.
 *
 * - Contract: one PC at a time. A new authorised offer retires the current client and binds a
 *   fresh MCP session ([onNewClient]).
 * - Reads/Writes: prefs `aura_prefs` key `webrtc_device_id` (stable id the bridge looks for);
 *   pairing tokens via [TrustedClientsStore] (or the legacy prefs set `webrtc_trusted_clients`).
 * - Fails: if no signaling port can be bound, [startListening] throws and the controller reports
 *   the server as crashed.
 */
class WebRtcTransport(
    private val context: Context,
    private val connectionTracker: ConnectionTracker? = null,
    /** Trust ledger (token → metadata). Null falls back to the legacy prefs set. */
    private val trustedClients: TrustedClientsStore? = null,
    /** Connection lifecycle events (approved / auto / denied / disconnected). */
    private val auditLogger: McpAuditLogger? = null,
) {
    private val TAG = "WebRtcTransport"

    private val peerConnectionFactory: PeerConnectionFactory
    // Written on the signaling-server thread, read on WebRTC callback threads.
    @Volatile private var peerConnection: PeerConnection? = null
    @Volatile private var dataChannel: DataChannel? = null
    @Volatile private var sessionKey: String? = null

    @Volatile private var closed = false

    var onBytesReceived: ((ByteArray) -> Unit)? = null
    var onConnectionStateChange: ((Boolean) -> Unit)? = null

    /** What the MCP Center shows: the pairing PIN, the signaling port and the phone's LAN addresses. */
    data class LanStatus(val pin: String, val port: Int, val addresses: List<String>)

    /** Fires whenever the PIN, port or addresses change. Any thread. */
    var onStatusChanged: ((LanStatus) -> Unit)? = null

    /**
     * Bumped each time a fresh [PeerConnection] is built for a new client. Every
     * [PeerConnection.Observer] captures the generation it was created under and ignores its
     * callbacks once superseded, so a previous client's dying peer can't flip the connection
     * state or hand a stale DataChannel to the new client's session.
     */
    @Volatile private var connectionGeneration = 0

    /**
     * Invoked when a new client begins connecting, after its peer is armed but before approval.
     * The controller binds a brand-new MCP session here, so a previous client's closed session
     * is never reused (which would leave the new client's `initialize` unanswered).
     */
    var onNewClient: (() -> Unit)? = null

    private val prefs = context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)

    /** Stable id of this install's MCP server. The bridge saves it at pairing and matches on it. */
    val deviceId: String = prefs.getString("webrtc_device_id", null) ?: java.util.UUID.randomUUID().toString()
        .also { prefs.edit().putString("webrtc_device_id", it).apply() }

    private val network = LanNetwork(context)
    private val pinGate = PinGate(PairingCrypto::newPin)
    private val authorizer = ConnectAuthorizer(pinGate) { hash ->
        trustedTokens().any { PairingCrypto.tokenHash(it) == hash }
    }
    private var server: LanSignalingServer? = null

    /** How the current peer's `/aura/connect` was authorised; decides what `init` may do. */
    @Volatile private var connectAuth: ConnectAuthorizer.Decision? = null
    /** LAN address of the current peer, shown in MCP Center. */
    @Volatile private var connectPeer: String? = null

    /** Completes when the current peer finishes gathering ICE candidates. */
    @Volatile private var gatheringDone = CompletableFuture<Unit>()

    private val connectLock = Any()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
        val options = PeerConnectionFactory.Options().apply {
            // Never gather candidates on mobile data. The bits mirror rtc::AdapterType
            // (CELLULAR, CELLULAR_2G/3G/4G/5G); the Java constants for them are package-private.
            networkIgnoreMask = (1 shl 2) or (1 shl 6) or (1 shl 7) or (1 shl 8) or (1 shl 9)
        }
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    /**
     * Opens the signaling port, advertises it on the LAN, and returns the first pairing PIN.
     *
     * - Contract: call once per instance; [close] undoes it. [onStatusChanged] then reports every
     *   PIN rotation and network change.
     * - Fails: throws [java.io.IOException] when no port in [LanPolicy.PORTS] is free.
     */
    fun startListening(): LanStatus {
        val s = LanSignalingServer(
            handler = object : LanSignalingServer.Handler {
                override fun info() = buildJsonObject {
                    put("service", "aura-mcp")
                    put("proto", PairingCrypto.PROTOCOL_VERSION)
                    put("deviceId", deviceId)
                }
                override fun connect(request: LanSignalingServer.ConnectRequest) = onConnect(request)
            },
            acceptPeer = { socket -> LanPolicy.isLanPeer(socket.inetAddress) && !network.arrivedOverCellular(socket) },
            log = { Log.i(TAG, it) },
        )
        val port = s.start()
        server = s
        pinGate.onPinChanged = { publishStatus() }
        network.advertise(port, deviceId)
        network.watch {
            if (closed) return@watch
            network.advertise(port, deviceId)
            publishStatus()
        }
        return status()
    }

    private fun status() = LanStatus(pinGate.pin, server?.port ?: 0, network.lanAddresses())

    private fun publishStatus() {
        if (!closed) onStatusChanged?.invoke(status())
    }

    private fun onConnect(request: LanSignalingServer.ConnectRequest): LanSignalingServer.Response {
        if (closed) return LanSignalingServer.Response.error(503, "stopped", "MCP is stopped on the phone. Start it in AURA → MCP Center.")
        val decision = authorizer.authorize(request)
        if (decision is ConnectAuthorizer.Decision.Refused) {
            Log.w(TAG, "Refused a connect: ${decision.response.body["code"]}")
            return decision.response
        }
        val offer = LanPolicy.filterSdp(request.offerSdp)
        if (LanPolicy.lanCandidateCount(offer) == 0) {
            return LanSignalingServer.Response.error(
                409, "no_lan",
                "Your computer offered no local-network address. Connect it to the same Wi-Fi as the phone.",
            )
        }
        val answer = synchronized(connectLock) {
            answerOffer(offer, decision, request.peer.hostAddress)
        } ?: return LanSignalingServer.Response.error(503, "busy", "The phone couldn't set up the connection. Try again.")
        if (LanPolicy.lanCandidateCount(answer) == 0) {
            synchronized(connectLock) { retirePeer() }
            return LanSignalingServer.Response.error(
                409, "no_lan",
                "The phone has no local-network address. Connect it to Wi-Fi (or turn on its hotspot) and try again.",
            )
        }
        return LanSignalingServer.Response(
            200,
            buildJsonObject {
                putJsonObject("answer") {
                    put("type", "answer")
                    put("sdp", answer)
                }
            },
        )
    }

    /**
     * Retires the current client, arms a fresh peer for [offerSdp], and returns the filtered
     * answer SDP with all candidates, or null when WebRTC failed or timed out.
     *
     * - Contract: blocks the calling (signaling server) thread for up to about 8 s. Callers
     *   serialise on [connectLock].
     */
    private fun answerOffer(offerSdp: String, auth: ConnectAuthorizer.Decision, peerAddress: String?): String? {
        retirePeer()
        connectAuth = auth
        connectPeer = peerAddress
        val pc = setupPeerConnection() ?: return null
        onNewClient?.invoke()

        val localSet = CompletableFuture<Unit>()
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(p0: SessionDescription?) {
                        val desc = p0 ?: run { localSet.completeExceptionally(IllegalStateException("no answer")); return }
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() { localSet.complete(Unit) }
                            override fun onSetFailure(p0: String?) { localSet.completeExceptionally(IllegalStateException(p0)) }
                        }, desc)
                    }
                    override fun onCreateFailure(p0: String?) { localSet.completeExceptionally(IllegalStateException(p0)) }
                }, MediaConstraints())
            }
            override fun onSetFailure(p0: String?) { localSet.completeExceptionally(IllegalStateException(p0)) }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))

        try {
            localSet.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not answer the offer: ${e.message}")
            retirePeer()
            return null
        }
        // Host-only gathering finishes in well under a second. On timeout, send what we have.
        runCatching { gatheringDone.get(3, TimeUnit.SECONDS) }
        val sdp = pc.localDescription?.description ?: return null
        return LanPolicy.filterSdp(sdp)
    }

    /** Disconnects and invalidates the current peer, if any. Safe to call repeatedly. */
    private fun retirePeer() {
        connectionGeneration++
        if (peerConnection != null || sessionKey != null) {
            onConnectionStateChange?.invoke(false)
            auditDisconnect()
            sessionKey?.let { connectionTracker?.onDisconnect(it) }
            sessionKey = null
        }
        isApproved = false
        pendingChallenge = null
        pendingEnroll = null
        runCatching { dataChannel?.close() }
        dataChannel = null
        runCatching { peerConnection?.close() }
        peerConnection = null
    }

    private fun setupPeerConnection(): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()) // LAN only: no STUN, no TURN.
        val gen = connectionGeneration
        val gathered = CompletableFuture<Unit>()
        gatheringDone = gathered

        val pc = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                if (gen != connectionGeneration) return
                Log.d(TAG, "ICE Connection State: $p0")
                if (p0 == PeerConnection.IceConnectionState.CONNECTED) {
                    onConnectionStateChange?.invoke(true)
                } else if (p0 == PeerConnection.IceConnectionState.DISCONNECTED || p0 == PeerConnection.IceConnectionState.FAILED) {
                    onConnectionStateChange?.invoke(false)
                    auditDisconnect()
                    sessionKey?.let { connectionTracker?.onDisconnect(it) }
                    sessionKey = null
                }
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                if (p0 == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }
            // Candidates travel inside the answer SDP once gathering completes; nothing to trickle.
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {
                if (gen != connectionGeneration) return
                Log.i(TAG, "Received remote DataChannel")
                dataChannel = dc
                setupDataChannelObserver()
            }
            override fun onRenegotiationNeeded() {}
        })
        peerConnection = pc
        return pc
    }

    /**
     * Asks the user to approve a PC that isn't trusted yet.
     *
     * - Contract: [verificationCode] is the 6-digit code the PC also prints. The UI must show it
     *   and tell the user to approve only if both match; a mismatch means someone is between
     *   the phone and the PC.
     */
    var onApprovalRequested: ((version: String, clientName: String, host: String?, platform: String?, verificationCode: String, onApproved: (Boolean) -> Unit) -> Unit)? = null
    @Volatile private var isApproved = false

    /** Self-declared display data from the PC's `init`. Never used for trust decisions. */
    private data class ClientMeta(val version: String, val name: String, val host: String?, val platform: String?)

    /** A reconnect waiting for its `proof`: the stored token and the nonce it must sign. */
    private data class PendingChallenge(val token: String, val nonce: String, val binding: String, val meta: ClientMeta)

    /** A new PC the user approved, waiting to send its `token`. */
    private data class PendingEnroll(val tokenHash: String, val meta: ClientMeta)

    @Volatile private var pendingChallenge: PendingChallenge? = null
    @Volatile private var pendingEnroll: PendingEnroll? = null

    /**
     * The pre-approval handshake (protocol [PairingCrypto.PROTOCOL_VERSION]).
     *
     * PC → phone `init` {proto, version, client, host, platform, tokenHash}, then:
     * - Known [tokenHash]: phone → `challenge` {nonce}; PC → `proof` {mac}; the phone checks
     *   `mac == proofMac(token, binding, nonce)` and replies `approved` or `denied`.
     * - Unknown, and the connect carried the right PIN: the user approves on the phone after
     *   comparing [PairingCrypto.verificationCode]; phone → `enroll`; PC → `token` {clientToken},
     *   which must hash to the announced `tokenHash`; phone stores it and replies `approved`.
     * - Anything else (old protocol, a token connect whose `init` names another token, no
     *   fingerprints): `denied` with a `reason`.
     *
     * Why: the binding covers both sides' DTLS fingerprints, so anyone who rewrote the signaling
     * fails the proof or shows the user mismatched codes.
     */
    private fun handleControlMessage(text: String) {
        val json = try {
            org.json.JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring non-JSON pre-approval frame")
            return
        }
        when (json.optString("type")) {
            "init" -> onInit(json)
            "proof" -> onProof(json.optString("mac"))
            "token" -> onToken(json.optString("clientToken"))
            else -> Log.w(TAG, "Unexpected pre-approval message type")
        }
    }

    private fun onInit(json: org.json.JSONObject) {
        val meta = ClientMeta(
            version = json.optString("version", "unknown"),
            name = json.optString("client", "Unknown Client"),
            host = json.optString("host", "").takeIf { it.isNotEmpty() },
            platform = json.optString("platform", "").takeIf { it.isNotEmpty() },
        )
        if (json.optInt("proto", 1) < PairingCrypto.PROTOCOL_VERSION) {
            deny(meta, null, "This AURA needs aura-mcp-connect $MIN_BRIDGE_VERSION or newer. Update it with: npm i -g aura-mcp-connect@latest")
            return
        }
        val binding = currentBinding()
        if (binding == null) {
            deny(meta, null, "Could not read the connection fingerprints")
            return
        }
        val auth = connectAuth
        val tokenHash = json.optString("tokenHash", "")
        if (auth is ConnectAuthorizer.Decision.Token && auth.tokenHash != tokenHash) {
            deny(meta, null, "Pairing token did not match")
            return
        }
        val token = if (tokenHash.isEmpty()) null else trustedTokens().firstOrNull { PairingCrypto.tokenHash(it) == tokenHash }
        if (token != null) {
            val nonce = PairingCrypto.newNonce()
            pendingChallenge = PendingChallenge(token, nonce, binding, meta)
            sendHandshake(org.json.JSONObject().put("type", "challenge").put("nonce", nonce).toString())
            return
        }
        if (auth != ConnectAuthorizer.Decision.Pin) {
            deny(meta, null, "This computer isn't paired. Run: aura-mcp pair <PIN from AURA>")
            return
        }
        if (tokenHash.length != 16) {
            deny(meta, null, "Missing pairing token")
            return
        }
        val gen = connectionGeneration
        val code = PairingCrypto.verificationCode(binding)
        onApprovalRequested?.invoke(meta.version, meta.name, meta.host, meta.platform, code) { approved ->
            if (gen != connectionGeneration) return@invoke // a newer client replaced this one
            if (approved) {
                pendingEnroll = PendingEnroll(tokenHash, meta)
                sendHandshake("{\"type\":\"enroll\"}")
            } else {
                deny(meta, tokenHash.take(8), "Denied on the phone")
            }
        } ?: deny(meta, null, "No approval UI available")
    }

    private fun onProof(mac: String) {
        val c = pendingChallenge ?: return
        pendingChallenge = null
        if (!PairingCrypto.macEquals(PairingCrypto.proofMac(c.token, c.binding, c.nonce), mac)) {
            deny(c.meta, c.token.take(8), "Pairing proof did not match")
            return
        }
        Log.i(TAG, "Trusted client proved its token; auto-approving")
        isApproved = true
        trustedClients?.touch(c.token, c.meta.name, c.meta.host, c.meta.platform, System.currentTimeMillis())
        auditLogger?.logConnection(ConnectionEventType.AUTO_APPROVED, c.token.take(8), c.meta.name, c.meta.host, c.meta.platform)
        registerConnection(c.meta.name, c.meta.version, c.meta.host, c.meta.platform, c.token)
        sendHandshake("{\"type\":\"approved\"}")
    }

    private fun onToken(clientToken: String) {
        val e = pendingEnroll ?: return
        pendingEnroll = null
        if (clientToken.length < 16 || PairingCrypto.tokenHash(clientToken) != e.tokenHash) {
            deny(e.meta, null, "Pairing token did not match")
            return
        }
        isApproved = true
        addTrustedClient(clientToken, e.meta.name, e.meta.host, e.meta.platform)
        auditLogger?.logConnection(ConnectionEventType.APPROVED, clientToken.take(8), e.meta.name, e.meta.host, e.meta.platform)
        registerConnection(e.meta.name, e.meta.version, e.meta.host, e.meta.platform, clientToken)
        sendHandshake("{\"type\":\"approved\"}")
    }

    /** Sends `denied` with [reason], records it, and drops the peer. */
    private fun deny(meta: ClientMeta, tokenId: String?, reason: String) {
        Log.w(TAG, "Denied ${meta.name}: $reason")
        auditLogger?.logConnection(ConnectionEventType.DENIED, tokenId ?: "webrtc", meta.name, meta.host, meta.platform)
        pendingChallenge = null
        pendingEnroll = null
        sendHandshake(org.json.JSONObject().put("type", "denied").put("reason", reason).toString())
        peerConnection?.close()
    }

    /** `binding(phoneFingerprint, pcFingerprint)` for the live peer, or null if either is missing. */
    private fun currentBinding(): String? {
        val phoneFp = PairingCrypto.extractFingerprint(peerConnection?.localDescription?.description)
        val pcFp = PairingCrypto.extractFingerprint(peerConnection?.remoteDescription?.description)
        return if (phoneFp != null && pcFp != null) PairingCrypto.binding(phoneFp, pcFp) else null
    }

    /** Every stored pairing token, from the trust ledger or the legacy prefs set. */
    private fun trustedTokens(): List<String> =
        trustedClients?.clients?.value?.map { it.token }
            ?: (prefs.getStringSet("webrtc_trusted_clients", emptySet())?.toList() ?: emptyList())

    // Last approved client's identity, kept so a later disconnect can be
    // logged with *who* dropped (the ICE/close callbacks only have sessionKey).
    private var connectedTokenId: String? = null
    private var connectedName: String? = null
    private var connectedHost: String? = null
    private var connectedPlatform: String? = null

    /** Emit a DISCONNECTED audit event for the currently-connected client, once. */
    private fun auditDisconnect() {
        val token = connectedTokenId ?: return
        auditLogger?.logConnection(ConnectionEventType.DISCONNECTED, token, connectedName, connectedHost, connectedPlatform)
        connectedTokenId = null
    }

    private fun addTrustedClient(clientToken: String, label: String, host: String?, platform: String?) {
        trustedClients?.approve(clientToken, label, host, platform, System.currentTimeMillis())
            ?: run {
                val trusted = (prefs.getStringSet("webrtc_trusted_clients", emptySet()) ?: emptySet()).toMutableSet()
                trusted.add(clientToken)
                prefs.edit().putStringSet("webrtc_trusted_clients", trusted).apply()
            }
    }

    /**
     * Records the approved client on the [ConnectionTracker] so the MCP Center can show who is
     * connected. [clientToken]'s first 8 chars are a stable per-PC id that doesn't expose the
     * secret.
     */
    private fun registerConnection(
        clientName: String,
        version: String,
        host: String?,
        platform: String?,
        clientToken: String,
    ) {
        val tokenId = clientToken.take(8).ifEmpty { "webrtc" }
        connectedTokenId = tokenId
        connectedName = clientName
        connectedHost = host
        connectedPlatform = platform
        sessionKey = connectionTracker?.onConnect(
            ClientInfo(
                tokenId = tokenId,
                agentLabel = clientName,
                remoteAddr = connectPeer ?: host,
                clientVersion = version,
                host = host,
                platform = platform,
                transport = "WebRTC (LAN)",
            ),
        )
    }

    private fun setupDataChannelObserver() {
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "DataChannel State: ${dataChannel?.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer?.let {
                    val data = ByteArray(it.data.remaining())
                    it.data.get(data)

                    if (!isApproved) {
                        // Control plane: small single-frame text JSON. MCP data flows only after approval.
                        handleControlMessage(String(data))
                        return
                    }

                    // Post-approval: raw MCP bytes. Hand them up verbatim — the
                    // SDK ReadBuffer in WebRtcMcpTransport reassembles newline-
                    // delimited JSON-RPC frames across chunk boundaries.
                    onBytesReceived?.invoke(data)
                }
            }
        })
    }

    /**
     * Send one (already newline-terminated) MCP JSON-RPC frame. Chunked to stay
     * under the SCTP/DataChannel max-message-size and sent as **binary** so a
     * chunk split mid-frame is never decoded as partial UTF-8 — the peer's
     * ReadBuffer reassembles on the newline boundary.
     */
    fun sendFrame(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + MAX_DATACHANNEL_CHUNK, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(chunk), true))
            offset = end
        }
    }

    /**
     * Send a small control-plane message (init/approved/denied) as a single
     * **text** frame — no framing or chunking; the peer parses it whole.
     */
    private fun sendHandshake(message: String) {
        dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray()), false))
    }

    /**
     * Tears this transport down: stops the signaling port and the mDNS advert, closes the peer,
     * and notifies the connection tracker. Idempotent. Must run before another transport starts,
     * or the new one can't bind the port.
     */
    fun close() {
        if (closed) return
        closed = true
        runCatching { server?.stop() }
        server = null
        network.unwatch()
        network.stopAdvertising()
        synchronized(connectLock) { retirePeer() }
        onConnectionStateChange?.invoke(false)
        Log.i(TAG, "WebRtcTransport closed")
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    companion object {
        /**
         * Max bytes per DataChannel send. WebRTC's reliable/ordered SCTP channel
         * caps a single message well below this on some stacks; 16 KiB is a
         * universally safe chunk. Frames larger than this (e.g. base64
         * screenshots) are split here and reassembled by the peer's ReadBuffer.
         */
        private const val MAX_DATACHANNEL_CHUNK = 16 * 1024

        /** Oldest `aura-mcp-connect` that speaks the LAN transport. Change together: the bridge's package.json. */
        const val MIN_BRIDGE_VERSION = "0.9.0"
    }
}
