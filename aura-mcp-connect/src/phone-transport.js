/**
 * PhoneTransport — an MCP SDK `Transport` that carries JSON-RPC to the AURA
 * phone over a WebRTC DataChannel, set up over the local network only.
 *
 * Connection (the phone's WebRtcTransport.kt is the source of truth):
 *   1. Find the phone (lan.js: saved address → mDNS → subnet scan).
 *   2. Gather this computer's ICE candidates (no STUN, no TURN), keep only
 *      LAN ones, and POST the complete offer to the phone's /aura/connect
 *      with either the pairing PIN (first time) or the token hash (after).
 *      The phone answers with its complete SDP; nothing trickles.
 *   3. Pairing handshake on the DataChannel, protocol 2. Single-frame
 *      **text** JSON:
 *      PC → {type:"init", proto:2, version, client, host, platform, tokenHash}
 *      Known token: phone → {type:"challenge", nonce}; PC → {type:"proof", mac}
 *      New PC: the user approves on the phone after comparing verification
 *      codes; phone → {type:"enroll"}; PC → {type:"token", clientToken}
 *      Then phone → {type:"approved"} or {type:"denied", reason}.
 *      See pairing-crypto.js for why each step is bound to the DTLS
 *      fingerprints: nothing on the signaling path is trusted.
 *   4. Data plane: newline-delimited JSON-RPC frames, sent as **binary**
 *      chunks of ≤16 KiB; frames are reassembled on the newline.
 *   Every connect makes the phone bind a fresh MCP session, so every
 *   (re)connect needs a fresh `initialize` (the SDK Client does this).
 *
 * This module is a library: it never calls process.exit() — failures surface
 * through start() rejection or the onclose/onerror callbacks.
 */
import wrtc from "@roamhq/wrtc";
import os from "os";
import { loadPairing, savePairing } from "./config.js";
import { discoverPhones, filterSdp, lanCandidateCount, postConnect } from "./lan.js";
import {
  PROTOCOL_VERSION,
  binding,
  extractFingerprint,
  proofMac,
  tokenHash,
  verificationCode,
  commitment,
  newNonce,
} from "./pairing-crypto.js";
import { VERSION } from "./version.js";

const { RTCPeerConnection, RTCSessionDescription } = wrtc;

/** Max bytes per DataChannel send — must match the phone's chunk size. */
const MAX_CHUNK = 16 * 1024;

/** What to check when the phone can't be found. Shared by several errors. */
export const NETWORK_HINTS =
  "Check that:\n" +
  "  • AURA is open on the phone and MCP Center says Running\n" +
  "  • this computer and the phone are on the same Wi-Fi (guest and office Wi-Fi\n" +
  "    often block devices from seeing each other: use the phone's hotspot instead)\n" +
  "  • from another network, both devices are on the same Tailscale network\n" +
  "You can also name the phone's address, shown in MCP Center:\n" +
  "  aura-mcp pair <PIN> --host=<address>";

export class PhoneNotPairedError extends Error {
  constructor() {
    super("Not paired with a phone. Run `aura-mcp pair <PIN>` first (the PIN is in AURA → MCP Center).");
    this.name = "PhoneNotPairedError";
  }
}

export class PhoneNotFoundError extends Error {
  constructor() {
    super(`Couldn't find your phone on this network.\n${NETWORK_HINTS}`);
    this.name = "PhoneNotFoundError";
  }
}

export class PhoneUnreachableError extends Error {
  constructor(stage) {
    super(
      `Phone not reachable (timed out during ${stage}). ` +
        "Keep AURA open on the phone and stay on the same network, then retry.",
    );
    this.name = "PhoneUnreachableError";
  }
}

/** The phone refused /aura/connect. `code` is the phone's machine-readable reason. */
export class PhoneRefusedError extends Error {
  constructor(code, message) {
    super(message || `The phone refused the connection (${code}).`);
    this.name = "PhoneRefusedError";
    this.code = code;
  }
}

export class PhoneDeniedError extends Error {
  constructor(reason) {
    super(`Connection denied by the phone${reason ? `: ${reason}` : "."}`);
    this.name = "PhoneDeniedError";
  }
}

/** Resolves once `pc` has finished gathering candidates, or after `timeoutMs`. */
function gatheringComplete(pc, timeoutMs) {
  if (pc.iceGatheringState === "complete") return Promise.resolve();
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, timeoutMs);
    pc.addEventListener("icegatheringstatechange", () => {
      if (pc.iceGatheringState === "complete") {
        clearTimeout(timer);
        resolve();
      }
    });
  });
}

/**
 * MCP SDK Transport (start/send/close + onmessage/onclose/onerror) over the
 * AURA link. One instance = one connection attempt; build a new one for
 * every reconnect.
 */
export class PhoneTransport {
  /**
   * @param {object} [options]
   * @param {string} [options.clientName] shown in the phone's approval dialog / MCP Center
   * @param {number} [options.openTimeoutMs]     connect + DataChannel-open budget
   * @param {number} [options.approvalTimeoutMs] human-approval budget (first connect only;
   *                                             trusted tokens auto-approve in <1s)
   * @param {(msg: string) => void} [options.log]
   * @param {(code: string) => void} [options.onVerificationCode] called with the
   *   6-digit code when this pairing hasn't been approved on the phone yet; the
   *   user must check the phone shows the same code. Defaults to logging it.
   * @param {string} [options.pin] pair mode: authorise with this PIN instead of
   *   the saved token. Requires `pairing` and `endpoint`; saves nothing.
   * @param {object} [options.pairing] {deviceId, clientToken, enrolled} to use
   *   instead of ~/.aura/webrtc.json
   * @param {{host: string, port: number}} [options.endpoint] skip discovery
   */
  constructor(options = {}) {
    this.onmessage = undefined;
    this.onclose = undefined;
    this.onerror = undefined;

    this._clientName = options.clientName ?? "AURA MCP Bridge";
    this._openTimeoutMs = options.openTimeoutMs ?? 30_000;
    this._approvalTimeoutMs = options.approvalTimeoutMs ?? 120_000;
    this._log = options.log ?? (() => {});
    this._onVerificationCode =
      options.onVerificationCode ??
      ((code) =>
        this._log(
          `Verification code ${code}: approve on the phone only if it shows the same code.`,
        ));
    this._pin = options.pin ?? null;
    this._pairingOverride = options.pairing ?? null;
    this._endpoint = options.endpoint ?? null;

    this._pc = null;
    this._dataChannel = null;
    this._recvBuffer = Buffer.alloc(0);
    this._approved = false;
    this._closed = false;
  }

  async start() {
    const pairing = this._pairingOverride ?? loadPairing();
    if (!pairing) throw new PhoneNotPairedError();
    const { deviceId, clientToken } = pairing;

    let endpoint = this._endpoint;
    if (!endpoint) {
      const [found] = await discoverPhones({
        deviceId,
        host: pairing.host,
        port: pairing.port,
        log: this._log,
      });
      if (!found) throw new PhoneNotFoundError();
      endpoint = found;
    }
    this._log(`Phone at ${endpoint.host}:${endpoint.port}`);

    const pc = new RTCPeerConnection({ iceServers: [] }); // LAN only: no STUN, no TURN
    this._pc = pc;

    const dataChannel = pc.createDataChannel("mcp", { ordered: true });
    this._dataChannel = dataChannel;

    // Surface silent link death: a phone app restart / network flip often
    // kills the peer WITHOUT a DataChannel close event. ICE state is the
    // reliable low-level signal — treat failed/closed as gone immediately,
    // and 'disconnected' as gone if it doesn't recover within a grace period.
    let iceGraceTimer = null;
    pc.oniceconnectionstatechange = () => {
      const state = pc.iceConnectionState;
      this._log(`ICE state: ${state}`);
      if (state === "failed" || state === "closed") {
        this._teardown();
        this._handleClosed();
      } else if (state === "disconnected") {
        clearTimeout(iceGraceTimer);
        iceGraceTimer = setTimeout(() => {
          if (pc.iceConnectionState === "disconnected") {
            this._log("ICE stuck in disconnected — closing link");
            this._teardown();
            this._handleClosed();
          }
        }, 10_000);
      } else {
        clearTimeout(iceGraceTimer);
      }
    };

    // ── Handshake: wait for DataChannel open, then device approval ────
    let openTimer = null;
    let approvalTimer = null;
    // First pairing: committed in `init`, revealed only after the phone sends its own nonce.
    const pcNonce = newNonce();
    let revealed = false;
    let codeShown = false;
    const approved = new Promise((resolve, reject) => {
      openTimer = setTimeout(() => {
        reject(new PhoneUnreachableError("the WebRTC connect"));
      }, this._openTimeoutMs);

      dataChannel.onopen = () => {
        clearTimeout(openTimer);
        openTimer = null;
        this._log("DataChannel open — requesting device approval");
        const bound = this._binding();
        if (!bound) {
          reject(new PhoneUnreachableError("reading the connection fingerprints"));
          return;
        }
        dataChannel.send(
          JSON.stringify({
            type: "init",
            proto: PROTOCOL_VERSION,
            version: VERSION,
            client: this._clientName,
            host: os.hostname(),
            platform: process.platform,
            tokenHash: tokenHash(clientToken),
            commit: commitment(pcNonce),
          }),
        );
        approvalTimer = setTimeout(() => {
          reject(new PhoneUnreachableError("device approval (tap Approve on the phone)"));
        }, this._approvalTimeoutMs);
      };

      dataChannel.onmessage = (event) => {
        if (this._approved) {
          this._handleData(event.data);
          return;
        }
        // Control plane: single-frame text JSON from the phone.
        const text =
          typeof event.data === "string"
            ? event.data
            : Buffer.from(event.data).toString("utf8");
        let msg;
        try {
          msg = JSON.parse(text);
        } catch (e) {
          return; // Not a control frame — ignore during handshake.
        }
        if (msg.type === "challenge") {
          const bound = this._binding();
          dataChannel.send(
            JSON.stringify({
              type: "proof",
              mac: bound ? proofMac(clientToken, bound, String(msg.nonce ?? "")) : "",
            }),
          );
        } else if (msg.type === "sas" && !revealed && this._pin) {
          // The phone has committed to its nonce by sending it; now reveal ours.
          revealed = true;
          const bound = this._binding();
          if (!bound) return;
          dataChannel.send(JSON.stringify({ type: "reveal", nonce: pcNonce }));
          const code = verificationCode(bound, pcNonce, String(msg.nonce ?? ""));
          this._onVerificationCode(`${code.slice(0, 3)} ${code.slice(3)}`);
          codeShown = true;
        } else if (msg.type === "enroll") {
          // The token goes out only while pairing with a PIN, after the user has
          // been shown a code to compare. Anyone else asking for it (a man in
          // the middle of a reconnect, or one skipping the code) gets nothing.
          if (!this._pin || !codeShown) {
            if (approvalTimer) clearTimeout(approvalTimer);
            reject(
              new PhoneDeniedError(
                "the phone asked for this computer's pairing token without the verification step. " +
                  "Update AURA on the phone, then pair again",
              ),
            );
            return;
          }
          dataChannel.send(JSON.stringify({ type: "token", clientToken }));
        } else if (msg.type === "approved") {
          this._approved = true;
          if (approvalTimer) clearTimeout(approvalTimer);
          resolve();
        } else if (msg.type === "denied") {
          if (approvalTimer) clearTimeout(approvalTimer);
          reject(new PhoneDeniedError(msg.reason));
        }
      };

      dataChannel.onclose = () => {
        if (openTimer) clearTimeout(openTimer);
        if (approvalTimer) clearTimeout(approvalTimer);
        if (!this._approved) {
          reject(new PhoneUnreachableError("the WebRTC connect (channel closed early)"));
        }
        this._handleClosed();
      };

      dataChannel.onerror = (error) => {
        this._log(`DataChannel error: ${error?.message ?? error}`);
        this.onerror?.(error instanceof Error ? error : new Error(String(error)));
      };
    });
    approved.catch(() => {}); // handled below; avoids an unhandled rejection while signaling

    try {
      // ── Signaling: one HTTP round trip with complete SDPs ────────────
      await pc.setLocalDescription(await pc.createOffer());
      await gatheringComplete(pc, 3_000);
      const offerSdp = filterSdp(pc.localDescription.sdp);
      if (lanCandidateCount(offerSdp) === 0) {
        throw new PhoneRefusedError(
          "no_lan",
          `This computer has no local-network address the phone could reach.\n${NETWORK_HINTS}`,
        );
      }

      this._log("Sending the connection offer to the phone");
      let res;
      try {
        res = await postConnect(endpoint, {
          ...(this._pin ? { pin: String(this._pin) } : { tokenHash: tokenHash(clientToken) }),
          offer: { type: "offer", sdp: offerSdp },
        });
      } catch (e) {
        throw new PhoneUnreachableError(`the connect request (${e.message})`);
      }
      if (res.status !== 200 || typeof res.json?.answer?.sdp !== "string") {
        throw new PhoneRefusedError(res.json?.code ?? `http_${res.status}`, res.json?.error);
      }
      await pc.setRemoteDescription(
        new RTCSessionDescription({ type: "answer", sdp: filterSdp(res.json.answer.sdp) }),
      );

      await approved;
    } catch (e) {
      if (openTimer) clearTimeout(openTimer);
      if (approvalTimer) clearTimeout(approvalTimer);
      this._teardown();
      throw e;
    }

    if (!this._pin) {
      // Remember where the phone answered, and that it approved us.
      const moved = pairing.host !== endpoint.host || pairing.port !== endpoint.port;
      if ((moved || !pairing.enrolled) && !this._pairingOverride) {
        savePairing({ ...pairing, enrolled: true, host: endpoint.host, port: endpoint.port });
      }
    }
    this._endpoint = endpoint;
    this._log("Connected and approved by device");
  }

  /** Where the phone answered, once start() has resolved. */
  get endpoint() {
    return this._endpoint;
  }

  /** @param {import("@modelcontextprotocol/sdk/types.js").JSONRPCMessage} message */
  async send(message) {
    const dc = this._dataChannel;
    if (!dc || dc.readyState !== "open" || !this._approved) {
      throw new Error("Phone connection is not open");
    }
    const buf = Buffer.from(JSON.stringify(message) + "\n", "utf8");
    for (let i = 0; i < buf.length; i += MAX_CHUNK) {
      dc.send(buf.subarray(i, Math.min(i + MAX_CHUNK, buf.length)));
    }
  }

  async close() {
    this._teardown();
    this._handleClosed();
  }

  // ── Internals ───────────────────────────────────────────────────────

  /** binding(phoneFingerprint, pcFingerprint) for this peer, or null. */
  _binding() {
    const phoneFp = extractFingerprint(this._pc?.remoteDescription?.sdp);
    const pcFp = extractFingerprint(this._pc?.localDescription?.sdp);
    return phoneFp && pcFp ? binding(phoneFp, pcFp) : null;
  }

  _handleData(data) {
    const chunk =
      typeof data === "string" ? Buffer.from(data, "utf8") : Buffer.from(data);
    this._recvBuffer = Buffer.concat([this._recvBuffer, chunk]);

    let newlineIndex;
    while ((newlineIndex = this._recvBuffer.indexOf(0x0a)) !== -1) {
      const line = this._recvBuffer.subarray(0, newlineIndex).toString("utf8");
      this._recvBuffer = this._recvBuffer.subarray(newlineIndex + 1);
      if (!line.trim()) continue;
      try {
        this.onmessage?.(JSON.parse(line));
      } catch (e) {
        this.onerror?.(new Error(`Failed to parse frame from phone: ${e.message}`));
      }
    }
  }

  _teardown() {
    try {
      this._dataChannel?.close();
    } catch (e) {
      /* already closed */
    }
    try {
      this._pc?.close();
    } catch (e) {
      /* already closed */
    }
    this._dataChannel = null;
    this._pc = null;
  }

  _handleClosed() {
    if (this._closed) return;
    this._closed = true;
    this.onclose?.();
  }
}
