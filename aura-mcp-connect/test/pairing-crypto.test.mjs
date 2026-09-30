// Test vectors for the pairing handshake. The same values are asserted by the
// phone's PairingCryptoTest.kt: if one side changes, both tests must change.
import assert from "node:assert/strict";
import {
  extractFingerprint,
  binding,
  verificationCode,
  tokenHash,
  proofMac,
} from "../src/pairing-crypto.js";

const SDP =
  "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n" +
  "a=fingerprint:sha-256 ab:CD:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB\r\n" +
  "a=setup:actpass\r\n";
const TOKEN = "3f2c9b1e-7d4a-4c8e-9a1f-0b6d5e4c3a21";
const NONCE = "00112233445566778899aabbccddeeff";
const B = "aura-pair-v2|AA:BB|CC:DD";

assert.equal(
  extractFingerprint(SDP),
  "AB:CD:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB",
);
assert.equal(extractFingerprint("v=0\r\n"), null);
assert.equal(binding("AA:BB", "CC:DD"), B);
assert.equal(verificationCode(B), "479832");
assert.equal(tokenHash(TOKEN), "4f914ee5edc16944");
assert.equal(
  proofMac(TOKEN, B, NONCE),
  "534d5deeb10774aa218934206ddbbe162caebd2902185c63309bea364035a92e",
);
// A different binding (a man in the middle) must give a different proof.
assert.notEqual(proofMac(TOKEN, binding("AA:BB", "EE:FF"), NONCE), proofMac(TOKEN, B, NONCE));

console.log("pairing-crypto: all vectors match");
