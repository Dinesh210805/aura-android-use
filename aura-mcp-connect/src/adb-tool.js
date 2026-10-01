/**
 * aura-adb — a single generic MCP tool that runs an ADB command against
 * whatever phone is paired to THIS workstation (USB, or same-network
 * Wireless Debugging — see the README's "Pairing your phone to this machine").
 *
 * Deliberately local-only: this never touches the WebRTC/phone connection.
 * It shells out to the machine's own `adb` binary, exactly like a developer
 * typing `adb install app.apk` themselves — so it only works for the local-
 * dev case (phone reachable from this machine directly), not the remote
 * case the WebRTC transport exists for.
 *
 * Off unless armed: the daemon only registers this when started with
 * --enable-adb / AURA_MCP_ENABLE_ADB=1 (see daemon.js). Arbitrary adb
 * execution should never be something a client gets merely by connecting.
 */
import { execFile } from "child_process";
import { promisify } from "util";

const execFileAsync = promisify(execFile);

export const MAX_OUTPUT_CHARS = 20_000;
const TIMEOUT_MS = 60_000;

function truncate(text) {
  if (text.length <= MAX_OUTPUT_CHARS) return text;
  const cut = text.length - MAX_OUTPUT_CHARS;
  return `${text.slice(0, MAX_OUTPUT_CHARS)}\n…(truncated, ${cut} more chars)`;
}

/**
 * What the handshake says about aura-adb. Kept to a few lines on purpose.
 *
 * - Why short: Claude Code keeps only ~2 KB of a server's handshake `instructions`, and the
 *   phone's own text already uses most of it. Anything long appended here is cut off — the
 *   safety rule first of all, because it would sit at the end.
 * - Why it exists at all: a tool description is read only once a model is already picking a
 *   tool, so without this line agents never learn there is a second channel to the device.
 * - Why the safety rule is here and not only in the description: `adb shell input tap` reaches
 *   the same pixels without passing through the on-device MCP server, so no device-side check
 *   fires. An agent that learns about adb must learn the rule in the same breath.
 * - Change together: `test/daemon.test.mjs` pins the combined handshake length.
 */
const INSTRUCTIONS =
  "You also have `aura-adb`: raw ADB from this computer to the same phone. It BYPASSES every " +
  "on-device safety check — never use it to reach anything the device refused with " +
  "`policy_blocked`, or to type a credential; tell the user instead. Prefer the device tools; " +
  "use adb when they cannot see or reach something, or to diagnose. Read its description first.";

/**
 * The tool's full guidance. Agents load a tool's description right before calling it, so the
 * detail lives here rather than in the size-capped handshake (see [INSTRUCTIONS]).
 */
const DESCRIPTION = `
Run an ADB command against the phone paired to this workstation. Pass exactly what you'd type after \`adb\` — e.g. \`install app-debug.apk\`, \`logcat -d -t 200\`, \`shell pm list packages -3\`. Runs THIS machine's adb; the phone must already be paired (USB debugging authorized, or \`adb pair\`/\`adb connect\` done). Set AURA_ADB_SERIAL to pick one device when several are attached.

SAFETY: this does NOT go through the phone's MCP server, so it skips every on-device check. Anything refused with \`policy_blocked\` (banking, payments, authenticator/password apps, card numbers, CVVs, PINs, passwords) stays refused. Never use adb to reach it or to type a credential. Confirm destructive commands with the user first (\`pm clear\`, \`uninstall\`, \`rm\`, settings writes).

WHEN: prefer the device tools for ordinary interaction (policy-checked and verified). Switch to adb when they fail — a target with no UI-tree node, a Flutter/React-Native/Unity/WebView surface, a stuck screen — instead of retrying the same gesture.

USEFUL:
- Focused activity: \`shell dumpsys window | grep mCurrentFocus\`. Raw hierarchy: \`shell uiautomator dump\` then \`shell cat /sdcard/window_dump.xml\`. Screen size: \`shell wm size\`.
- Diagnose a failure: \`logcat -d -t 300\`, errors only \`logcat -d *:E\`, permissions \`shell dumpsys package <pkg>\`.
- Reset fast: \`shell am force-stop <pkg>\`, \`shell am start -n <pkg>/<activity>\`, \`shell input keyevent HOME\`.
- Dev loops: \`install -r\`, \`uninstall\`, \`pull\`/\`push\`, \`shell screencap -p /sdcard/s.png\`, \`shell settings get/put\`, \`shell svc wifi disable\`.

RULES:
- Do NOT quote. Arguments split on whitespace and quotes stay literal: write \`shell dumpsys window | grep x\`, not \`shell "dumpsys window | grep x"\`.
- Nothing runs in a shell on this computer, but everything after \`shell\` runs in the PHONE's shell, which does chain and pipe: \`shell rm x; reboot\` does both.
- Output is cut at 20,000 chars keeping the start — filter on the device with grep/head.
- "no devices/emulators found" means the phone is not adb-paired to this computer; tell the user, you cannot fix it.
`.trim();

/**
 * Build the tool.
 *
 * The `run`/`env` seams exist so tests can drive the handler without a real
 * adb on PATH: `promisify(execFile)` is captured at module load, so an ESM
 * test can never monkeypatch it after the fact. Mirrors the injection style
 * of `PhoneManager({ transportFactory })` elsewhere in this package.
 *
 * @param {{
 *   run?: (file: string, args: string[], opts: object) => Promise<{stdout: string, stderr: string}>,
 *   env?: Record<string, string | undefined>,
 * }} [deps]
 */
export function createAdbTool({ run = execFileAsync, env = process.env } = {}) {
  return {
    /** Merged into the handshake instructions by buildProxyServer. */
    instructions: INSTRUCTIONS,
    definition: {
      name: "aura-adb",
      description: DESCRIPTION,
      inputSchema: {
        type: "object",
        properties: {
          command: {
            type: "string",
            description: "Arguments to pass to adb, e.g. 'shell pm list packages -3'",
          },
        },
        required: ["command"],
      },
    },

    /** @param {{command?: string}} args */
    async handler(args) {
      const command = args?.command;
      if (typeof command !== "string" || !command.trim()) {
        return {
          content: [{ type: "text", text: "aura-adb: 'command' must be a non-empty string" }],
          isError: true,
        };
      }

      // execFile — NOT exec()/shell:true — so metacharacters in `command`
      // (`;`, `&&`, backticks, `|`) reach adb as literal argv entries instead of
      // being interpreted by a shell ON THIS MACHINE. This is a generic *adb*
      // passthrough, deliberately not a generic host-shell passthrough.
      //
      // It does NOT follow that the phone sees them literally: `adb shell X; Y`
      // hands "X; Y" to the *device's* shell, which does chain (verified on
      // device 2026-08-06 — `shell echo hi; touch /sdcard/PWNED` created the
      // file). That is inherent to `adb shell` and expected for a dev tool; the
      // boundary this protects is the host, not the phone.
      const argv = command.trim().split(/\s+/);
      const serial = env.AURA_ADB_SERIAL;
      const fullArgv = serial ? ["-s", serial, ...argv] : argv;

      try {
        const { stdout, stderr } = await run("adb", fullArgv, {
          timeout: TIMEOUT_MS,
          maxBuffer: 10 * 1024 * 1024,
        });
        const text = [stdout, stderr].filter(Boolean).join("\n").trim() || "(no output)";
        return { content: [{ type: "text", text: truncate(text) }] };
      } catch (e) {
        // Never let a failed adb invocation escape past the MCP boundary — the
        // model can act on "device unauthorized" text, but not on a dead session.
        const text = [e.stdout, e.stderr, e.message].filter(Boolean).join("\n").trim();
        return {
          content: [{ type: "text", text: truncate(text || String(e)) }],
          isError: true,
        };
      }
    },
  };
}

export const auraAdbTool = createAdbTool();
