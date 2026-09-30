/**
 * Regression test for the 2026-07-16 stale-connection incident: the phone app
 * restarted, the WebRTC link died WITHOUT a close event, and the daemon kept
 * serving the dead cached client forever — initialize worked (cached), every
 * forwarded call hung. PhoneManager must detect the dead link via liveness
 * ping and reconnect transparently.
 *
 * Run: node test/stale-link.test.mjs
 */
import assert from "node:assert/strict";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
  ListToolsResultSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { PhoneManager } from "../src/daemon.js";

/** InMemoryTransport wrapper that can die SILENTLY: after kill(), outbound
 *  frames vanish (no error, no close event) — exactly what a dead WebRTC
 *  DataChannel looks like after the peer's process is gone. */
class SilentlyKillableTransport {
  constructor(inner) {
    this._inner = inner;
    this.dead = false;
    inner.onmessage = (m) => {
      if (!this.dead) this.onmessage?.(m);
    };
    inner.onclose = () => this.onclose?.();
    inner.onerror = (e) => this.onerror?.(e);
  }
  async start() {
    return this._inner.start();
  }
  async send(message) {
    if (this.dead) return; // swallow — the void does not answer
    return this._inner.send(message);
  }
  async close() {
    return this._inner.close();
  }
  kill() {
    this.dead = true;
  }
}

let connects = 0;
let liveWrapper = null;

function makeFakePhoneTransport() {
  connects++;
  const phone = new Server(
    { name: "fake-phone", version: "9.9.9" },
    { capabilities: { tools: {} } },
  );
  phone.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: [
      {
        name: "echo",
        description: "Echo",
        inputSchema: { type: "object", properties: {} },
      },
    ],
  }));
  phone.setRequestHandler(CallToolRequestSchema, async () => ({
    content: [{ type: "text", text: `alive-from-connect-${connects}` }],
  }));
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  phone.connect(serverSide);
  liveWrapper = new SilentlyKillableTransport(clientSide);
  return liveWrapper;
}

const manager = new PhoneManager({
  transportFactory: makeFakePhoneTransport,
  livenessMaxAgeMs: 0, // always verify — simulates "link idle past trust window"
  pingTimeoutMs: 1_000,
  log: () => {},
});

// ── 1. Healthy connect works ─────────────────────────────────────────
const client1 = await manager.getClient();
const tools1 = await client1.request({ method: "tools/list" }, ListToolsResultSchema);
assert.equal(tools1.tools[0].name, "echo");
assert.equal(connects, 1);

// ── 2. Link dies silently (phone app restart) ────────────────────────
liveWrapper.kill();

// ── 3. getClient must detect the corpse via ping and reconnect ───────
const t0 = Date.now();
const client2 = await manager.getClient();
const detectMs = Date.now() - t0;
assert.equal(connects, 2, "must have built a fresh connection");
assert.notEqual(client2, client1, "must not hand back the dead client");
assert.ok(detectMs < 5_000, `detection took ${detectMs}ms — should be ~pingTimeout`);

// ── 4. The fresh client actually works ───────────────────────────────
const tools2 = await client2.request({ method: "tools/list" }, ListToolsResultSchema);
assert.equal(tools2.tools[0].name, "echo");
assert.equal(manager.state, "connected");

await manager.close();
console.log("stale-link.test.mjs: ALL PASS");
