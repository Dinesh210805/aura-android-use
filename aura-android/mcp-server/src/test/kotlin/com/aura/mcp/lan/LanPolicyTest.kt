package com.aura.mcp.lan

import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Change together: `aura-mcp-connect/test/lan.test.mjs` asserts the same addresses and the same
 * SDP, so the phone and the bridge agree on what "local network" means.
 */
class LanPolicyTest {

    @Test fun `private, link-local and tailscale ranges are LAN`() {
        listOf("10.0.0.1", "172.16.5.4", "172.31.255.255", "192.168.1.23", "169.254.10.10", "100.64.0.1", "100.127.255.254")
            .forEach { assertTrue(LanPolicy.isLanIpv4(it), it) }
    }

    @Test fun `public, loopback, malformed and IPv6 addresses are not`() {
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "100.63.255.255", "100.128.0.1", "127.0.0.1",
            "192.169.0.1", "256.1.1.1", "10.0.0", "10.0.0.1.2", "fd00::1", "fe80::1", "", "a.b.c.d", "010.0.0.1x")
            .forEach { assertFalse(LanPolicy.isLanIpv4(it), it) }
    }

    @Test fun `loopback peers are allowed for adb forward`() {
        assertTrue(LanPolicy.isLanPeer(InetAddress.getByName("127.0.0.1")))
        assertTrue(LanPolicy.isLanPeer(InetAddress.getByName("192.168.0.9")))
        assertFalse(LanPolicy.isLanPeer(InetAddress.getByName("1.1.1.1")))
    }

    private val sdp = listOf(
        "v=0",
        "a=fingerprint:sha-256 AB:CD",
        "a=candidate:1 1 udp 2122260223 192.168.1.23 50000 typ host generation 0",
        "a=candidate:2 1 udp 2122260223 8.8.4.4 50001 typ host generation 0",
        "a=candidate:3 1 udp 1686052607 203.0.113.9 50002 typ srflx raddr 192.168.1.23 rport 50000",
        "a=candidate:4 1 udp 41885439 10.1.2.3 3478 typ relay raddr 0.0.0.0 rport 0",
        "a=candidate:5 1 udp 2122262783 2001:db8::1 50003 typ host generation 0",
        "a=candidate:6 1 tcp 1518280447 100.101.102.103 9 typ host tcptype active",
        "a=candidate:7 1 udp 2122260223 abcd-1234.local 50004 typ host",
        "a=end-of-candidates",
        "",
    ).joinToString("\r\n")

    @Test fun `filterSdp keeps only LAN host candidates and every other line`() {
        val expected = listOf(
            "v=0",
            "a=fingerprint:sha-256 AB:CD",
            "a=candidate:1 1 udp 2122260223 192.168.1.23 50000 typ host generation 0",
            "a=candidate:6 1 tcp 1518280447 100.101.102.103 9 typ host tcptype active",
            "a=end-of-candidates",
            "",
        ).joinToString("\r\n")
        assertEquals(expected, LanPolicy.filterSdp(sdp))
        assertEquals(2, LanPolicy.lanCandidateCount(sdp))
    }

    @Test fun `filterSdp normalises LF line endings`() =
        assertEquals("v=0\r\na=x", LanPolicy.filterSdp("v=0\na=x"))
}
