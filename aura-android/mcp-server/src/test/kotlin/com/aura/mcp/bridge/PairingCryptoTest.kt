package com.aura.mcp.bridge

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Test vectors shared with `aura-mcp-connect/test/pairing-crypto.test.mjs`.
 *
 * Change together: if a value here changes, the JS test must change the same way, or the phone
 * and the bridge will stop agreeing and pairing will fail.
 */
class PairingCryptoTest {

    private val sdp =
        "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n" +
            "a=fingerprint:sha-256 ab:CD:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB\r\n" +
            "a=setup:actpass\r\n"
    private val token = "3f2c9b1e-7d4a-4c8e-9a1f-0b6d5e4c3a21"
    private val nonce = "00112233445566778899aabbccddeeff"
    private val binding = "aura-pair-v2|AA:BB|CC:DD"

    @Test fun `extracts and upper-cases the sha-256 fingerprint`() {
        assertEquals(
            "AB:CD:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB",
            PairingCrypto.extractFingerprint(sdp),
        )
        assertNull(PairingCrypto.extractFingerprint("v=0\r\n"))
        assertNull(PairingCrypto.extractFingerprint(null))
    }

    @Test fun `binding matches the bridge`() =
        assertEquals(binding, PairingCrypto.binding("AA:BB", "CC:DD"))

    @Test fun `verification code matches the bridge`() =
        assertEquals("479832", PairingCrypto.verificationCode(binding))

    @Test fun `token hash matches the bridge`() =
        assertEquals("4f914ee5edc16944", PairingCrypto.tokenHash(token))

    @Test fun `proof mac matches the bridge`() =
        assertEquals(
            "534d5deeb10774aa218934206ddbbe162caebd2902185c63309bea364035a92e",
            PairingCrypto.proofMac(token, binding, nonce),
        )

    @Test fun `a man in the middle changes the proof`() =
        assertNotEquals(
            PairingCrypto.proofMac(token, binding, nonce),
            PairingCrypto.proofMac(token, PairingCrypto.binding("AA:BB", "EE:FF"), nonce),
        )

    @Test fun `pins and nonces are well formed`() {
        repeat(200) {
            val pin = PairingCrypto.newPin()
            assertTrue(pin.length == 6 && pin.all(Char::isDigit), pin)
        }
        assertTrue(PairingCrypto.newNonce().matches(Regex("[0-9a-f]{32}")))
    }
}
