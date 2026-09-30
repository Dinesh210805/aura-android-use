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

const DESCRIPTION =
  "Run an ADB command against the phone paired to this workstation. Pass " +
  "exactly what you'd type after `adb` — e.g. `install app-debug.apk`, " +
  "`logcat -d -t 200`, `shell pm list packages -3`, `shell am force-stop " +
  "com.example`. Runs on THIS machine's local adb binary; the phone must " +
  "already be paired (USB debugging authorized once, or `adb pair`/" +
  "`adb connect` already completed) — this tool does not perform pairing " +
  "itself. Arguments are split on whitespace and quotes are NOT stripped, so " +
  "write `shell dumpsys window | grep mCurrentFocus` unquoted — the device's " +
  "shell handles the pipe. Output is truncated at 20k chars, so filter with " +
  "grep/head rather than dumping. Set the AURA_ADB_SERIAL env var to target " +
  "one device when more than one is attached.";

/**
 * Appended to the phone's own handshake instructions when this tool is armed.
 *
 * A tool description is read once a model is already choosing between tools;
 * handshake instructions shape strategy before the first call — which is the
 * difference between an agent that reaches for adb only when the user says
 * "adb" and one that knows it has a second, independent channel to the device.
 *
 * The policy paragraph is not boilerplate: `adb shell input tap` reaches the
 * same pixels without passing through the on-device MCP server, so none of the
 * device-side safety checks fire. An agent told "use adb when tap fails" and
 * nothing else would have a documented bypass for them.
 */
const INSTRUCTIONS = `
## aura-adb — a second, independent channel to this device

You also have \`aura-adb\`, which runs any ADB command on the computer hosting
this MCP connection, against the same phone. Pass exactly what you'd type after
\`adb\` (e.g. \`shell dumpsys window\`). It does NOT go through the phone's MCP
server — it is a separate path to the same device.

Two things that follow from that, and they pull in opposite directions:

**1. It is a real fallback.** When the accessibility path fails — \`tap\`/\`swipe\`
mis-target, an element has no node in the UI tree, a Flutter/React-Native/Unity
surface exposes nothing useful, a WebView is opaque, the screen is stuck — do
not keep retrying the same gesture. Switch channels.

**2. It skips every on-device safety check.** Anything the device refused with
\`policy_blocked\` (banking, payments, authenticator/password apps, card numbers,
CVVs, PINs, passwords) stays refused. NEVER use aura-adb to reach a target the
device declined, or to type a credential. A refusal is a decision, not an
obstacle to route around. Tell the user instead.

Use it for far more than installs and logcat:

- **See what the UI tools can't** — \`shell dumpsys window | grep
  mCurrentFocus\` names the focused activity in one line, \`shell dumpsys
  activity top\`, \`shell uiautomator dump\` then \`shell cat
  /sdcard/window_dump.xml\` for a raw hierarchy independent of the accessibility
  tree, \`shell wm size\` / \`wm density\` when coordinates look wrong.
- **Diagnose instead of guess** — \`logcat -d -t 300\` right after a failure
  (crash, ANR, silent no-op) usually says why. \`logcat -d *:E\` for errors only.
  \`shell dumpsys package <pkg>\` for permissions actually granted.
- **Get to a known state fast** — \`shell am force-stop <pkg>\`,
  \`shell pm clear <pkg>\` (destructive: ask first), \`shell am start -n
  <pkg>/<activity>\`, \`shell input keyevent HOME\`. Cheaper and more reliable
  than navigating back through many screens.
- **Development and test loops** — \`install -r app.apk\`, \`uninstall\`,
  \`shell pm list packages -3\`, \`shell am instrument\`, \`pull\`/\`push\`,
  \`shell screencap -p /sdcard/s.png\`, \`shell settings get/put\`,
  \`shell content query\`, \`shell svc wifi disable\` to test offline paths.
- **Reproduce conditions** — rotate (\`shell settings put system
  user_rotation\`), simulate low battery, toggle radios, change density, then
  verify with the normal perception tools.

Working rules:

- Prefer the device's own tools for ordinary interaction. They are
  policy-checked, coordinate-aware, and verified. Reach for adb when they
  cannot see or cannot reach something, or when you need evidence.
- **Read before you write.** \`dumpsys\`/\`logcat\` cost nothing and are always
  safe. \`input tap\`, \`pm clear\`, \`rm\`, \`svc\` change real state.
- Confirm destructive commands with the user first (\`pm clear\`,
  \`uninstall\`, \`rm\`, factory-reset-adjacent settings writes).
- **No shell runs on the host computer**, so \`;\`, \`&&\` and \`|\` never chain
  commands *there*. But everything after \`adb shell\` is handed to the DEVICE's
  shell, which DOES chain and pipe. So \`shell rm x; reboot\` really does both —
  treat any \`adb shell\` line as a shell command on the phone.
- **Do NOT quote.** Arguments are split on whitespace and quotes survive as
  literal characters, so \`shell "dumpsys window | grep x"\` fails with
  "inaccessible or not found". Write it unquoted: \`shell dumpsys window | grep
  mCurrentFocus\` works and is the single most useful thing here.
- **Filter on the device, don't drown.** Output is truncated at 20,000
  characters keeping the START, and \`dumpsys window\` or a \`uiautomator\` dump
  of a busy screen blows past that — leaving you the top of the hierarchy, which
  is rarely where your target is. Pipe through \`grep\`/\`head\` instead:
  \`shell dumpsys window | grep mCurrentFocus\` returns one line naming the
  focused activity. Use \`logcat -d -t 200\` rather than unbounded \`logcat\`.
- If it returns \`no devices/emulators found\`, the phone is not adb-paired to
  that computer. Say so — you cannot fix it from here, the user must pair it.
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
