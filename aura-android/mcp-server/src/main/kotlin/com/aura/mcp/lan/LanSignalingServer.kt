package com.aura.mcp.lan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The phone's signaling endpoint: a tiny HTTP/1.1 server on the local network that trades one
 * WebRTC offer for one answer. MCP traffic itself never goes through it.
 *
 * Endpoints (JSON in and out, `Connection: close`):
 * - `GET /aura/info` → [Handler.info]. Lets the PC recognise the phone during discovery.
 * - `POST /aura/connect` {`pin`? | `tokenHash`?, `offer`: {`type`: "offer", `sdp`}} →
 *   [Handler.connect]: 200 {`answer`: {`type`, `sdp`}} or an error {`error`, `code`,
 *   `retryAfterSec`?}.
 *
 * - Contract: pure JVM (no Android APIs), so it is unit tested on the desktop JVM. Binds IPv4
 *   only, on the first free port of [LanPolicy.PORTS]. At most [MAX_CONCURRENT] requests run at
 *   once, and at most [MAX_PER_PEER] from one address; extra connections are closed unanswered.
 *   A request must arrive in full within `requestDeadlineMs`, so a client trickling bytes can't
 *   hold a worker.
 * - Refuses, before any handler runs: peers that fail [acceptPeer] (not on the LAN, or arriving
 *   over mobile data), any request with an `Origin` header, and a POST that isn't
 *   `application/json`. Why the last two: a web page open on a computer in the same network can
 *   send requests here. Browsers always add `Origin` to those, and can't send a JSON POST
 *   cross-origin without a preflight this server never answers. The bridge sends neither.
 * - Fails: a malformed request gets 400; an exception in a handler gets 500 and is logged.
 *   Nothing is retried.
 * - Change together: the request/response shapes with `aura-mcp-connect/src/lan.js`.
 */
class LanSignalingServer(
    private val handler: Handler,
    private val acceptPeer: (socket: Socket) -> Boolean = { LanPolicy.isLanPeer(it.inetAddress) },
    private val log: (String) -> Unit = {},
    private val requestDeadlineMs: Long = REQUEST_DEADLINE_MS,
) {
    interface Handler {
        fun info(): JsonObject
        fun connect(request: ConnectRequest): Response
    }

    /** A parsed, shape-checked `POST /aura/connect`. Exactly one of [pin] and [tokenHash] is set. */
    data class ConnectRequest(
        val pin: String?,
        val tokenHash: String?,
        val offerSdp: String,
        val peer: InetAddress,
    )

    data class Response(val status: Int, val body: JsonObject) {
        companion object {
            fun error(status: Int, code: String, message: String, retryAfterSec: Long? = null) =
                Response(
                    status,
                    buildJsonObject {
                        put("error", message)
                        put("code", code)
                        if (retryAfterSec != null) put("retryAfterSec", retryAfterSec)
                    },
                )
        }
    }

    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = ThreadPoolExecutor(
        0, MAX_CONCURRENT, 30, TimeUnit.SECONDS, SynchronousQueue(),
    ) { r -> Thread(r, "aura-lan-signaling").apply { isDaemon = true } }
    private val perPeer = ConcurrentHashMap<InetAddress, Int>()

    /** The bound port, or 0 when stopped. */
    val port: Int get() = serverSocket?.localPort ?: 0

    /**
     * Binds and starts accepting. Returns the bound port.
     *
     * - Fails: throws [java.io.IOException] when every port in [ports] is taken.
     */
    fun start(ports: IntRange = LanPolicy.PORTS): Int {
        check(serverSocket == null) { "already started" }
        var lastError: Exception? = null
        for (p in ports) {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(ANY_IPV4, p), 16)
                serverSocket = s
                break
            } catch (e: Exception) {
                lastError = e
                runCatching { s.close() }
            }
        }
        val bound = serverSocket ?: throw java.io.IOException("No free signaling port in $ports", lastError)
        acceptThread = Thread({ acceptLoop(bound) }, "aura-lan-accept").apply { isDaemon = true; start() }
        log("LAN signaling listening on port ${bound.localPort}")
        return bound.localPort
    }

    /** Stops accepting and closes the socket. Idempotent. In-flight requests finish on their own. */
    fun stop() {
        val s = serverSocket ?: return
        serverSocket = null
        runCatching { s.close() }
        workers.shutdown()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val socket = try {
                s.accept()
            } catch (e: SocketException) {
                break // closed by stop()
            } catch (e: Exception) {
                log("accept failed: ${e.message}")
                continue
            }
            if (!acceptPeer(socket)) {
                log("Refused a non-LAN peer")
                runCatching { socket.close() }
                continue
            }
            val peer = socket.inetAddress
            if (perPeer.merge(peer, 1, Int::plus)!! > MAX_PER_PEER) {
                release(peer)
                runCatching { socket.close() }
                continue
            }
            try {
                workers.execute {
                    try { serve(socket) } finally { release(peer) }
                }
            } catch (e: RejectedExecutionException) {
                release(peer)
                runCatching { socket.close() }
            }
        }
    }

    private fun release(peer: InetAddress) {
        perPeer.compute(peer) { _, n -> if (n == null || n <= 1) null else n - 1 }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            val response = try {
                route(readRequest(DeadlineInput(s, System.currentTimeMillis() + requestDeadlineMs)), s.inetAddress)
            } catch (e: SocketTimeoutException) {
                return // too slow: close without an answer
            } catch (e: BadRequest) {
                Response.error(400, "bad_request", e.message ?: "Bad request")
            } catch (e: Exception) {
                log("request failed: ${e.message}")
                Response.error(500, "internal", "The phone hit an error. Try again.")
            }
            runCatching { writeResponse(s, response) }
        }
    }

    internal fun route(req: Request, peer: InetAddress): Response {
        if (req.headers.containsKey("origin")) return Response.error(403, "forbidden", "Browsers may not call this endpoint")
        return when {
            req.method == "GET" && req.path == "/aura/info" -> Response(200, handler.info())
            req.method == "POST" && req.path == "/aura/connect" -> {
                val type = req.headers["content-type"].orEmpty()
                if (!type.startsWith("application/json")) {
                    return Response.error(415, "bad_request", "Expected application/json")
                }
                handler.connect(parseConnect(req.body, peer))
            }
            else -> Response.error(404, "not_found", "Unknown endpoint")
        }
    }

    /** Reads from [socket], failing with [SocketTimeoutException] once [deadline] (epoch ms) passes. */
    private class DeadlineInput(private val socket: Socket, private val deadline: Long) : InputStream() {
        private val inner = socket.getInputStream()

        private fun arm() {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw SocketTimeoutException("request deadline passed")
            socket.soTimeout = left.toInt()
        }

        override fun read(): Int { arm(); return inner.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { arm(); return inner.read(b, off, len) }
    }

    internal data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private class BadRequest(message: String) : Exception(message)

    companion object {
        const val MAX_CONCURRENT = 8
        const val MAX_PER_PEER = 2
        private const val REQUEST_DEADLINE_MS = 5_000L
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_SDP_CHARS = 32 * 1024
        private val ANY_IPV4: InetAddress = Inet4Address.getByAddress(byteArrayOf(0, 0, 0, 0))
        private val PIN = Regex("^[0-9]{6}$")
        private val TOKEN_HASH = Regex("^[0-9a-f]{16}$")
        private val json = Json { ignoreUnknownKeys = true }

        internal fun readRequest(input: InputStream): Request {
            val head = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                if (b < 0) throw BadRequest("Connection closed mid-request")
                head.write(b)
                if (head.size() > MAX_HEADER_BYTES) throw BadRequest("Headers too large")
                matched = when {
                    (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                    (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
            }
            val lines = head.toString(Charsets.ISO_8859_1.name()).trimEnd().split("\r\n")
            val start = lines.first().split(" ")
            if (start.size != 3 || !start[2].startsWith("HTTP/1.")) throw BadRequest("Malformed request line")
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()
            if (headers["transfer-encoding"] != null) throw BadRequest("Chunked bodies are not supported")
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY_BYTES) throw BadRequest("Body too large")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) throw BadRequest("Connection closed mid-body")
                read += n
            }
            return Request(start[0], start[1].substringBefore('?'), headers, String(body, Charsets.UTF_8))
        }

        internal fun parseConnect(body: String, peer: InetAddress): ConnectRequest {
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw BadRequest("Body is not a JSON object")
            fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val offer = obj["offer"] as? JsonObject ?: throw BadRequest("Missing offer")
            if (str(offer, "type") != "offer") throw BadRequest("offer.type must be \"offer\"")
            val sdp = str(offer, "sdp") ?: throw BadRequest("Missing offer.sdp")
            if (sdp.length > MAX_SDP_CHARS) throw BadRequest("offer.sdp too large")
            val pin = str(obj, "pin")
            val tokenHash = str(obj, "tokenHash")
            if ((pin == null) == (tokenHash == null)) throw BadRequest("Send exactly one of pin and tokenHash")
            if (pin != null && !PIN.matches(pin)) throw BadRequest("pin must be 6 digits")
            if (tokenHash != null && !TOKEN_HASH.matches(tokenHash)) throw BadRequest("Malformed tokenHash")
            return ConnectRequest(pin, tokenHash, sdp, peer)
        }

        private fun writeResponse(socket: Socket, response: Response) {
            val body = response.body.toString().toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(body)
                flush()
            }
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            415 -> "Unsupported Media Type"
            429 -> "Too Many Requests"
            503 -> "Service Unavailable"
            else -> "Error"
        }
    }
}
