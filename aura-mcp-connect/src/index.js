#!/usr/bin/env node
/**
 * aura-mcp-connect — connect any MCP client to AURA's on-device server.
 *
 * Architecture (v0.6): ONE daemon per computer owns the WebRTC link to the
 * phone and serves MCP over Streamable HTTP on localhost. Clients either
 * point at the URL directly (preferred) or launch this stdio shim, which
 * auto-starts the daemon and proxies to it. Any number of clients share the
 * single phone connection — no more "newest client kicks the previous one".
 *
 * Modes:
 *   aura-mcp                    stdio shim → daemon (auto-starts it)  [default]
 *   aura-mcp daemon             run the daemon in the foreground
 *   aura-mcp pair <pin>         pair this computer with the phone, then exit
 *   aura-mcp --pin=123456       pair, then run the stdio shim
 *
 * The phone must be on the same local network (same Wi-Fi, its hotspot, or a
 * private VPN such as Tailscale). --host=<address> skips discovery.
 *
 * Options: --port=<n> (or AURA_MCP_PORT env) — daemon port, default 4816.
 *          --enable-adb (or AURA_MCP_ENABLE_ADB=1) — arm the host-side
 *          `aura-adb` tool. Off by default; see adb-tool.js.
 *
 * All log output goes to stderr — stdout is reserved for MCP JSON-RPC frames.
 */

import { VERSION } from "./version.js";

// ─── Logging ──────────────────────────────────────────────────────────
// Stderr only — stdout is reserved for MCP JSON-RPC frames.

function log(...parts) {
  process.stderr.write(`[aura-mcp-connect] ${parts.join(" ")}\n`);
}

function fatal(message, code = 1) {
  process.stderr.write(`[aura-mcp-connect] FATAL: ${message}\n`);
  process.exit(code);
}

// ─── Argument parsing ─────────────────────────────────────────────────

function parseArgs(argv) {
  const out = { _: [] };
  for (const raw of argv) {
    if (!raw.startsWith("--")) {
      out._.push(raw);
      continue;
    }
    const eq = raw.indexOf("=");
    if (eq === -1) out[raw.slice(2)] = "true";
    else out[raw.slice(2, eq)] = raw.slice(eq + 1);
  }
  return out;
}

function resolvePort(args) {
  const raw = args.port ?? process.env.AURA_MCP_PORT;
  if (raw === undefined) return undefined; // module default (4816)
  const port = Number(raw);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    fatal(`Invalid port: ${raw}`);
  }
  return port;
}

/** Arm the host-side aura-adb tool (off by default — it runs arbitrary adb
 *  commands on this machine). The env var matters as much as the flag: the
 *  stdio shim auto-spawns the daemon with a hardcoded argv, so a CLI-only
 *  switch could never reach a daemon the client started for you. */
function resolveEnableAdb(args) {
  const raw = args["enable-adb"] ?? process.env.AURA_MCP_ENABLE_ADB;
  if (raw === undefined) return false;
  return raw !== "0" && raw.toLowerCase() !== "false";
}

const HELP = `aura-mcp-connect ${VERSION}

Usage:
  aura-mcp                     Run as MCP stdio server (auto-starts the shared daemon)
  aura-mcp daemon              Run the shared daemon in the foreground
  aura-mcp pair <6-digit-pin>  Pair this computer with your phone, then exit
  aura-mcp --pin=<pin>         Pair, then run as MCP stdio server

The phone and this computer must share a network: the same Wi-Fi, the
phone's hotspot, or a private VPN such as Tailscale. Nothing is reachable
from the internet.

Options:
  --host=<addr>  Pair with the phone at this address (shown in AURA →
                 MCP Center) instead of searching. Needed over Tailscale.
  --port=<n>     Daemon port (default 4816; env AURA_MCP_PORT)
  --enable-adb   Arm the host-side 'aura-adb' tool, letting connected MCP
                 clients run adb commands on THIS machine (env
                 AURA_MCP_ENABLE_ADB=1). Off by default. Requires the phone
                 to already be adb-paired here — see the README section
                 "Pairing your phone to this machine". Set AURA_ADB_SERIAL
                 to pick a device when several are attached.
  --version      Print version
  --help         This text

Because the stdio shim auto-starts the daemon for you, prefer the env var:
setting AURA_MCP_ENABLE_ADB=1 in your MCP client's environment arms the
daemon it spawns; --enable-adb only arms a daemon you start by hand.

Prefer connecting your MCP client by URL — every client then shares one
phone connection:

  http://127.0.0.1:4816/mcp        (transport: streamable HTTP)

See https://github.com/Dinesh210805/aura-releases
`;

/** Logger for daemon mode: stderr + ~/.aura/daemon.log (the daemon is often
 *  spawned detached with stdio ignored, so file logging is the only trace). */
async function makeDaemonLogger() {
  const { default: fs } = await import("fs");
  const { default: path } = await import("path");
  const { default: os } = await import("os");
  const logFile = path.join(os.homedir(), ".aura", "daemon.log");
  try {
    fs.mkdirSync(path.dirname(logFile), { recursive: true });
  } catch (e) {
    /* home dir edge case — stderr still works */
  }
  return (...parts) => {
    const line = `${new Date().toISOString()} ${parts.join(" ")}`;
    process.stderr.write(`[aura-mcp-connect] ${line}\n`);
    try {
      fs.appendFileSync(logFile, line + "\n");
    } catch (e) {
      /* best-effort */
    }
  };
}

// ─── Entry ────────────────────────────────────────────────────────────

async function main() {
  const args = parseArgs(process.argv.slice(2));

  if (args.version) {
    process.stdout.write(`aura-mcp-connect ${VERSION}\n`);
    return;
  }
  if (args.help) {
    process.stdout.write(HELP);
    return;
  }

  const port = resolvePort(args);

  // ── Pairing-only mode: `aura-mcp pair 123456` ──────────────────────
  if (args._[0] === "pair") {
    const pin = args._[1] || args.pin;
    if (!pin) fatal("Please provide a PIN: aura-mcp pair 123456");
    const { pairPhone } = await import("./pair.js");
    log(`v${VERSION} · pairing`);
    // The first handshake runs here, in the terminal the user is watching:
    // the phone asks for approval and both sides must show the same code.
    const phone = await pairPhone({
      pin,
      host: args.host,
      log,
      onVerificationCode: (code) =>
        process.stdout.write(
          `\n🔐 Verification code: ${code}\n` +
            `Your phone is asking to approve this computer. Approve ONLY if it shows the same code.\n\n`,
        ),
    });
    process.stdout.write(
      `✅ Paired with the phone at ${phone.host}.\n` +
        `Point your MCP client at http://127.0.0.1:${port ?? 4816}/mcp ` +
        `(or use the 'aura-mcp' stdio command).\n\n`,
    );
    process.exit(0);
  }

  // ── Daemon mode: `aura-mcp daemon` ─────────────────────────────────
  if (args._[0] === "daemon") {
    const { startDaemon, DEFAULT_PORT } = await import("./daemon.js");
    // The daemon is usually spawned detached with stdio ignored, so mirror
    // logs to ~/.aura/daemon.log — otherwise failures are invisible.
    const daemonLog = await makeDaemonLogger();
    daemonLog(`v${VERSION} · daemon mode`);
    const enableAdbTool = resolveEnableAdb(args);
    let daemon;
    try {
      daemon = await startDaemon({ port, log: daemonLog, enableAdbTool });
    } catch (e) {
      if (e.code === "EADDRINUSE") {
        const effectivePort = port ?? DEFAULT_PORT;
        // Is the port holder one of ours?
        const healthy = await fetch(`http://127.0.0.1:${effectivePort}/health`)
          .then((r) => (r.ok ? r.json() : null))
          .catch(() => null);
        if (healthy?.service === "aura-mcp-daemon") {
          // "Already running" is normally a no-op, but silently exiting 0 after
          // an explicit --enable-adb would leave the user with no adb tool and
          // no error to explain why. Say what actually happened.
          if (enableAdbTool && !healthy.adbToolEnabled) {
            fatal(
              `A daemon is already running on port ${effectivePort} with aura-adb DISABLED, ` +
                `so --enable-adb had no effect. Stop it and start it again with the flag ` +
                `(or AURA_MCP_ENABLE_ADB=1) to arm the adb tool.`,
            );
          }
          daemonLog(
            `An AURA daemon is already running on port ${effectivePort} — nothing to do.`,
          );
          process.exit(0);
        }
        fatal(
          `Port ${effectivePort} is in use by something that isn't the AURA daemon. ` +
            `Pick another port: aura-mcp daemon --port=<n> (clients must use the same port).`,
        );
      }
      throw e;
    }
    const shutdown = async () => {
      log("Shutting down daemon...");
      await daemon.close();
      process.exit(0);
    };
    process.on("SIGINT", shutdown);
    process.on("SIGTERM", shutdown);
    return; // Keeps running until signalled.
  }

  // ── Default: stdio shim → shared daemon ────────────────────────────
  const pin = args.pin || (args._[0]?.match(/^\d{6}$/) ? args._[0] : null);
  if (pin) {
    const { pairPhone } = await import("./pair.js");
    log(`v${VERSION} · pairing before starting`);
    try {
      await pairPhone({ pin, host: args.host, log });
    } catch (e) {
      const { loadPairing } = await import("./config.js");
      if (!loadPairing()) throw e;
      log(`PIN failed (${e.message}) — using previously saved pairing.`);
    }
  }

  const { runStdioShim } = await import("./stdio-shim.js");
  log(`v${VERSION} · stdio shim → shared daemon`);
  await runStdioShim({ port, log, enableAdbTool: resolveEnableAdb(args) });
}

// Expected failures (not found, wrong PIN, denied…) carry a message written
// for the user; a stack trace would only bury it. AURA_DEBUG=1 shows it.
main().catch((err) =>
  fatal(process.env.AURA_DEBUG ? err?.stack || String(err) : err?.message || String(err)),
);
