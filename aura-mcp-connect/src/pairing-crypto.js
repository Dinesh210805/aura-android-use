/**
 * Pairing handshake crypto (protocol 2), shared contract with the phone's
 * `PairingCrypto.kt`. Both sides must produce byte-identical results; the test
 * vectors in `test/pairing-crypto.test.mjs` are copied into the phone's
 * `PairingCryptoTest.kt`. Change together.
 *
 * Why: the signaling path (plain HTTP on the local network) is treated as
 * untrusted. Whoever can rewrite the SDP on the way can put themselves between
 * the phone and the PC, but they cannot make both sides' DTLS fingerprints
 * match. Everything here binds
 * a decision to those fingerprints:
 *  - first pairing: both sides show `verificationCode(binding)`; the user
 *    approves on the phone only if the codes match
 *  - reconnect: the PC proves it holds the pairing token with
 *    HMAC(token, binding | nonce); the token itself is never sent again
 */
import crypto from "crypto";

export const PROTOCOL_VERSION = 2;

/**
 * The `a=fingerprint:sha-256 …` value from an SDP, upper-cased, or null.
 * @param {string} sdp
 */
export function extractFingerprint(sdp) {
  const m = /^a=fingerprint:sha-256\s+([0-9A-Fa-f:]+)\s*$/m.exec(sdp || "");
  return m ? m[1].toUpperCase() : null;
}

/** The string both sides sign. Order is fixed: phone first, PC second. */
export function binding(phoneFingerprint, pcFingerprint) {
  return `aura-pair-v2|${phoneFingerprint}|${pcFingerprint}`;
}

/** 6-digit code shown on both screens during first pairing. */
export function verificationCode(bindingString) {
  const d = crypto.createHash("sha256").update(bindingString, "utf8").digest();
  const n = d.readUInt32BE(0) % 1_000_000;
  return String(n).padStart(6, "0");
}

/** Public identifier of a pairing token: first 16 hex chars of sha256(token). */
export function tokenHash(token) {
  return crypto.createHash("sha256").update(token, "utf8").digest("hex").slice(0, 16);
}

/** Hex HMAC-SHA256(key = token, msg = binding + "|" + nonce). */
export function proofMac(token, bindingString, nonce) {
  return crypto
    .createHmac("sha256", Buffer.from(token, "utf8"))
    .update(`${bindingString}|${nonce}`, "utf8")
    .digest("hex");
}
