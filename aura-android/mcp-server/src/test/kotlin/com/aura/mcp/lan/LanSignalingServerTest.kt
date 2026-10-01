package com.aura.mcp.lan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

/** Drives the real server over a loopback socket with raw HTTP, the way the bridge does. */
class LanSignalingServerTest {

    private val received = mutableListOf<LanSignalingServer.ConnectRequest>()
    private val server = LanSignalingServer(
        handler = object : LanSignalingServer.Handler {
            override fun info() = buildJsonObject { put("service", "aura-mcp"); put("deviceId", "dev-1") }
            override fun connect(request: LanSignalingServer.ConnectRequest): LanSignalingServer.Response {
                received += request
                return LanSignalingServer.Response(200, buildJsonObject { put("ok", true) })
            }
        },
        requestDeadlineMs = 500,
    )
    private val port = server.start(40000..40100)

    @After fun stop() = server.stop()

    private fun call(raw: String): Pair<Int, String> =
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write(raw.toByteArray())
            val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            text.substringAfter(' ').substringBefore(' ').toInt() to text.substringAfter("\r\n\r\n")
        }

    private fun post(body: String, extraHeaders: String = "", type: String = "application/json") =
        call(
            "POST /aura/connect HTTP/1.1\r\nHost: x\r\nContent-Type: $type\r\n$extraHeaders" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n$body",
        )

    private val offer = """"offer":{"type":"offer","sdp":"v=0\r\n"}"""

    @Test fun `info answers`() {
        val (status, body) = call("GET /aura/info HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(200, status)
        assertEquals("dev-1", Json.parseToJsonElement(body).jsonObject["deviceId"]!!.jsonPrimitive.content)
    }

    @Test fun `a well-formed connect reaches the handler`() {
        assertEquals(200, post("""{"pin":"123456",$offer}""").first)
        val r = received.single()
        assertEquals("123456", r.pin)
        assertNull(r.tokenHash)
        assertEquals("v=0\r\n", r.offerSdp)
        assertEquals(InetAddress.getByName("127.0.0.1"), r.peer)
    }

    @Test fun `browser requests are refused`() {
        assertEquals(403, post("""{"pin":"123456",$offer}""", extraHeaders = "Origin: http://evil.example\r\n").first)
        assertEquals(415, post("""{"pin":"123456",$offer}""", type = "text/plain").first)
        assertTrue(received.isEmpty())
    }

    @Test fun `malformed connects get 400 and never reach the handler`() {
        listOf(
            "not json",
            """{"pin":"123456"}""",
            """{"pin":"12345",$offer}""",
            """{"tokenHash":"XYZ",$offer}""",
            """{"pin":"123456","tokenHash":"0123456789abcdef",$offer}""",
            """{$offer}""",
            """{"pin":"123456","offer":{"type":"answer","sdp":"v=0"}}""",
        ).forEach { assertEquals(400, post(it).first, it) }
        assertTrue(received.isEmpty())
    }

    @Test fun `unknown paths are 404`() =
        assertEquals(404, call("GET /secrets HTTP/1.1\r\n\r\n").first)

    @Test fun `non-LAN peers are dropped before any handler runs`() {
        val closed = LanSignalingServer(handler = object : LanSignalingServer.Handler {
            override fun info() = error("must not run")
            override fun connect(request: LanSignalingServer.ConnectRequest) = error("must not run")
        }, acceptPeer = { false })
        val p = closed.start(40101..40200)
        try {
            val text = Socket("127.0.0.1", p).use { s ->
                s.getOutputStream().write("GET /aura/info HTTP/1.1\r\n\r\n".toByteArray())
                s.getInputStream().readBytes()
            }
            assertEquals(0, text.size)
        } finally {
            closed.stop()
        }
    }

    /** A socket that sends one byte and then nothing, holding a worker for as long as it is allowed. */
    private fun stall(): Socket = Socket("127.0.0.1", port).apply { getOutputStream().write('G'.code) }

    @Test fun `slow requests are cut off at the deadline, not per read`() {
        val stalls = List(LanSignalingServer.MAX_PER_PEER) { stall() }
        try {
            Thread.sleep(900)
            stalls.forEach { s ->
                s.soTimeout = 1_000
                assertEquals(-1, s.getInputStream().read(), "server should have closed the stalled request")
            }
            assertEquals(200, call("GET /aura/info HTTP/1.1\r\nHost: x\r\n\r\n").first)
        } finally {
            stalls.forEach { it.close() }
        }
    }

    @Test fun `one peer cannot hold more than its share of workers`() {
        val stalls = List(LanSignalingServer.MAX_PER_PEER) { stall() }
        try {
            Thread.sleep(100)
            Socket("127.0.0.1", port).use { extra ->
                extra.soTimeout = 300 // shorter than the deadline: only an immediate close reads -1
                val first = try { extra.getInputStream().read() } catch (e: SocketTimeoutException) { -2 }
                assertEquals(-1, first, "a request over the per-peer cap should be closed at once")
            }
        } finally {
            stalls.forEach { it.close() }
        }
    }
}
