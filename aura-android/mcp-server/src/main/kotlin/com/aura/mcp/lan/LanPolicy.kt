package com.aura.mcp.lan

import java.net.Inet4Address
import java.net.InetAddress

/**
 * The rule that keeps the MCP server on the local network: which peers may reach the signaling
 * port, and which ICE candidates may be offered or accepted.
 *
 * - Contract: pure, no Android APIs. Same rule as `aura-mcp-connect/src/lan.js` (`isLanIpv4`,
 *   `filterSdp`).
 * - Change together: `lan.js`, `LanPolicyTest.kt` and `test/lan.test.mjs`.
 * - Why: the link has no STUN or TURN server, and both sides drop every candidate that isn't a
 *   private IPv4 host address. So the WebRTC connection can only form between machines that can
 *   already reach each other directly: the same Wi-Fi, the phone's hotspot, or a private VPN.
 *   Tailscale works because its addresses are in `100.64.0.0/10`.
 */
object LanPolicy {

    /** First signaling port tried. The bridge scans the same range. */
    const val DEFAULT_PORT = 47821

    /** Ports tried in order when [DEFAULT_PORT] is taken. Change together: `PORTS` in lan.js. */
    val PORTS: IntRange = DEFAULT_PORT..(DEFAULT_PORT + 4)

    /**
     * True for IPv4 addresses that are private to a network: `10/8`, `172.16/12`, `192.168/16`,
     * link-local `169.254/16`, and shared/CGNAT `100.64/10` (Tailscale).
     *
     * IPv6 is refused on purpose: a phone's global IPv6 address can be reachable from the internet.
     */
    fun isLanIpv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        val b = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return false
            b[i] = p.toInt()
            if (b[i] > 255) return false
        }
        return b[0] == 10 ||
            (b[0] == 172 && b[1] in 16..31) ||
            (b[0] == 192 && b[1] == 168) ||
            (b[0] == 169 && b[1] == 254) ||
            (b[0] == 100 && b[1] in 64..127)
    }

    /**
     * True when [peer] may talk to the signaling port: a LAN IPv4 address, or loopback
     * (`adb forward` from a PC over USB).
     */
    fun isLanPeer(peer: InetAddress): Boolean =
        peer.isLoopbackAddress || (peer is Inet4Address && isLanIpv4(peer.hostAddress ?: ""))

    /**
     * [sdp] with every `a=candidate` line removed except LAN IPv4 host candidates.
     *
     * - Contract: other lines are kept byte for byte, including the DTLS fingerprint the pairing
     *   handshake binds to. Line endings are normalised to CRLF.
     */
    fun filterSdp(sdp: String): String =
        sdp.split("\r\n", "\n")
            .filter { line -> !line.startsWith("a=candidate:") || isLanCandidate(line) }
            .joinToString("\r\n")

    /** Number of candidates in [sdp] that [filterSdp] keeps. Zero means no shared network. */
    fun lanCandidateCount(sdp: String): Int =
        sdp.split("\r\n", "\n").count { it.startsWith("a=candidate:") && isLanCandidate(it) }

    /**
     * `a=candidate:<foundation> <component> <transport> <priority> <address> <port> typ <type> …`
     * is kept only when `<type>` is `host` and `<address>` passes [isLanIpv4].
     */
    internal fun isLanCandidate(line: String): Boolean {
        val f = line.removePrefix("a=candidate:").trim().split(Regex("\\s+"))
        if (f.size < 8 || f[6] != "typ") return false
        return f[7] == "host" && isLanIpv4(f[4])
    }
}
