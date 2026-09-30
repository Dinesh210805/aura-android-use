/**
 * stdio-shim — keeps the classic `aura-mcp` stdio entry point working, but
 * instead of opening its own WebRTC link to the phone (which stole the
 * connection from every other client), it proxies to the shared local daemon,
 * auto-starting it when needed.
 *
 *   IDE ── stdio ── this shim ── HTTP ── daemon ── WebRTC ── phone
 *
 * Any number of shims (one per IDE/window) share the one phone connection.
 */
import { spawn } from "child_process";
import { fileURLToPath } from "url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { buildProxyServer } from "./proxy-core.js";
import { DEFAULT_PORT } from "./daemon.js";

const VERSION = "0.7.0";

async function fetchHealth(port, timeoutMs = 1500) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(`http://127.0.0.1:${port}/health`, {
      signal: controller.signal,
    });
    if (!res.ok) return null;
    const body = await res.json();
    return body?.service === "aura-mcp-daemon" ? body : null;
  } catch (e) {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

async function ensureDaemonRunning(port, log, { enableAdbTool = false } = {}) {
  const existing = await fetchHealth(port);
  if (existing) {
    // A daemon's tool set is fixed at startup, so an already-running one can't
    // be re-armed by a later client asking for adb. Say so — otherwise the user
    // sets AURA_MCP_ENABLE_ADB=1, sees no aura-adb, and has nothing to go on.
    if (enableAdbTool && !existing.adbToolEnabled) {
      log(
        `A daemon is already running on port ${port} with aura-adb DISABLED, so ` +
          `AURA_MCP_ENABLE_ADB had no effect. Stop that daemon and let this ` +
          `client restart it to arm the adb tool.`,
      );
    }
    return;
  }

  log(`Daemon not running — starting it on port ${port}...`);
  const indexPath = fileURLToPath(new URL("./index.js", import.meta.url));
  // Env (and therefore AURA_MCP_ENABLE_ADB) is inherited — deliberately not
  // filtered here, since that inheritance is what lets a client arm the daemon
  // it auto-starts. The argv is fixed, so a CLI flag could never reach here.
  const child = spawn(process.execPath, [indexPath, "daemon", `--port=${port}`], {
    detached: true,
    stdio: "ignore",
    windowsHide: true,
  });
  child.unref();

  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (await fetchHealth(port)) {
      log("Daemon is up.");
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error(
    `Could not start the AURA daemon on port ${port} within 20s. ` +
      `Try running it manually to see why: aura-mcp daemon --port=${port}`,
  );
}

/**
 * Run the stdio shim until the IDE closes stdin or the daemon goes away.
 * @param {object} [options]
 * @param {number} [options.port]
 * @param {(msg: string) => void} [options.log] stderr logger
 * @param {boolean} [options.enableAdbTool] Only used to warn when an already-
 *   running daemon can't honour the request — the shim never registers the
 *   tool itself, it forwards through to whichever daemon owns it.
 */
export async function runStdioShim({
  port = DEFAULT_PORT,
  log = () => {},
  enableAdbTool = false,
} = {}) {
  await ensureDaemonRunning(port, log, { enableAdbTool });

  const client = new Client({ name: "aura-mcp-shim", version: VERSION });
  const httpTransport = new StreamableHTTPClientTransport(
    new URL(`http://127.0.0.1:${port}/mcp`),
  );
  // Long initialize budget: the daemon may be doing the first phone connect
  // (including the on-phone approval tap) behind this call.
  await client.connect(httpTransport, { timeout: 150_000 });

  const server = await buildProxyServer({
    getClient: async () => client,
    name: "aura-on-device",
    version: VERSION,
  });

  // Pass phone/daemon notifications through to the IDE.
  client.fallbackNotificationHandler = async (notification) => {
    try {
      await server.notification(notification);
    } catch (e) {
      /* session closing — drop */
    }
  };

  client.onclose = () => {
    log("Daemon connection closed — exiting so the IDE can reconnect.");
    process.exit(1);
  };

  const stdioTransport = new StdioServerTransport();
  server.onclose = async () => {
    // IDE closed our stdin — release the daemon session and leave cleanly.
    client.onclose = undefined;
    try {
      await client.close();
    } catch (e) {
      /* already gone */
    }
    process.exit(0);
  };

  await server.connect(stdioTransport);
  log(`Bridging stdio ↔ daemon on port ${port}.`);
}
