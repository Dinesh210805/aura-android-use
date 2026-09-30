/**
 * The pairing file, ~/.aura/webrtc.json: which phone this computer is paired
 * with and the secret token that proves it.
 *
 * Shape: {deviceId, clientToken, enrolled?, host?, port?}
 * - `deviceId`: the phone's id (`GET /aura/info`), used to recognise it on
 *   the network.
 * - `clientToken`: random secret. Sent once, when the phone first approves
 *   this computer; afterwards only HMAC proofs over it are sent.
 * - `enrolled`: true once the phone has approved the token.
 * - `host`/`port`: where the phone answered last, tried first on reconnect.
 *
 * - Contract: files written by 0.7/0.8 (no host) still work; the phone is
 *   then found by mDNS or a subnet scan and the address is saved.
 */
import fs from "fs";
import path from "path";
import os from "os";

export function pairingFilePath() {
  return path.join(os.homedir(), ".aura", "webrtc.json");
}

export function loadPairing() {
  try {
    const saved = JSON.parse(fs.readFileSync(pairingFilePath(), "utf8"));
    if (saved && saved.deviceId && saved.clientToken) return saved;
  } catch (e) {
    // No pairing file / unreadable — treated as "not paired".
  }
  return null;
}

/** Writes ~/.aura/webrtc.json readable by this user only: it holds the pairing token. */
export function savePairing(pairing) {
  const file = pairingFilePath();
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  fs.writeFileSync(file, JSON.stringify(pairing), { mode: 0o600 });
  try {
    fs.chmodSync(file, 0o600); // writeFileSync's mode only applies when creating the file
  } catch (e) {
    // Not supported on every filesystem (e.g. some Windows setups); best effort.
  }
}
