package com.aura.mcp.lan

import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class ConnectAuthorizerTest {

    private val gate = PinGate(newPin = { "111111" })
    private val authorizer = ConnectAuthorizer(gate) { it == "0123456789abcdef" }
    private val peer = InetAddress.getByName("192.168.1.5")

    private fun req(pin: String? = null, hash: String? = null) =
        LanSignalingServer.ConnectRequest(pin, hash, "v=0", peer)

    @Test fun `right PIN opens the pairing path`() =
        assertEquals(ConnectAuthorizer.Decision.Pin, authorizer.authorize(req(pin = "111111")))

    @Test fun `known token hash opens the reconnect path`() =
        assertEquals(ConnectAuthorizer.Decision.Token("0123456789abcdef"), authorizer.authorize(req(hash = "0123456789abcdef")))

    @Test fun `unknown token hash is refused with not_paired`() {
        val d = assertIs<ConnectAuthorizer.Decision.Refused>(authorizer.authorize(req(hash = "ffffffffffffffff")))
        assertEquals(403, d.response.status)
        assertEquals("\"not_paired\"", d.response.body["code"].toString())
    }

    @Test fun `wrong PINs are refused, then locked with a retry time`() {
        repeat(PinGate.MAX_FAILURES - 1) {
            val d = assertIs<ConnectAuthorizer.Decision.Refused>(authorizer.authorize(req(pin = "000000")))
            assertEquals(403, d.response.status)
        }
        val locked = assertIs<ConnectAuthorizer.Decision.Refused>(authorizer.authorize(req(pin = "000000")))
        assertEquals(429, locked.response.status)
        assertEquals("60", locked.response.body["retryAfterSec"].toString())
    }
}
