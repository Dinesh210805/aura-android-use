/**
 * MANUAL end-to-end smoke — NOT part of `npm test`.
 *
 * Requires a real phone adb-paired to this machine (`adb devices` must show it).
 * Run: node test/adb-e2e.manual.mjs
 *
 * A real daemon, a real Streamable-HTTP MCP client, and REAL adb against the
 * attached phone. Only the WebRTC phone link is stubbed, which is exactly right
 * — aura-adb never touches it.
 *
 * Worth keeping despite needing hardware: the stubbed unit tests cannot see
 * that `adb shell a; b` chains on the DEVICE (execFile only stops a host shell).
 * This file caught that, and asserts both halves so the docs stay honest.
 *
 * It creates and removes /sdcard/AURA_ADB_E2E on the device.
 */
import assert from "node:assert/strict";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { startDaemon } from "../src/daemon.js";

function fakePhone() {
  const phone = new Server(
    { name: "fake-phone", version: "9.9.9" },
    { capabilities: { tools: {} }, instructions: "PHONE DOCTRINE FIRST." },
  );
  phone.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: [] }));
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  phone.connect(serverSide);
  return clientSide;
}

const ok = (label, cond, extra = "") =>
  console.log(`${cond ? "PASS" : "FAIL"}  ${label}${extra ? ` — ${extra}` : ""}`);

// ── Disarmed daemon: the tool must be invisible ────────────────────────
const off = await startDaemon({ port: 0, phoneTransportFactory: fakePhone, log: () => {} });
{
  const c = new Client({ name: "e2e", version: "1" });
  await c.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${off.port}/mcp`)));
  const names = (await c.listTools()).tools.map((t) => t.name);
  ok("disarmed: aura-adb absent from tools/list", !names.includes("aura-adb"), JSON.stringify(names));
  ok("disarmed: no adb guidance in instructions", !/aura-adb/.test(c.getInstructions() ?? ""));

  let blocked = false;
  try {
    await c.callTool({ name: "aura-adb", arguments: { command: "devices" } });
  } catch (e) {
    blocked = true;
    ok("disarmed: calling it anyway is rejected", true, e.message.slice(0, 60));
  }
  if (!blocked) ok("disarmed: calling it anyway is rejected", false, "IT RAN");
  const h = await fetch(`http://127.0.0.1:${off.port}/health`).then((r) => r.json());
  ok("disarmed: /health adbToolEnabled=false", h.adbToolEnabled === false);
  await c.close();
}
await off.close();

// ── Armed daemon: real adb round-trips ─────────────────────────────────
const on = await startDaemon({
  port: 0,
  phoneTransportFactory: fakePhone,
  log: () => {},
  enableAdbTool: true,
});
const c = new Client({ name: "e2e", version: "1" });
await c.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${on.port}/mcp`)));

const instructions = c.getInstructions();
ok("armed: phone doctrine still first", instructions.startsWith("PHONE DOCTRINE FIRST."));
ok("armed: adb guidance appended", /aura-adb/.test(instructions));
ok("armed: policy_blocked rule present", /policy_blocked/.test(instructions));
ok("armed: tool listed", (await c.listTools()).tools.some((t) => t.name === "aura-adb"));

const call = async (command) =>
  c.callTool({ name: "aura-adb", arguments: { command } });

// REAL adb, REAL phone.
const devices = await call("devices");
ok("REAL adb devices", !devices.isError && /device/.test(devices.content[0].text),
   devices.content[0].text.replace(/\n/g, " | ").slice(0, 70));

const model = await call("shell getprop ro.product.model");
ok("REAL adb shell getprop", !model.isError, model.content[0].text.trim());

const pkgs = await call("shell pm list packages -3");
const count = pkgs.content[0].text.split("\n").filter((l) => l.startsWith("package:")).length;
ok("REAL adb pm list packages -3", !pkgs.isError && count > 0, `${count} third-party packages`);

const auraPresent = /com\.aura/.test(pkgs.content[0].text);
ok("AURA app visible on device", auraPresent);

// The guidance steers agents here — verify truncation actually behaves.
const dump = await call("shell dumpsys window");
const truncated = /…\(truncated, \d+ more chars\)$/.test(dump.content[0].text);
ok("dumpsys window truncates cleanly", !dump.isError,
   truncated ? `TRUNCATED at ${dump.content[0].text.length} chars (guidance is warranted)`
             : `${dump.content[0].text.length} chars, under the cap`);

// Failure path: a bogus subcommand must come back as isError, not a throw.
const bad = await call("this-is-not-a-real-adb-subcommand");
ok("failing adb → isError, not a crash", bad.isError === true,
   bad.content[0].text.split("\n")[0].slice(0, 60));

// Serial targeting: an unknown serial must fail cleanly, proving -s is applied.
process.env.AURA_ADB_SERIAL = "NOSUCHDEVICE";
const { createAdbTool } = await import(
  "../src/adb-tool.js"
);
const serialTool = createAdbTool();
const serialRes = await serialTool.handler({ command: "shell true" });
ok("AURA_ADB_SERIAL targets a device (unknown serial fails)", serialRes.isError === true,
   serialRes.content[0].text.split("\n")[0].slice(0, 60));
delete process.env.AURA_ADB_SERIAL;

// Metacharacters must not run anything on the HOST. (They DO chain inside
// `adb shell`, on the device — that's adb's own behaviour, now documented.)
const { existsSync, rmSync } = await import("node:fs");
const marker = "HOST_PWNED_MARKER";
const injected = await call(`devices; touch ${marker}`);
ok("host shell is never invoked (marker file not created)",
   !existsSync(marker) && injected.isError === true,
   injected.content[0].text.split("\n")[0].slice(0, 60));
if (existsSync(marker)) rmSync(marker);

// Device-side chaining IS real — assert it, so the docs stay honest.
await call("shell touch /sdcard/AURA_ADB_E2E; true");
const devChain = await call("shell ls /sdcard/AURA_ADB_E2E");
ok("device shell DOES chain (documented, not a host bug)",
   /AURA_ADB_E2E/.test(devChain.content[0].text),
   "docs must say: treat `adb shell` as a shell command on the phone");
await call("shell rm -f /sdcard/AURA_ADB_E2E");
const gone = await call("shell ls /sdcard/AURA_ADB_E2E");
ok("e2e artifact cleaned off the device", /No such file/.test(gone.content[0].text));

await c.close();
await on.close();
console.log("\ne2e complete.");
