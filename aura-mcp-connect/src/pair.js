/**
 * `aura-mcp pair <PIN>`: finds the phone, runs the first handshake with the
 * PIN, and saves the pairing only once the phone has approved it.
 *
 * - Contract: on success ~/.aura/webrtc.json holds the new pairing and
 *   replaces any earlier one. On failure the earlier pairing is untouched.
 * - Fails: throws a user-actionable error (wrong PIN, locked, not found,
 *   denied, timed out).
 */
import crypto from "crypto";
import { savePairing, pairingFilePath } from "./config.js";
import { discoverPhones, parseHost, probe, PORTS } from "./lan.js";
import { PhoneNotFoundError, PhoneRefusedError, PhoneTransport } from "./phone-transport.js";

/**
 * @param {object} o
 * @param {string} o.pin                 6 digits from AURA → MCP Center
 * @param {string} [o.host]              the phone's address, skipping discovery
 * @param {(msg: string) => void} [o.log]
 * @param {(code: string) => void} [o.onVerificationCode]
 * @returns {Promise<{deviceId: string, host: string, port: number}>}
 */
export async function pairPhone({ pin, host, log = () => {}, onVerificationCode }) {
  if (!/^\d{6}$/.test(String(pin ?? ""))) {
    throw new Error("The PIN is 6 digits, shown in AURA → MCP Center.");
  }

  let phones;
  if (host) {
    const target = parseHost(host);
    if (!target) throw new Error(`Not an address: ${host}`);
    phones = [];
    for (const port of target.port ? [target.port] : PORTS) {
      const hit = await probe(target.host, port, 3000);
      if (hit) {
        phones.push(hit);
        break;
      }
    }
  } else {
    log("Looking for your phone on this network...");
    phones = await discoverPhones({ exhaustive: true, log });
  }
  if (!phones.length) throw new PhoneNotFoundError();

  const clientToken = crypto.randomUUID();
  let lastError = null;
  for (const phone of phones) {
    const transport = new PhoneTransport({
      log,
      pin,
      endpoint: phone,
      pairing: { deviceId: phone.deviceId, clientToken, enrolled: false },
      onVerificationCode,
    });
    try {
      await transport.start();
      await transport.close();
      savePairing({
        deviceId: phone.deviceId,
        clientToken,
        enrolled: true,
        host: phone.host,
        port: phone.port,
      });
      log("Pairing saved to " + pairingFilePath());
      return phone;
    } catch (e) {
      lastError = e;
      // Several AURA phones on one network: a wrong PIN here may be the right PIN on the next.
      if (e instanceof PhoneRefusedError && e.code === "bad_pin" && phones.length > 1) continue;
      throw e;
    }
  }
  throw lastError;
}
