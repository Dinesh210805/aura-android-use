package com.aura.mcp.bridge

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto for the PC ↔ phone pairing handshake (protocol 3).
 *
 * - Contract: byte-identical to `aura-mcp-connect/src/pairing-crypto.js`. Both sides are checked
 *   against the same vectors (`PairingCryptoTest.kt`, `test/pairing-crypto.test.mjs`).
 * - Change together: those two files and their tests.
 * - Why: the signaling path (plain HTTP on the local network, [com.aura.mcp.lan.LanSignalingServer])
 *   is untrusted. Anyone who can rewrite the SDP on the way can sit between phone and PC, but
 *   can't make both sides see the same pair of DTLS fingerprints.
 *   Every trust decision is bound to those fingerprints:
 *   - first pairing: the PC sends [commitment] to its nonce before it sees the phone's nonce,
 *     then reveals it. Phone and PC both show [verificationCode] over the binding and both
 *     nonces; the user approves only if they match. The commitment is what makes 6 digits
 *     enough: without it, whoever sits in the middle could choose its own values last and
 *     search for a pair of fingerprints that gives equal codes.
 *   - reconnect: the PC proves it holds the pairing token with [proofMac] over the binding and a
 *     fresh nonce. The token itself is never sent again.
 */
object PairingCrypto {

    const val PROTOCOL_VERSION = 3

    private val secureRandom = SecureRandom()
    private val FINGERPRINT = Regex("""(?m)^a=fingerprint:sha-256\s+([0-9A-Fa-f:]+)\s*$""")

    /** The `a=fingerprint:sha-256` value of [sdp], upper-cased, or null when absent. */
    fun extractFingerprint(sdp: String?): String? =
        sdp?.let { FINGERPRINT.find(it)?.groupValues?.get(1)?.uppercase() }

    /** The string both sides authenticate. Order is fixed: phone first, PC second. */
    fun binding(phoneFingerprint: String, pcFingerprint: String): String =
        "aura-pair-v2|$phoneFingerprint|$pcFingerprint"

    /** Hex sha256 commitment to [nonce]; the PC sends it before revealing the nonce. */
    fun commitment(nonce: String): String = hex(sha256("aura-commit-v3|$nonce".toByteArray(Charsets.UTF_8)))

    /** 6-digit code shown on both screens during first pairing. */
    fun verificationCode(binding: String, pcNonce: String, phoneNonce: String): String {
        val d = sha256("aura-sas-v3|$binding|$pcNonce|$phoneNonce".toByteArray(Charsets.UTF_8))
        val n = ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or
            ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    /** Public id of a pairing token: first 16 hex chars of sha256(token). Safe to send and log. */
    fun tokenHash(token: String): String = hex(sha256(token.toByteArray(Charsets.UTF_8))).take(16)

    /** Hex HMAC-SHA256(key = token, message = "$binding|$nonce"). */
    fun proofMac(token: String, binding: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hex(mac.doFinal("$binding|$nonce".toByteArray(Charsets.UTF_8)))
    }

    /** Constant-time comparison for [proofMac] results. */
    fun macEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** 128-bit random nonce, hex. */
    fun newNonce(): String = ByteArray(16).also(secureRandom::nextBytes).let(::hex)

    /** Uniform 6-digit pairing PIN (000000–999999) from a secure RNG. */
    fun newPin(): String = secureRandom.nextInt(1_000_000).toString().padStart(6, '0')

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
