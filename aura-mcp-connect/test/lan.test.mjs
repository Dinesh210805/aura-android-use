// The LAN rule and the discovery parsers. The address and SDP cases are the
// same as the phone's LanPolicyTest.kt: if one side changes, both must.
import assert from "node:assert/strict";
import {
  buildMdnsQuery,
  filterSdp,
  isLanIpv4,
  lanCandidateCount,
  parseHost,
  parseMdnsResponse,
  scanTargets,
  SERVICE_NAME,
} from "../src/lan.js";

for (const a of ["10.0.0.1", "172.16.5.4", "172.31.255.255", "192.168.1.23", "169.254.10.10", "100.64.0.1", "100.127.255.254"]) {
  assert.equal(isLanIpv4(a), true, a);
}
for (const a of ["8.8.8.8", "172.15.0.1", "172.32.0.1", "100.63.255.255", "100.128.0.1", "127.0.0.1",
  "192.169.0.1", "256.1.1.1", "10.0.0", "10.0.0.1.2", "fd00::1", "fe80::1", "", "a.b.c.d", "010.0.0.1x"]) {
  assert.equal(isLanIpv4(a), false, a);
}

const sdp = [
  "v=0",
  "a=fingerprint:sha-256 AB:CD",
  "a=candidate:1 1 udp 2122260223 192.168.1.23 50000 typ host generation 0",
  "a=candidate:2 1 udp 2122260223 8.8.4.4 50001 typ host generation 0",
  "a=candidate:3 1 udp 1686052607 203.0.113.9 50002 typ srflx raddr 192.168.1.23 rport 50000",
  "a=candidate:4 1 udp 41885439 10.1.2.3 3478 typ relay raddr 0.0.0.0 rport 0",
  "a=candidate:5 1 udp 2122262783 2001:db8::1 50003 typ host generation 0",
  "a=candidate:6 1 tcp 1518280447 100.101.102.103 9 typ host tcptype active",
  "a=candidate:7 1 udp 2122260223 abcd-1234.local 50004 typ host",
  "a=end-of-candidates",
  "",
].join("\r\n");
assert.equal(
  filterSdp(sdp),
  [
    "v=0",
    "a=fingerprint:sha-256 AB:CD",
    "a=candidate:1 1 udp 2122260223 192.168.1.23 50000 typ host generation 0",
    "a=candidate:6 1 tcp 1518280447 100.101.102.103 9 typ host tcptype active",
    "a=end-of-candidates",
    "",
  ].join("\r\n"),
);
assert.equal(lanCandidateCount(sdp), 2);
assert.equal(filterSdp("v=0\na=x"), "v=0\r\na=x");

assert.deepEqual(parseHost("192.168.1.5"), { host: "192.168.1.5", port: undefined });
assert.deepEqual(parseHost("192.168.1.5:47822"), { host: "192.168.1.5", port: 47822 });
assert.deepEqual(parseHost("pixel.tailnet.ts.net"), { host: "pixel.tailnet.ts.net", port: undefined });
assert.equal(parseHost("1.2.3.4:99999"), null);

// Subnet scan targets: the /24 around each private interface, minus itself;
// Tailscale, link-local, public and /32 interfaces are skipped.
const targets = scanTargets([
  { address: "192.168.1.23", netmask: "255.255.255.0" },
  { address: "100.101.102.103", netmask: "255.192.0.0" },
  { address: "169.254.3.4", netmask: "255.255.0.0" },
  { address: "192.0.2.2", netmask: "255.255.255.0" },
  { address: "10.8.0.2", netmask: "255.255.255.255" },
  { address: "10.20.30.40", netmask: "255.255.0.0" },
  { address: "172.20.10.2", netmask: "255.255.255.240" },
]);
assert.equal(targets.length, 253 + 253 + 13);
assert.ok(targets.includes("192.168.1.1") && targets.includes("192.168.1.254"));
assert.ok(!targets.includes("192.168.1.23") && !targets.includes("192.168.1.0") && !targets.includes("192.168.1.255"));
assert.ok(targets.includes("10.20.30.1") && !targets.includes("10.20.31.1"));
assert.ok(targets.includes("172.20.10.1") && targets.includes("172.20.10.14") && !targets.includes("172.20.10.15"));

// mDNS: the query asks for PTR _aura-mcp._tcp.local, and a response carrying
// PTR + SRV + TXT + A (with name compression) parses into host/port/deviceId.
const q = buildMdnsQuery(SERVICE_NAME, 0x1234);
assert.equal(q.readUInt16BE(0), 0x1234);
assert.equal(q.readUInt16BE(4), 1);
assert.equal(q.subarray(12, q.length - 4).toString("latin1"), "\x09_aura-mcp\x04_tcp\x05local\x00");
assert.equal(q.readUInt16BE(q.length - 4), 12);

function name(labels) {
  return Buffer.concat([...labels.map((l) => Buffer.concat([Buffer.from([l.length]), Buffer.from(l)])), Buffer.from([0])]);
}
function rr(nameBuf, type, rdata) {
  const h = Buffer.alloc(10);
  h.writeUInt16BE(type, 0);
  h.writeUInt16BE(1, 2);
  h.writeUInt32BE(120, 4);
  h.writeUInt16BE(rdata.length, 8);
  return Buffer.concat([nameBuf, h, rdata]);
}
const header = Buffer.alloc(12);
header.writeUInt16BE(0x1234, 0);
header.writeUInt16BE(0x8400, 2);
header.writeUInt16BE(1, 6); // 1 answer
header.writeUInt16BE(3, 10); // 3 additionals
const svc = name(["_aura-mcp", "_tcp", "local"]); // at offset 12
const ptrTarget = Buffer.concat([Buffer.from([13]), Buffer.from("AURA-1234abcd"), Buffer.from([0xc0, 12])]); // "AURA-1234abcd" + pointer to svc
const instOffset = 12 + svc.length + 10; // where ptrTarget lands in the packet
const instPtr = Buffer.from([0xc0, instOffset]);
const srvData = Buffer.alloc(6);
srvData.writeUInt16BE(47822, 4);
const host = name(["android-phone", "local"]);
const txtEntry = (s) => Buffer.concat([Buffer.from([s.length]), Buffer.from(s)]);
const packet = Buffer.concat([
  header,
  rr(svc, 12, ptrTarget),
  rr(instPtr, 33, Buffer.concat([srvData, host])),
  rr(instPtr, 16, Buffer.concat([txtEntry("id=dev-42"), txtEntry("proto=2")])),
  rr(host, 1, Buffer.from([192, 168, 1, 23])),
]);
assert.deepEqual(parseMdnsResponse(packet), [{ host: "192.168.1.23", port: 47822, deviceId: "dev-42" }]);
assert.deepEqual(parseMdnsResponse(Buffer.from([1, 2, 3])), []);

console.log("lan: all checks pass");
