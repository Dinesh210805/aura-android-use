/**
 * daemon — ONE process on this computer that owns the single WebRTC link to
 * the phone and serves MCP over Streamable HTTP on localhost.
 *
 * Every MCP client (Claude Code, Claude Desktop, Cursor, VS Code windows…)
 * connects to http://127.0.0.1:<port>/mcp and gets its own MCP session; tool
 * calls are serialized onto the one phone connection. This replaces the old
 * model where each client spawned its own bridge and stole the phone from the
 * previous one ("newest wins" tug-of-war).
 *
 * Endpoints:
 *   POST/GET/DELETE /mcp   Streamable HTTP MCP (per-session)
 *   GET /health            {ok, version, phone, paired, sessions, adbToolEnabled}
 */
import http from "http";
import { randomUUID } from "crypto";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { isInitializeRequest } from "@modelcontextprotocol/sdk/types.js";
import { buildProxyServer, broadcastNotification } from "./proxy-core.js";
import { PhoneTransport } from "./phone-transport.js";
import { loadPairing } from "./config.js";
import { auraAdbTool } from "./adb-tool.js";
import { VERSION } from "./version.js";

export const DEFAULT_PORT = 4816;
const MAX_BODY_BYTES = 8 * 1024 * 1024;

/** How long a link stays trusted after its last proven round-trip. Past
 *  this, getClient() pings before reuse (a phone app restart kills the link
 *  without any close event — see 2026-07-16 stale-connection incident). */
const LIVENESS_MAX_AGE_MS = 20_000;
const PING_TIMEOUT_MS = 5_000;

/** Owns the single connection to the phone: single-flight connect, exclusive
 *  tool-call queue, reconnect-on-demand after a drop. */
export class PhoneManager {
  constructor({
    transportFactory,
    log = () => {},
    livenessMaxAgeMs = LIVENESS_MAX_AGE_MS,
    pingTimeoutMs = PING_TIMEOUT_MS,
  } = {}) {
    this._transportFactory =
      transportFactory ?? (() => new PhoneTransport({ log }));
    this._log = log;
    this._livenessMaxAgeMs = livenessMaxAgeMs;
    this._pingTimeoutMs = pingTimeoutMs;
    this._client = null;
    this._connectPromise = null;
    this._queueTail = Promise.resolve();
    this._closed = false;
    this._lastAliveAt = 0;
    this.state = "disconnected";
    /** Set by the daemon to fan phone notifications out to sessions. */
    this.onNotification = null;
  }

  async getClient() {
    if (this._closed) throw new Error("Daemon is shutting down");
    if (this._client) {
      // A phone app restart or network flip can kill the link without any
      // close event — the cached client then looks healthy while every
      // forwarded request hangs. Verify liveness with a cheap MCP ping
      // when the link hasn't been proven alive recently.
      if (Date.now() - this._lastAliveAt <= this._livenessMaxAgeMs) return this._client;
      try {
        await this._client.ping({ timeout: this._pingTimeoutMs });
        this._lastAliveAt = Date.now();
        return this._client;
      } catch (e) {
        this._log("Cached phone connection is dead (ping failed) — reconnecting");
        const dead = this._client;
        this._client = null;
        this.state = "disconnected";
        try {
          await dead.close();
        } catch (closeErr) {
          /* it's already gone */
        }
      }
    }
    if (!this._connectPromise) {
      this._connectPromise = this._connect().finally(() => {
        this._connectPromise = null;
      });
    }
    return this._connectPromise;
  }

  /** Every successful round-trip proves the link — cheaper than extra pings. */
  markAlive() {
    this._lastAliveAt = Date.now();
  }

  async _connect() {
    this.state = "connecting";
    this._log("Connecting to phone...");
    const transport = await this._transportFactory();
    const client = new Client({ name: "aura-mcp-daemon", version: VERSION });
    client.fallbackNotificationHandler = async (notification) => {
      await this.onNotification?.(notification);
    };
    try {
      // Generous initialize budget: the very first connect can include a
      // human tapping Approve on the phone.
      await client.connect(transport, { timeout: 130_000 });
    } catch (e) {
      this.state = "disconnected";
      throw e;
    }
    client.onclose = () => {
      if (this._client === client) {
        this._client = null;
        this.state = "disconnected";
        this._log("Phone connection closed — will reconnect on next request");
      }
    };
    this._client = client;
    this._lastAliveAt = Date.now();
    this.state = "connected";
    this._log("Phone connected");
    return client;
  }

  /** Serialize work against the single physical screen. */
  runExclusive(fn) {
    const run = this._queueTail.then(fn, fn);
    // Keep the chain alive whether fn resolved or rejected.
    this._queueTail = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  async close() {
    this._closed = true;
    const client = this._client;
    this._client = null;
    this.state = "disconnected";
    if (client) {
      try {
        await client.close();
      } catch (e) {
        /* already gone */
      }
    }
  }
}

function isLoopbackHost(hostHeader) {
  if (!hostHeader) return false;
  // Strip port; tolerate IPv6 brackets.
  const host = hostHeader.replace(/:\d+$/, "").replace(/^\[|\]$/g, "");
  return host === "127.0.0.1" || host === "localhost" || host === "::1";
}

function isAllowedOrigin(origin) {
  if (origin === undefined || origin === "null") return true; // non-browser clients
  try {
    const url = new URL(origin);
    return isLoopbackHost(url.host);
  } catch (e) {
    return false;
  }
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error("Request body too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch (e) {
        reject(new Error("Invalid JSON body"));
      }
    });
    req.on("error", reject);
  });
}

function sendJson(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

function jsonRpcError(res, status, id, code, message) {
  sendJson(res, status, {
    jsonrpc: "2.0",
    error: { code, message },
    id: id ?? null,
  });
}

/**
 * Start the daemon.
 *
 * @param {object} [options]
 * @param {number} [options.port]   0 = ephemeral (tests)
 * @param {string} [options.host]
 * @param {() => Promise<import("@modelcontextprotocol/sdk/shared/transport.js").Transport> | import("@modelcontextprotocol/sdk/shared/transport.js").Transport} [options.phoneTransportFactory]
 * @param {(msg: string) => void} [options.log]
 * @param {boolean} [options.enableAdbTool]  Arm the host-side aura-adb tool. Off
 *   by default: it runs arbitrary adb commands on this machine, so it must be an
 *   explicit act by whoever runs the daemon, not something a client gets for free
 *   by connecting. Resolved from --enable-adb / AURA_MCP_ENABLE_ADB in index.js.
 * @returns {Promise<{port: number, close: () => Promise<void>, phoneManager: PhoneManager}>}
 */
export async function startDaemon({
  port = DEFAULT_PORT,
  host = "127.0.0.1",
  phoneTransportFactory,
  log = () => {},
  enableAdbTool = false,
} = {}) {
  const phoneManager = new PhoneManager({
    transportFactory: phoneTransportFactory,
    log,
  });

  /** sessionId → { transport, server } */
  const sessions = new Map();

  phoneManager.onNotification = (notification) =>
    broadcastNotification(
      [...sessions.values()].map((s) => s.server),
      notification,
    );

  const httpServer = http.createServer(async (req, res) => {
    try {
      // Local-only surface: reject DNS-rebinding / cross-origin browser abuse.
      if (!isLoopbackHost(req.headers.host) || !isAllowedOrigin(req.headers.origin)) {
        sendJson(res, 403, { error: "Forbidden: localhost access only" });
        return;
      }

      const url = new URL(req.url, `http://${req.headers.host}`);

      if (url.pathname === "/health" && req.method === "GET") {
        sendJson(res, 200, {
          ok: true,
          service: "aura-mcp-daemon",
          version: VERSION,
          paired: loadPairing() !== null,
          phone: phoneManager.state,
          sessions: sessions.size,
          adbToolEnabled: enableAdbTool,
        });
        return;
      }

      if (url.pathname !== "/mcp") {
        sendJson(res, 404, { error: "Not found" });
        return;
      }

      const sessionId = req.headers["mcp-session-id"];

      if (req.method === "POST") {
        let body;
        try {
          body = await readJsonBody(req);
        } catch (e) {
          jsonRpcError(res, 400, null, -32700, e.message);
          return;
        }

        // Existing session → route to its transport.
        if (sessionId && sessions.has(sessionId)) {
          await sessions.get(sessionId).transport.handleRequest(req, res, body);
          return;
        }

        // New session — must be an initialize request.
        if (!sessionId && isInitializeRequest(body)) {
          let server;
          try {
            server = await buildProxyServer({
              getClient: () => phoneManager.getClient(),
              runExclusive: (fn) => phoneManager.runExclusive(fn),
              onBackendAlive: () => phoneManager.markAlive(),
              name: "aura-on-device (via daemon)",
              version: VERSION,
              // Executes on THIS machine (wherever the daemon runs), not the
              // phone — see adb-tool.js. The stdio shim builds its own proxy
              // server without this, so it transparently forwards aura-adb
              // calls to here rather than double-registering the tool.
              // Unarmed → not registered at all, so it never even appears in
              // tools/list; a client can't call what it can't discover.
              localTools: enableAdbTool ? [auraAdbTool] : [],
            });
          } catch (e) {
            log(`Session init failed: ${e.message}`);
            jsonRpcError(res, 503, body.id, -32001, e.message);
            return;
          }

          const transport = new StreamableHTTPServerTransport({
            sessionIdGenerator: () => randomUUID(),
            onsessioninitialized: (id) => {
              sessions.set(id, { transport, server });
              log(`Session ${id.slice(0, 8)}… opened (${sessions.size} active)`);
            },
          });
          transport.onclose = () => {
            const id = transport.sessionId;
            if (id && sessions.delete(id)) {
              log(`Session ${id.slice(0, 8)}… closed (${sessions.size} active)`);
            }
          };

          await server.connect(transport);
          await transport.handleRequest(req, res, body);
          return;
        }

        jsonRpcError(
          res,
          400,
          body?.id,
          -32000,
          "Bad Request: unknown session (send initialize without mcp-session-id to start one)",
        );
        return;
      }

      if (req.method === "GET" || req.method === "DELETE") {
        if (!sessionId || !sessions.has(sessionId)) {
          jsonRpcError(res, 400, null, -32000, "Bad Request: no valid session");
          return;
        }
        await sessions.get(sessionId).transport.handleRequest(req, res);
        return;
      }

      sendJson(res, 405, { error: "Method not allowed" });
    } catch (e) {
      log(`HTTP handler error: ${e.stack || e.message}`);
      if (!res.headersSent) {
        jsonRpcError(res, 500, null, -32603, `Internal error: ${e.message}`);
      } else {
        res.end();
      }
    }
  });

  await new Promise((resolve, reject) => {
    httpServer.once("error", reject);
    httpServer.listen(port, host, resolve);
  });
  const boundPort = httpServer.address().port;
  log(`AURA MCP daemon listening on http://${host}:${boundPort}/mcp`);
  if (enableAdbTool) {
    log(
      "aura-adb is ARMED — connected MCP clients can run adb commands on this machine.",
    );
  }

  const close = async () => {
    for (const { transport } of sessions.values()) {
      try {
        await transport.close();
      } catch (e) {
        /* best-effort */
      }
    }
    sessions.clear();
    await phoneManager.close();
    await new Promise((resolve) => httpServer.close(resolve));
  };

  return { port: boundPort, close, phoneManager };
}
