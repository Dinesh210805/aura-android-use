/**
 * Full-chain shim test — no phone required.
 *
 * stdio client (this test) ── spawns `aura-mcp` shim ── HTTP ── daemon
 * (in-process, fake phone) ── InMemoryTransport ── fake phone server.
 *
 * Run: node test/shim.test.mjs
 */
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { startDaemon } from "../src/daemon.js";

const FAKE_INSTRUCTIONS = "FAKE PHONE INSTRUCTIONS";

function makeFakePhoneTransport() {
  const phone = new Server(
    { name: "fake-phone", version: "9.9.9" },
    { capabilities: { tools: {} }, instructions: FAKE_INSTRUCTIONS },
  );
  phone.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: [
      {
        name: "echo",
        description: "Echoes text back.",
        inputSchema: {
          type: "object",
          properties: { text: { type: "string" } },
          required: ["text"],
        },
      },
    ],
  }));
  phone.setRequestHandler(CallToolRequestSchema, async (req) => ({
    content: [{ type: "text", text: `echo: ${req.params.arguments?.text}` }],
  }));
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  phone.connect(serverSide);
  return clientSide;
}

// Armed, so this also proves the daemon's locally-appended adb guidance and
// tool survive the SECOND proxy hop. The shim registers no local tools of its
// own — it must forward whatever the daemon advertises, instructions included.
const daemon = await startDaemon({
  port: 0,
  phoneTransportFactory: makeFakePhoneTransport,
  log: () => {},
  enableAdbTool: true,
});

const indexPath = fileURLToPath(new URL("../src/index.js", import.meta.url));

const client = new Client({ name: "shim-test-client", version: "0.0.1" });
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [indexPath, `--port=${daemon.port}`],
  stderr: "ignore",
});

try {
  await client.connect(transport);

  const instructions = client.getInstructions();
  assert.ok(
    instructions.startsWith(FAKE_INSTRUCTIONS),
    "instructions must survive the double proxy (shim → daemon → phone)",
  );
  assert.match(
    instructions,
    /aura-adb/,
    "the daemon's local adb guidance must reach a stdio client too",
  );
  assert.match(
    instructions,
    /policy_blocked/,
    "the do-not-route-around-a-refusal rule must survive both hops",
  );

  const tools = await client.listTools();
  assert.deepEqual(
    tools.tools.map((t) => t.name).sort(),
    ["aura-adb", "echo"],
    "the shim forwards the daemon's local tool without re-registering it",
  );

  const result = await client.callTool({
    name: "echo",
    arguments: { text: "via-shim" },
  });
  assert.equal(result.content[0].text, "echo: via-shim");

  await client.close();
  console.log("shim.test.mjs: ALL PASS");
} finally {
  await daemon.close();
}
