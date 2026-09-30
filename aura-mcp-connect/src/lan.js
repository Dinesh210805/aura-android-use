/**
 * Finding and talking to the phone on the local network.
 *
 * The phone runs a tiny HTTP endpoint (`LanSignalingServer.kt`) that trades
 * one WebRTC offer for one answer. This module finds it and calls it:
 *   1. the address saved at pairing (or given with --host)
 *   2. an mDNS lookup of `_aura-mcp._tcp.local`
 *   3. a scan of this computer's local /24 subnets
 * Every hit is confirmed with `GET /aura/info` before it is used.
 *
 * - Contract: `isLanIpv4` and `filterSdp` are the same rule as the phone's
 *   `LanPolicy.kt`. Change together: that file, `LanPolicyTest.kt` and
 *   `test/lan.test.mjs`.
 * - Why LAN only: there is no STUN or TURN server and both sides drop every
 *   non-LAN ICE candidate, so the link can only form between machines that
 *   can already reach each other: the same Wi-Fi, the phone's hotspot, or a
 *   private VPN such as Tailscale (its `100.64.0.0/10` addresses count as LAN).
 * - Fails: discovery never throws; it returns an empty list and the caller
 *   explains what to check.
 */
import dgram from "dgram";
import http from "http";
import os from "os";
import crypto from "crypto";

/** Change together: `LanPolicy.PORTS` on the phone. */
export const DEFAULT_PORT = 47821;
export const PORTS = [47821, 47822, 47823, 47824, 47825];

/** Change together: `LanNetwork.SERVICE_TYPE` on the phone (plus `.local`). */
export const SERVICE_NAME = "_aura-mcp._tcp.local";

// ─── The LAN rule ────────────────────────────────────────────────────────

/**
 * True for private IPv4: 10/8, 172.16/12, 192.168/16, link-local 169.254/16
 * and shared/CGNAT 100.64/10 (Tailscale). IPv6 is refused on purpose: a
 * global IPv6 address can be reachable from the internet.
 */
export function isLanIpv4(address) {
  const parts = String(address).split(".");
  if (parts.length !== 4) return false;
  const b = [];
  for (const p of parts) {
    if (!/^\d{1,3}$/.test(p)) return false;
    const n = Number(p);
    if (n > 255) return false;
    b.push(n);
  }
  return (
    b[0] === 10 ||
    (b[0] === 172 && b[1] >= 16 && b[1] <= 31) ||
    (b[0] === 192 && b[1] === 168) ||
    (b[0] === 169 && b[1] === 254) ||
    (b[0] === 100 && b[1] >= 64 && b[1] <= 127)
  );
}

function isLanCandidate(line) {
  const f = line.slice("a=candidate:".length).trim().split(/\s+/);
  if (f.length < 8 || f[6] !== "typ") return false;
  return f[7] === "host" && isLanIpv4(f[4]);
}

/**
 * `sdp` without any `a=candidate` line except LAN IPv4 host candidates. Other
 * lines (including the DTLS fingerprint) are kept; line endings become CRLF.
 */
export function filterSdp(sdp) {
  return String(sdp)
    .split(/\r?\n/)
    .filter((line) => !line.startsWith("a=candidate:") || isLanCandidate(line))
    .join("\r\n");
}

/** How many candidates `filterSdp` keeps. Zero means no shared network. */
export function lanCandidateCount(sdp) {
  return String(sdp)
    .split(/\r?\n/)
    .filter((line) => line.startsWith("a=candidate:") && isLanCandidate(line)).length;
}

// ─── Talking to the phone ────────────────────────────────────────────────

/**
 * One HTTP request to the phone. Resolves {status, json}; rejects on network
 * errors and timeouts. Uses `http` rather than fetch so no `Origin` header is
 * ever sent (the phone refuses requests that carry one).
 */
function request(host, port, method, path, body, timeoutMs) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? null : Buffer.from(JSON.stringify(body), "utf8");
    const req = http.request(
      {
        host,
        port,
        method,
        path,
        headers: payload
          ? { "Content-Type": "application/json", "Content-Length": payload.length }
          : {},
        timeout: timeoutMs,
        agent: false,
      },
      (res) => {
        const chunks = [];
        let size = 0;
        res.on("data", (c) => {
          size += c.length;
          if (size > 256 * 1024) req.destroy(new Error("Response too large"));
          else chunks.push(c);
        });
        res.on("end", () => {
          let json = null;
          try {
            json = JSON.parse(Buffer.concat(chunks).toString("utf8"));
          } catch (e) {
            /* not JSON: treated as not-AURA by callers */
          }
          resolve({ status: res.statusCode, json });
        });
        res.on("error", reject);
      },
    );
    req.on("timeout", () => req.destroy(new Error("timeout")));
    req.on("error", reject);
    if (payload) req.write(payload);
    req.end();
  });
}

/** `GET /aura/info` → {host, port, deviceId, proto}, or null if it isn't an AURA phone. */
export async function probe(host, port, timeoutMs = 1500) {
  try {
    const { status, json } = await request(host, port, "GET", "/aura/info", undefined, timeoutMs);
    if (status !== 200 || json?.service !== "aura-mcp" || typeof json.deviceId !== "string") return null;
    return { host, port, deviceId: json.deviceId, proto: json.proto };
  } catch (e) {
    return null;
  }
}

/**
 * `POST /aura/connect`. Resolves {status, json}. The phone may take a few
 * seconds (it gathers its ICE candidates before answering).
 */
export function postConnect({ host, port }, body, timeoutMs = 15_000) {
  return request(host, port, "POST", "/aura/connect", body, timeoutMs);
}

/** Parses `--host` values: `192.168.1.5`, `192.168.1.5:47822`, `pixel.tailnet.ts.net`. */
export function parseHost(value) {
  const s = String(value).trim();
  const m = /^(.+?)(?::(\d{1,5}))?$/.exec(s);
  if (!m || !m[1]) return null;
  const port = m[2] ? Number(m[2]) : undefined;
  if (port !== undefined && (port < 1 || port > 65535)) return null;
  return { host: m[1], port };
}

// ─── mDNS (one-shot "legacy unicast" query) ──────────────────────────────
// A query sent from an ephemeral port (not 5353) must be answered by unicast
// straight back to that port (RFC 6762 §6.7). That keeps this a plain
// outgoing request: nothing listens on 5353, so no firewall prompt.

function encodeName(name) {
  const parts = name.split(".").filter(Boolean);
  const bufs = parts.map((p) => {
    const b = Buffer.from(p, "utf8");
    return Buffer.concat([Buffer.from([b.length]), b]);
  });
  return Buffer.concat([...bufs, Buffer.from([0])]);
}

/** A DNS query for the PTR records of `name`. */
export function buildMdnsQuery(name = SERVICE_NAME, id = crypto.randomInt(1, 0xffff)) {
  const header = Buffer.alloc(12);
  header.writeUInt16BE(id, 0);
  header.writeUInt16BE(1, 4); // one question
  const tail = Buffer.alloc(4);
  tail.writeUInt16BE(12, 0); // PTR
  tail.writeUInt16BE(1, 2); // IN
  return Buffer.concat([header, encodeName(name), tail]);
}

function readName(buf, offset) {
  const labels = [];
  let pos = offset;
  let end = -1;
  for (let jumps = 0; jumps < 32; ) {
    if (pos >= buf.length) throw new Error("name overruns packet");
    const len = buf[pos];
    if (len === 0) {
      pos += 1;
      break;
    }
    if ((len & 0xc0) === 0xc0) {
      if (pos + 1 >= buf.length) throw new Error("bad pointer");
      if (end < 0) end = pos + 2;
      pos = ((len & 0x3f) << 8) | buf[pos + 1];
      jumps++;
      continue;
    }
    if (pos + 1 + len > buf.length) throw new Error("label overruns packet");
    labels.push(buf.toString("utf8", pos + 1, pos + 1 + len));
    pos += 1 + len;
  }
  return { name: labels.join("."), next: end >= 0 ? end : pos };
}

/**
 * The AURA services in an mDNS response: [{host?, port, deviceId?}]. `host`
 * is the A record for the service's target when present. Malformed packets
 * yield [].
 */
export function parseMdnsResponse(buf) {
  try {
    const qd = buf.readUInt16BE(4);
    const total = buf.readUInt16BE(6) + buf.readUInt16BE(8) + buf.readUInt16BE(10);
    let pos = 12;
    for (let i = 0; i < qd; i++) pos = readName(buf, pos).next + 4;
    const srv = new Map(); // instance → {port, target}
    const txt = new Map(); // instance → {k: v}
    const addr = new Map(); // hostname → ip
    const instances = new Set();
    for (let i = 0; i < total; i++) {
      const { name, next } = readName(buf, pos);
      const type = buf.readUInt16BE(next);
      const rdlen = buf.readUInt16BE(next + 8);
      const rd = next + 10;
      if (rd + rdlen > buf.length) break;
      const lname = name.toLowerCase();
      if (type === 12 && lname === SERVICE_NAME) {
        instances.add(readName(buf, rd).name.toLowerCase());
      } else if (type === 33) {
        srv.set(lname, { port: buf.readUInt16BE(rd + 4), target: readName(buf, rd + 6).name.toLowerCase() });
      } else if (type === 16) {
        const kv = {};
        for (let p = rd; p < rd + rdlen; ) {
          const l = buf[p];
          const s = buf.toString("utf8", p + 1, p + 1 + l);
          const eq = s.indexOf("=");
          if (eq > 0) kv[s.slice(0, eq)] = s.slice(eq + 1);
          p += 1 + l;
        }
        txt.set(lname, kv);
      } else if (type === 1 && rdlen === 4) {
        addr.set(lname, `${buf[rd]}.${buf[rd + 1]}.${buf[rd + 2]}.${buf[rd + 3]}`);
      }
      pos = rd + rdlen;
    }
    const out = [];
    for (const inst of new Set([...instances, ...[...srv.keys()].filter((k) => k.endsWith(SERVICE_NAME))])) {
      const s = srv.get(inst);
      if (!s) continue;
      out.push({ host: addr.get(s.target), port: s.port, deviceId: txt.get(inst)?.id });
    }
    return out;
  } catch (e) {
    return [];
  }
}

/** This computer's usable IPv4 interfaces: [{address, netmask}]. */
function localIpv4() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list ?? []) {
      if (a.internal || (a.family !== "IPv4" && a.family !== 4)) continue;
      out.push({ address: a.address, netmask: a.netmask });
    }
  }
  return out;
}

/** Asks every local interface for AURA phones. Resolves [{host, port, deviceId?}]. */
export function mdnsDiscover({ timeoutMs = 1500 } = {}) {
  return new Promise((resolve) => {
    const found = [];
    const sockets = [];
    const query = buildMdnsQuery();
    for (const { address } of localIpv4()) {
      let sock;
      try {
        sock = dgram.createSocket({ type: "udp4" });
      } catch (e) {
        continue;
      }
      sockets.push(sock);
      sock.on("error", () => {});
      sock.on("message", (msg, rinfo) => {
        for (const s of parseMdnsResponse(msg)) found.push({ ...s, host: s.host ?? rinfo.address });
      });
      sock.bind({ address, port: 0 }, () => {
        try {
          sock.setMulticastInterface(address);
          sock.send(query, 5353, "224.0.0.251");
        } catch (e) {
          /* interface without multicast: skip */
        }
      });
    }
    setTimeout(() => {
      for (const s of sockets) {
        try {
          s.close();
        } catch (e) {
          /* already closed */
        }
      }
      resolve(found);
    }, timeoutMs);
  });
}

// ─── Subnet scan ─────────────────────────────────────────────────────────

/**
 * Hosts to scan: every other address in the /24 (or smaller subnet) of each
 * private, non-Tailscale interface. Tailscale and /32 VPN interfaces are
 * skipped: pass --host for those.
 */
export function scanTargets(interfaces = localIpv4()) {
  const hosts = new Set();
  for (const { address, netmask } of interfaces) {
    if (!isLanIpv4(address) || address.startsWith("169.254.")) continue;
    const a = address.split(".").map(Number);
    if (a[0] === 100) continue; // Tailscale / CGNAT: not a broadcast LAN
    const m = String(netmask ?? "255.255.255.0").split(".").map(Number);
    const prefix = m.reduce((n, o) => n + (o >>> 0).toString(2).replace(/0/g, "").length, 0);
    if (prefix >= 31) continue;
    const bits = Math.max(prefix, 24); // never more than 254 hosts per interface
    const size = 2 ** (32 - bits);
    const ip32 = ((a[0] << 24) | (a[1] << 16) | (a[2] << 8) | a[3]) >>> 0;
    const base = (ip32 & ~(size - 1)) >>> 0;
    for (let i = 1; i < size - 1; i++) {
      const n = (base + i) >>> 0;
      const ip = `${n >>> 24}.${(n >>> 16) & 255}.${(n >>> 8) & 255}.${n & 255}`;
      if (ip !== address) hosts.add(ip);
    }
  }
  return [...hosts];
}

/** Probes `hosts` × `ports` with bounded concurrency. Resolves the AURA phones found. */
export async function scan({ hosts = scanTargets(), ports = [DEFAULT_PORT], timeoutMs = 800, concurrency = 96, stopWhen } = {}) {
  const jobs = [];
  for (const port of ports) for (const host of hosts) jobs.push({ host, port });
  const found = [];
  let next = 0;
  let stop = false;
  async function worker() {
    while (!stop && next < jobs.length) {
      const { host, port } = jobs[next++];
      const hit = await probe(host, port, timeoutMs);
      if (hit) {
        found.push(hit);
        if (stopWhen?.(hit)) stop = true;
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, jobs.length) }, worker));
  return found;
}

// ─── Discovery ───────────────────────────────────────────────────────────

/**
 * Finds AURA phones on the local network.
 *
 * @param {object} [o]
 * @param {string} [o.deviceId]  only this phone; stop at the first match
 * @param {string} [o.host]      try this address first (saved or --host)
 * @param {number} [o.port]      its port, when known
 * @param {boolean} [o.exhaustive] also scan the fallback ports (slower; used by `pair`)
 * @param {(msg: string) => void} [o.log]
 * @returns {Promise<Array<{host: string, port: number, deviceId: string}>>}
 */
export async function discoverPhones({ deviceId, host, port, exhaustive = false, log = () => {} } = {}) {
  const wanted = (p) => p && (!deviceId || p.deviceId === deviceId);
  const unique = (list) => {
    const seen = new Set();
    return list.filter((p) => wanted(p) && !seen.has(p.deviceId) && seen.add(p.deviceId));
  };

  if (host) {
    for (const p of port ? [port] : PORTS) {
      const hit = await probe(host, p);
      if (wanted(hit)) return [hit];
    }
    log(`Phone not answering at ${host}; searching the local network`);
  }

  const viaMdns = [];
  for (const s of await mdnsDiscover()) {
    if (deviceId && s.deviceId && s.deviceId !== deviceId) continue;
    const hit = await probe(s.host, s.port);
    if (wanted(hit)) viaMdns.push(hit);
    if (deviceId && viaMdns.length) return viaMdns.slice(0, 1);
  }
  if (viaMdns.length) return unique(viaMdns);

  log("Scanning the local network for the phone...");
  const stopWhen = deviceId ? (hit) => hit.deviceId === deviceId : undefined;
  let hits = await scan({ stopWhen });
  if (!unique(hits).length && exhaustive) hits = await scan({ ports: PORTS.slice(1), stopWhen });
  return unique(hits);
}
