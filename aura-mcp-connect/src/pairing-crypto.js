/**
 * Pairing handshake crypto (protocol 3), shared contract with the phone's
 * `PairingCrypto.kt`. Both sides must produce byte-identical results; the test
 * vectors in `test/pairing-crypto.test.mjs` are copied into the phone's
 * `PairingCryptoTest.kt`. Change together.
 *
 * Why: the signaling path (plain HTTP on the local network) is treated as
 * untrusted. Whoever can rewrite the SDP on the way can put themselves between
 * the phone and the PC, but they cannot make both sides' DTLS fingerprints
 * match. Everything here binds
 * a decision to those fingerprints:
 *  - first pairing: the PC commits to a nonce (`commitment`) before it sees
 *    the phone's nonce, then reveals it; both sides show
 *    `verificationCode(binding, pcNonce, phoneNonce)` and the user approves on
 *    the phone only if the codes match. The commitment stops a man in the
 *    middle from choosing its values after seeing ours to force equal codes.
 *  - reconnect: the PC proves it holds the pairing token with
 *    HMAC(token, binding | nonce); the token itself is never sent again
 */
import crypto from "crypto";

export const PROTOCOL_VERSION = 3;

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

/** Hex sha256 commitment to `nonce`, sent before the nonce itself. */
export function commitment(nonce) {
  return crypto.createHash("sha256").update(`aura-commit-v3|${nonce}`, "utf8").digest("hex");
}

/** 128-bit random nonce, hex. */
export function newNonce() {
  return crypto.randomBytes(16).toString("hex");
}

/** 6-digit code shown on both screens during first pairing. */
export function verificationCode(bindingString, pcNonce, phoneNonce) {
  const d = crypto
    .createHash("sha256")
    .update(`aura-sas-v3|${bindingString}|${pcNonce}|${phoneNonce}`, "utf8")
    .digest();
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
