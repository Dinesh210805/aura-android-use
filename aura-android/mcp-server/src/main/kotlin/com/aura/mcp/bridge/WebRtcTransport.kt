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
import java.util.concurrent.Executors
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
 * - Contract: one approved PC at a time. A new connect is set up as a pending peer beside the
 *   current client and replaces it only once approved ([onNewClient]); until then the current
 *   client keeps working, so a connect that never proves anything disconnects no one. A newer
 *   connect replaces an older pending one.
 * - Contract: an approved peer is either fully live or fully gone. ICE `DISCONNECTED` gets
 *   [ICE_GRACE_MS] to recover (the bridge waits the same way); after that, or on `FAILED`,
 *   `CLOSED` or the DataChannel closing, the peer is closed and [onDisconnected] fires.
 * - Reads/Writes: prefs `aura_prefs` key `webrtc_device_id` (stable id the bridge looks for);
 *   pairing tokens via [TrustedClientsStore].
 * - Fails: if no signaling port can be bound, [startListening] throws and the controller reports
 *   the server as crashed.
 */
class WebRtcTransport(
    private val context: Context,
    private val connectionTracker: ConnectionTracker? = null,
    /** Trust ledger (token → metadata). */
    private val trustedClients: TrustedClientsStore,
    /** Connection lifecycle events (approved / auto / denied / disconnected). */
    private val auditLogger: McpAuditLogger? = null,
) {
    private val TAG = "WebRtcTransport"

    private val peerConnectionFactory: PeerConnectionFactory

    @Volatile private var closed = false

    /** MCP bytes from the approved client. */
    var onBytesReceived: ((ByteArray) -> Unit)? = null

    /** Fires when the approved client goes away: disconnect, replacement, or [close]. Any thread. */
    var onDisconnected: (() -> Unit)? = null

    /** What the MCP Center shows: the pairing PIN, the signaling port and the phone's LAN addresses. */
    data class LanStatus(val pin: String, val port: Int, val addresses: List<String>)

    /** Fires whenever the PIN, port or addresses change. Any thread. */
    var onStatusChanged: ((LanStatus) -> Unit)? = null

    /** A client that has just been approved. [tokenId] is the first 8 chars of its token. */
    data class ApprovedClient(val tokenId: String, val name: String)

    /**
     * Invoked when a client is approved, after the previous client is retired and before
     * `approved` is sent, so the controller can bind a brand-new MCP session for it. A previous
     * client's closed session is never reused (which would leave the new `initialize` unanswered).
     */
    var onNewClient: ((ApprovedClient) -> Unit)? = null

    /**
     * Asks the user to approve a PC that isn't trusted yet.
     *
     * - Contract: [verificationCode] is the 6-digit code the PC also prints. The UI must show it
     *   and tell the user to approve only if both match; a mismatch means someone is between
     *   the phone and the PC.
     */
    var onApprovalRequested: ((version: String, clientName: String, host: String?, platform: String?, verificationCode: String, onApproved: (Boolean) -> Unit) -> Unit)? = null

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

    /** Serialises `/aura/connect` handling; held while an offer is answered (up to ~8 s). */
    private val connectLock = Any()

    /** Guards [active] and [pending]. Never held while waiting on WebRTC. */
    private val stateLock = Any()
    @Volatile private var active: Peer? = null
    @Volatile private var pending: Peer? = null

    /** Closes peers and runs the ICE grace check, off the WebRTC callback threads. */
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "aura-webrtc-peer").apply { isDaemon = true }
    }

    /** Self-declared display data from the PC's `init`. Never used for trust decisions. */
    private data class ClientMeta(val version: String, val name: String, val host: String?, val platform: String?)

    /** A reconnect waiting for its `proof`: the stored token and the nonce it must sign. */
    private data class PendingChallenge(val token: String, val nonce: String, val binding: String, val meta: ClientMeta)

    /** A new PC that committed to its nonce; waiting for `reveal`. */
    private data class PendingSas(
        val tokenHash: String,
        val commit: String,
        val phoneNonce: String,
        val binding: String,
        val meta: ClientMeta,
    )

    /** A new PC the user approved, waiting to send its `token`. */
    private data class PendingEnroll(val tokenHash: String, val meta: ClientMeta)

    /** Who an approved peer is, for the connection tracker and the disconnect audit entry. */
    private data class ClientRecord(val tokenId: String, val meta: ClientMeta, val sessionKey: String?)

    /** One WebRTC peer and its handshake state. */
    private inner class Peer(val auth: ConnectAuthorizer.Decision, val address: String?) {
        @Volatile var pc: PeerConnection? = null
        @Volatile var dataChannel: DataChannel? = null
        @Volatile var approved = false
        @Volatile var initSeen = false
        @Volatile var challenge: PendingChallenge? = null
        @Volatile var sas: PendingSas? = null
        @Volatile var enroll: PendingEnroll? = null
        @Volatile var client: ClientRecord? = null
        @Volatile var dead = false
        val gathered = CompletableFuture<Unit>()

        /** `binding(phoneFingerprint, pcFingerprint)`, or null if either is missing. */
        fun binding(): String? {
            val phoneFp = PairingCrypto.extractFingerprint(pc?.localDescription?.description)
            val pcFp = PairingCrypto.extractFingerprint(pc?.remoteDescription?.description)
            return if (phoneFp != null && pcFp != null) PairingCrypto.binding(phoneFp, pcFp) else null
        }

        /** Sends a control-plane message as one text frame; the peer parses it whole. */
        fun send(message: String) {
            runCatching { dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray()), false)) }
        }

        /** Idempotent. Runs on [worker]: closing from inside a WebRTC observer can deadlock. */
        fun close() {
            if (dead) return
            dead = true
            val dc = dataChannel
            val p = pc
            onWorker {
                runCatching { dc?.close() }
                runCatching { p?.close() }
            }
        }
    }

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
        val peer = Peer(decision, request.peer.hostAddress)
        val answer = synchronized(connectLock) {
            if (replacePending(peer)) answerOffer(peer, offer) else null
        }
        if (answer == null) {
            drop(peer)
            return LanSignalingServer.Response.error(503, "busy", "The phone couldn't set up the connection. Try again.")
        }
        if (LanPolicy.lanCandidateCount(answer) == 0) {
            drop(peer)
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

    /** Makes [peer] the pending one, closing any older pending peer. False once [close]d. */
    private fun replacePending(peer: Peer): Boolean {
        val old = synchronized(stateLock) {
            if (closed) return false
            pending.also { pending = peer }
        }
        old?.close()
        return true
    }

    /**
     * Arms a peer connection for [peer] and returns the filtered answer SDP with all candidates,
     * or null when WebRTC failed or timed out.
     *
     * - Contract: blocks the calling (signaling server) thread for up to about 8 s. Callers
     *   serialise on [connectLock].
     */
    private fun answerOffer(peer: Peer, offerSdp: String): String? {
        val pc = createPeerConnection(peer) ?: return null

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
            return null
        }
        // Host-only gathering finishes in well under a second. On timeout, send what we have.
        runCatching { peer.gathered.get(3, TimeUnit.SECONDS) }
        val sdp = pc.localDescription?.description ?: return null
        return LanPolicy.filterSdp(sdp)
    }

    private fun createPeerConnection(peer: Peer): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()) // LAN only: no STUN, no TURN.
        val pc = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection State: $state")
                when (state) {
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> drop(peer)
                    PeerConnection.IceConnectionState.DISCONNECTED -> onWorker(ICE_GRACE_MS) {
                        val now = runCatching { peer.pc?.iceConnectionState() }.getOrNull()
                        if (!peer.dead && now == PeerConnection.IceConnectionState.DISCONNECTED) drop(peer)
                    }
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                if (p0 == PeerConnection.IceGatheringState.COMPLETE) peer.gathered.complete(Unit)
            }
            // Candidates travel inside the answer SDP once gathering completes; nothing to trickle.
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {
                if (dc == null || peer.dead) return
                Log.i(TAG, "Received remote DataChannel")
                peer.dataChannel = dc
                observe(peer, dc)
            }
            override fun onRenegotiationNeeded() {}
        }) ?: return null
        peer.pc = pc
        return pc
    }

    /**
     * Closes [peer] and forgets it. If it was the approved client, fires [onDisconnected] and
     * records the disconnect. Safe to call repeatedly and from any thread.
     */
    private fun drop(peer: Peer) {
        val wasActive = synchronized(stateLock) {
            when {
                active === peer -> { active = null; true }
                pending === peer -> { pending = null; false }
                else -> false
            }
        }
        peer.close()
        if (wasActive) clientGone(peer)
    }

    private fun clientGone(peer: Peer) {
        onDisconnected?.invoke()
        peer.client?.let { c ->
            auditLogger?.logConnection(ConnectionEventType.DISCONNECTED, c.tokenId, c.meta.name, c.meta.host, c.meta.platform)
            c.sessionKey?.let { connectionTracker?.onDisconnect(it) }
        }
    }

    private fun observe(peer: Peer, dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                val state = runCatching { dc.state() }.getOrNull()
                Log.d(TAG, "DataChannel State: $state")
                if (state == DataChannel.State.CLOSED) drop(peer)
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null || peer.dead) return
                val data = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                when {
                    // Control plane: small single-frame text JSON. MCP data flows only after approval.
                    !peer.approved -> handleControlMessage(peer, String(data))
                    // Post-approval: raw MCP bytes, handed up verbatim. The SDK ReadBuffer in
                    // WebRtcMcpTransport reassembles newline-delimited frames across chunks.
                    peer === active -> onBytesReceived?.invoke(data)
                }
            }
        })
    }

    /**
     * The pre-approval handshake (protocol [PairingCrypto.PROTOCOL_VERSION]).
     *
     * PC → phone `init` {proto, version, client, host, platform, tokenHash, commit}, then:
     * - Known [tokenHash]: phone → `challenge` {nonce}; PC → `proof` {mac}; the phone checks
     *   `mac == proofMac(token, binding, nonce)` and replies `approved` or `denied`. Protocol 2
     *   bridges may still do this.
     * - Unknown, and the connect carried the right PIN: phone → `sas` {nonce}; PC → `reveal`
     *   {nonce}, which must hash to `commit`; the user approves on the phone after comparing
     *   [PairingCrypto.verificationCode]; phone → `enroll`; PC → `token` {clientToken}, which
     *   must hash to the announced `tokenHash`; phone stores it and replies `approved`.
     * - Anything else (old protocol, a token connect whose `init` names another token, no
     *   fingerprints, a second `init`): `denied` with a `reason`, or ignored.
     *
     * Why: the binding covers both sides' DTLS fingerprints, so anyone who rewrote the signaling
     * fails the proof or shows the user mismatched codes. The PC commits to its nonce before it
     * sees the phone's, so a man in the middle can't pick values that make the codes match.
     */
    private fun handleControlMessage(peer: Peer, text: String) {
        val json = try {
            org.json.JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring non-JSON pre-approval frame")
            return
        }
        when (json.optString("type")) {
            "init" -> if (!peer.initSeen) {
                peer.initSeen = true
                onInit(peer, json)
            }
            "proof" -> onProof(peer, json.optString("mac"))
            "reveal" -> onReveal(peer, json.optString("nonce"))
            "token" -> onToken(peer, json.optString("clientToken"))
            else -> Log.w(TAG, "Unexpected pre-approval message type")
        }
    }

    private fun onInit(peer: Peer, json: org.json.JSONObject) {
        val meta = ClientMeta(
            version = json.optString("version", "unknown"),
            name = json.optString("client", "Unknown Client"),
            host = json.optString("host", "").takeIf { it.isNotEmpty() },
            platform = json.optString("platform", "").takeIf { it.isNotEmpty() },
        )
        val proto = json.optInt("proto", 1)
        if (proto < MIN_RECONNECT_PROTOCOL) {
            deny(peer, meta, null, UPDATE_BRIDGE)
            return
        }
        val binding = peer.binding()
        if (binding == null) {
            deny(peer, meta, null, "Could not read the connection fingerprints")
            return
        }
        val auth = peer.auth
        val tokenHash = json.optString("tokenHash", "")
        if (auth is ConnectAuthorizer.Decision.Token && auth.tokenHash != tokenHash) {
            deny(peer, meta, null, "Pairing token did not match")
            return
        }
        val token = if (tokenHash.isEmpty()) null else trustedTokens().firstOrNull { PairingCrypto.tokenHash(it) == tokenHash }
        if (token != null) {
            val nonce = PairingCrypto.newNonce()
            peer.challenge = PendingChallenge(token, nonce, binding, meta)
            peer.send(org.json.JSONObject().put("type", "challenge").put("nonce", nonce).toString())
            return
        }
        if (auth != ConnectAuthorizer.Decision.Pin) {
            deny(peer, meta, null, "This computer isn't paired. Run: aura-mcp pair <PIN from AURA>")
            return
        }
        // A new pairing needs the commitment step, which only protocol 3 bridges send.
        if (proto < PairingCrypto.PROTOCOL_VERSION) {
            deny(peer, meta, null, UPDATE_BRIDGE)
            return
        }
        val commit = json.optString("commit", "")
        if (tokenHash.length != 16 || !HEX_SHA256.matches(commit)) {
            deny(peer, meta, null, "Missing pairing token")
            return
        }
        val nonce = PairingCrypto.newNonce()
        peer.sas = PendingSas(tokenHash, commit, nonce, binding, meta)
        peer.send(org.json.JSONObject().put("type", "sas").put("nonce", nonce).toString())
    }

    private fun onReveal(peer: Peer, pcNonce: String) {
        val s = peer.sas ?: return
        peer.sas = null
        if (!PairingCrypto.macEquals(PairingCrypto.commitment(pcNonce), s.commit)) {
            deny(peer, s.meta, null, "Pairing commitment did not match")
            return
        }
        val code = PairingCrypto.verificationCode(s.binding, pcNonce, s.phoneNonce)
        onApprovalRequested?.invoke(s.meta.version, s.meta.name, s.meta.host, s.meta.platform, code) { approved ->
            if (peer.dead || peer !== pending) return@invoke // a newer connect replaced this one
            if (approved) {
                peer.enroll = PendingEnroll(s.tokenHash, s.meta)
                peer.send("{\"type\":\"enroll\"}")
            } else {
                deny(peer, s.meta, s.tokenHash.take(8), "Denied on the phone")
            }
        } ?: deny(peer, s.meta, null, "No approval UI available")
    }

    private fun onProof(peer: Peer, mac: String) {
        val c = peer.challenge ?: return
        peer.challenge = null
        if (!PairingCrypto.macEquals(PairingCrypto.proofMac(c.token, c.binding, c.nonce), mac)) {
            deny(peer, c.meta, c.token.take(8), "Pairing proof did not match")
            return
        }
        if (!promote(peer, c.token, c.meta)) return
        Log.i(TAG, "Trusted client proved its token; auto-approved")
        trustedClients.touch(c.token, c.meta.name, c.meta.host, c.meta.platform, System.currentTimeMillis())
        auditLogger?.logConnection(ConnectionEventType.AUTO_APPROVED, c.token.take(8), c.meta.name, c.meta.host, c.meta.platform)
    }

    private fun onToken(peer: Peer, clientToken: String) {
        val e = peer.enroll ?: return
        peer.enroll = null
        if (clientToken.length < 16 || PairingCrypto.tokenHash(clientToken) != e.tokenHash) {
            deny(peer, e.meta, null, "Pairing token did not match")
            return
        }
        if (!promote(peer, clientToken, e.meta)) return
        trustedClients.approve(clientToken, e.meta.name, e.meta.host, e.meta.platform, System.currentTimeMillis())
        auditLogger?.logConnection(ConnectionEventType.APPROVED, clientToken.take(8), e.meta.name, e.meta.host, e.meta.platform)
    }

    /**
     * Makes the approved pending [peer] the active client: retires the previous client, binds a
     * fresh MCP session ([onNewClient]), registers it with the tracker and sends `approved`.
     * False when [peer] was superseded or closed meanwhile; it is then dropped.
     */
    private fun promote(peer: Peer, token: String, meta: ClientMeta): Boolean {
        var previous: Peer? = null
        val promoted = synchronized(stateLock) {
            if (closed || peer.dead || pending !== peer) return@synchronized false
            pending = null
            previous = active
            active = peer
            true
        }
        if (!promoted) {
            drop(peer)
            return false
        }
        previous?.let { it.close(); clientGone(it) }
        val tokenId = token.take(8)
        onNewClient?.invoke(ApprovedClient(tokenId, meta.name))
        peer.approved = true
        peer.client = ClientRecord(
            tokenId = tokenId,
            meta = meta,
            sessionKey = connectionTracker?.onConnect(
                ClientInfo(
                    tokenId = tokenId,
                    agentLabel = meta.name,
                    remoteAddr = peer.address ?: meta.host,
                    clientVersion = meta.version,
                    host = meta.host,
                    platform = meta.platform,
                    transport = "WebRTC (LAN)",
                ),
            ),
        )
        peer.send("{\"type\":\"approved\"}")
        return true
    }

    /** Sends `denied` with [reason], records it, and drops the peer once the message has gone. */
    private fun deny(peer: Peer, meta: ClientMeta, tokenId: String?, reason: String) {
        Log.w(TAG, "Denied ${meta.name}: $reason")
        auditLogger?.logConnection(ConnectionEventType.DENIED, tokenId ?: "webrtc", meta.name, meta.host, meta.platform)
        peer.challenge = null
        peer.sas = null
        peer.enroll = null
        peer.send(org.json.JSONObject().put("type", "denied").put("reason", reason).toString())
        onWorker(DENY_FLUSH_MS) { drop(peer) }
    }

    /** Every stored pairing token. */
    private fun trustedTokens(): List<String> = trustedClients.clients.value.map { it.token }

    private fun onWorker(delayMs: Long = 0, block: () -> Unit) {
        runCatching { worker.schedule(block, delayMs, TimeUnit.MILLISECONDS) }
    }

    /**
     * Send one (already newline-terminated) MCP JSON-RPC frame to the approved client. Chunked
     * to stay under the SCTP/DataChannel max-message-size and sent as **binary** so a chunk
     * split mid-frame is never decoded as partial UTF-8 — the peer's ReadBuffer reassembles on
     * the newline boundary.
     */
    fun sendFrame(bytes: ByteArray) {
        val dc = active?.dataChannel ?: return
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + MAX_DATACHANNEL_CHUNK, bytes.size)
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes.copyOfRange(offset, end)), true))
            offset = end
        }
    }

    /**
     * Tears this transport down: stops the signaling port and the mDNS advert, closes both
     * peers, and notifies the connection tracker. Idempotent and quick (never waits on an
     * in-flight connect). Must run before another transport starts, or the new one can't bind
     * the port.
     */
    fun close() {
        val peers = synchronized(stateLock) {
            if (closed) return
            closed = true
            listOfNotNull(pending, active)
        }
        runCatching { server?.stop() }
        server = null
        network.unwatch()
        network.stopAdvertising()
        peers.forEach(::drop)
        worker.shutdown() // already-queued closes still run
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

        /** How long ICE `DISCONNECTED` may last before the peer is closed. Matches the bridge. */
        private const val ICE_GRACE_MS = 10_000L

        /** Lets `denied` reach the PC before the channel closes. */
        private const val DENY_FLUSH_MS = 500L

        /** Oldest protocol that may reconnect an already-paired PC (no first pairing). */
        private const val MIN_RECONNECT_PROTOCOL = 2

        private val HEX_SHA256 = Regex("^[0-9a-f]{64}$")

        /** Oldest `aura-mcp-connect` that can pair. Change together: the bridge's package.json. */
        const val MIN_BRIDGE_VERSION = "0.10.0"

        private const val UPDATE_BRIDGE =
            "This AURA needs aura-mcp-connect $MIN_BRIDGE_VERSION or newer. Update it with: npm i -g aura-mcp-connect@latest"
    }
}
