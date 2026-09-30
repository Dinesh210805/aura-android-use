// End to end over real WebRTC: pair with a PIN, then reconnect with the
// token proof, against a fake phone that speaks the phone's protocol
// (LanSignalingServer.kt + WebRtcTransport.kt). Skips itself when this
// machine has no private IPv4 address, since LAN-only candidates need one.
import assert from "node:assert/strict";
import fs from "fs";
import http from "http";
import os from "os";
import path from "path";
import wrtc from "@roamhq/wrtc";

const lanAddress = Object.values(os.networkInterfaces())
  .flat()
  .find((a) => a && !a.internal && (a.family === "IPv4" || a.family === 4) && /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(a.address))
  ?.address;
if (!lanAddress) {
  console.log("lan-e2e: skipped (no private IPv4 interface on this machine)");
  process.exit(0);
}

const home = fs.mkdtempSync(path.join(os.tmpdir(), "aura-e2e-"));
process.env.HOME = home;
process.env.USERPROFILE = home;

const { filterSdp, lanCandidateCount } = await import("../src/lan.js");
const crypto = await import("../src/pairing-crypto.js");
const { pairPhone } = await import("../src/pair.js");
const { PhoneTransport, PhoneRefusedError } = await import("../src/phone-transport.js");
const { loadPairing } = await import("../src/config.js");

// ─── Fake phone ─────────────────────────────────────────────────────────
function fakePhone({ deviceId = "dev-e2e" } = {}) {
  const state = { pin: "246810", tokens: [], shownCodes: [], peer: null };
  const server = http.createServer((req, res) => {
    const send = (status, body) => {
      res.writeHead(status, { "Content-Type": "application/json", Connection: "close" });
      res.end(JSON.stringify(body));
    };
    if (req.headers.origin) return send(403, { code: "forbidden" });
    if (req.method === "GET" && req.url === "/aura/info") return send(200, { service: "aura-mcp", proto: 2, deviceId });
    if (req.method !== "POST" || req.url !== "/aura/connect") return send(404, { code: "not_found" });
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", async () => {
      const r = JSON.parse(body);
      let auth;
      if (r.tokenHash) {
        if (!state.tokens.some((t) => crypto.tokenHash(t) === r.tokenHash)) return send(403, { code: "not_paired", error: "not paired" });
        auth = { kind: "token", hash: r.tokenHash };
      } else if (r.pin === state.pin) {
        state.pin = "135791"; // single use
        auth = { kind: "pin" };
      } else {
        return send(403, { code: "bad_pin", error: "Wrong PIN." });
      }
      state.peer?.close();
      const pc = new wrtc.RTCPeerConnection({ iceServers: [] });
      state.peer = pc;
      pc.ondatachannel = ({ channel }) => handshake(pc, channel, auth);
      await pc.setRemoteDescription({ type: "offer", sdp: filterSdp(r.offer.sdp) });
      await pc.setLocalDescription(await pc.createAnswer());
      await new Promise((ok) => {
        if (pc.iceGatheringState === "complete") return ok();
        pc.addEventListener("icegatheringstatechange", () => pc.iceGatheringState === "complete" && ok());
        setTimeout(ok, 3000);
      });
      const answer = filterSdp(pc.localDescription.sdp);
      if (!lanCandidateCount(answer)) return send(409, { code: "no_lan" });
      send(200, { answer: { type: "answer", sdp: answer } });
    });
  });

  function handshake(pc, dc, auth) {
    const bindingOf = () =>
      crypto.binding(crypto.extractFingerprint(pc.localDescription.sdp), crypto.extractFingerprint(pc.remoteDescription.sdp));
    let approved = false;
    let challenge = null;
    let enroll = null;
    dc.onmessage = ({ data }) => {
      const text = typeof data === "string" ? data : Buffer.from(data).toString("utf8");
      if (approved) {
        // Echo each JSON-RPC request back as a result.
        for (const line of text.split("\n").filter(Boolean)) {
          const m = JSON.parse(line);
          dc.send(Buffer.from(JSON.stringify({ jsonrpc: "2.0", id: m.id, result: { echo: m.method } }) + "\n"));
        }
        return;
      }
      const m = JSON.parse(text);
      if (m.type === "init") {
        assert.equal(m.proto, 2);
        if (auth.kind === "token" && m.tokenHash !== auth.hash) return dc.send(JSON.stringify({ type: "denied", reason: "mismatch" }));
        const token = state.tokens.find((t) => crypto.tokenHash(t) === m.tokenHash);
        if (token) {
          challenge = { token, nonce: "00112233445566778899aabbccddeeff" };
          return dc.send(JSON.stringify({ type: "challenge", nonce: challenge.nonce }));
        }
        if (auth.kind !== "pin") return dc.send(JSON.stringify({ type: "denied", reason: "not paired" }));
        state.shownCodes.push(crypto.verificationCode(bindingOf())); // "the user compares and approves"
        enroll = m.tokenHash;
        return dc.send(JSON.stringify({ type: "enroll" }));
      }
      if (m.type === "proof" && challenge) {
        const ok = crypto.proofMac(challenge.token, bindingOf(), challenge.nonce) === m.mac;
        approved = ok;
        return dc.send(JSON.stringify(ok ? { type: "approved" } : { type: "denied", reason: "bad proof" }));
      }
      if (m.type === "token" && enroll && crypto.tokenHash(m.clientToken) === enroll) {
        state.tokens.push(m.clientToken);
        approved = true;
        return dc.send(JSON.stringify({ type: "approved" }));
      }
    };
  }

  return new Promise((resolve) =>
    server.listen(0, "0.0.0.0", () => resolve({ state, server, port: server.address().port })),
  );
}

// ─── The flow ───────────────────────────────────────────────────────────
const phone = await fakePhone();
const host = `${lanAddress}:${phone.port}`;

try {
  // A wrong PIN is refused and saves nothing.
  await assert.rejects(pairPhone({ pin: "000000", host }), (e) => e instanceof PhoneRefusedError && e.code === "bad_pin");
  assert.equal(loadPairing(), null);

  // The right PIN pairs; the bridge shows the same code the phone showed.
  const pcCodes = [];
  const paired = await pairPhone({ pin: "246810", host, onVerificationCode: (c) => pcCodes.push(c.replace(" ", "")) });
  assert.equal(paired.deviceId, "dev-e2e");
  assert.deepEqual(pcCodes, phone.state.shownCodes);
  const saved = loadPairing();
  assert.equal(saved.enrolled, true);
  assert.equal(saved.host, lanAddress);
  assert.equal(saved.port, phone.port);
  assert.equal(phone.state.tokens[0], saved.clientToken);
  // Windows has no POSIX file modes, so statSync reports 0o666 whatever chmod set.
  if (process.platform !== "win32") {
    assert.equal(fs.statSync(path.join(home, ".aura", "webrtc.json")).mode & 0o777, 0o600);
  }

  // The PIN was single use.
  await assert.rejects(pairPhone({ pin: "246810", host }), (e) => e.code === "bad_pin");

  // Reconnect from the saved pairing: token proof, no PIN, then MCP bytes flow.
  const t = new PhoneTransport();
  await t.start();
  const reply = new Promise((ok) => (t.onmessage = ok));
  await t.send({ jsonrpc: "2.0", id: 7, method: "tools/list" });
  assert.deepEqual(await reply, { jsonrpc: "2.0", id: 7, result: { echo: "tools/list" } });
  await t.close();

  // A phone that forgot this computer refuses the reconnect with not_paired.
  phone.state.tokens.length = 0;
  await assert.rejects(new PhoneTransport().start(), (e) => e instanceof PhoneRefusedError && e.code === "not_paired");

  console.log("lan-e2e: pair, single-use PIN, reconnect proof and refusal all pass");
} finally {
  phone.state.peer?.close();
  phone.server.close();
  fs.rmSync(home, { recursive: true, force: true });
}
process.exit(0);
