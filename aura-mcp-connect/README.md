# aura-mcp-connect

Connect any MCP client to AURA's on-device MCP server over your **local
network**: no cloud relay, no IP addresses to type, no firewall rules, no
certificates.

The bridge is built around a **shared local daemon**: one process
on your computer owns the WebRTC link to the phone and serves MCP over
**Streamable HTTP** at `http://127.0.0.1:4816/mcp`. Every MCP client — Claude
Code, Claude Desktop, VS Code, Cursor, Cline, Windsurf, Zed, multiple windows
of each — connects to that URL (or through the `aura-mcp` stdio command, which
auto-starts the daemon) and **shares** the single phone connection.

```
Claude Code ──┐
Cursor ───────┼── http://127.0.0.1:4816/mcp ──► daemon ── WebRTC/DTLS ──► phone
VS Code ──────┘         (Streamable HTTP)      (one per PC)   (same network)
```

**The phone and this computer must share a network**: the same Wi-Fi, the
phone's hotspot, or a private VPN such as Tailscale (see
[From another network](#from-another-network-tailscale)). The phone is never
reachable from the internet.

## Upgrading from 0.8 or older

0.9 connects over your local network only. Older bridges found the phone
through a cloud relay that no longer exists, so the two don't mix:

- **Update both sides together:** AURA **1.0.534 or newer** on the phone, and
  `npm install -g aura-mcp-connect@latest` here. An older app can't talk to
  this bridge, and an older bridge can't talk to the new app.
- If the bridge then says it's not paired, or that the phone doesn't know this
  computer any more, **pair again** (see Setup below).

## Installation

Needs **Node.js 18 or newer** ([nodejs.org](https://nodejs.org)).

```bash
npm install -g aura-mcp-connect@latest
```

(~50 MB native WebRTC binary downloads during install — doing it up front
keeps you inside your MCP client's startup timeout later.)

## Setup

1. **Phone:** join the same Wi-Fi as this computer, open AURA → **MCP** tab,
   tap **Start server**, and note the 6-digit PIN. It works once and changes
   after each pairing.
2. **Computer (once):**

   ```bash
   aura-mcp pair 123456
   ```

   `pair` finds the phone on the network, connects once and prints a
   **verification code**. Your phone shows
   an approval dialog with a code too: approve **only if the two codes
   match**. After that, reconnects are silent and PIN-free until you revoke
   this computer in AURA → Trusted Devices. The pairing is saved to
   `~/.aura/webrtc.json`; keep that file private, since it identifies this
   computer to your phone.

   If `pair` can't find the phone (some routers hide devices from each
   other), name the address the **MCP** tab shows:
   `aura-mcp pair 123456 --host=192.168.1.23`.

   **Windows:** if Windows asks whether to allow Node.js on the network,
   allow it on **Private** networks.

3. **Clients — pick either:**

   **By URL** (preferred): run `aura-mcp daemon` once, then:

   ```bash
   claude mcp add --transport http aura http://127.0.0.1:4816/mcp
   ```

   ```json
   { "servers": { "aura": { "type": "http", "url": "http://127.0.0.1:4816/mcp" } } }
   ```

   **By command** (stdio clients — auto-starts the daemon):

   ```json
   { "mcpServers": { "aura": { "command": "aura-mcp", "args": [] } } }
   ```

   > **Windows:** some clients can't launch npm `.cmd` shims directly. If the
   > server never starts, use
   > `{ "command": "cmd", "args": ["/c", "aura-mcp"] }` — or connect by URL.

## From another network (Tailscale)

The link only works between devices that can reach each other directly. To
use AURA while away from the phone's Wi-Fi, put both devices on a private
VPN. [Tailscale](https://tailscale.com) is free for personal use:

1. Install Tailscale on the phone and on this computer and sign in to the
   same account on both.
2. In AURA → **MCP** tab, the phone lists its Tailscale address (it starts
   with `100.`).
3. Pair once with that address:

   ```bash
   aura-mcp pair 123456 --host=100.101.102.103
   ```

The address is saved, so later reconnects find the phone over Tailscale by
themselves. Other private VPNs work the same way.

**At home with Tailscale running:** if another of your Tailscale devices
shares your home network as a subnet route, and this computer accepts routes,
traffic to the phone's `192.168.x.x` address goes into Tailscale instead of
over Wi-Fi. If that device is offline, the phone can't be reached at all, even
with `--host`. Run `tailscale set --accept-routes=false` on this computer, or
stop advertising that route.

## CLI

| Command | Description |
|---|---|
| `aura-mcp` | Stdio MCP server that proxies to the shared daemon (auto-starts it). |
| `aura-mcp daemon` | Run the shared daemon in the foreground. `GET /health` reports status. |
| `aura-mcp pair <pin>` | Find the phone on the network and pair with it, then exit. |
| `--host=<address>` | With `pair`: use this phone address instead of searching (needed over Tailscale). |
| `aura-mcp --pin=123456` | Pair, then run the stdio shim. |
| `--port=<n>` / `AURA_MCP_PORT` | Daemon port (default 4816). |
| `--enable-adb` / `AURA_MCP_ENABLE_ADB=1` | Arm the host-side `aura-adb` tool (off by default). See below. |
| `AURA_ADB_SERIAL=<serial>` | Which device `aura-adb` targets when several are attached. |
| `AURA_DEBUG=1` | Print full stack traces instead of short error messages. |

## Pairing your phone to this machine (adb)

This is a **prerequisite** for the `aura-adb` tool described in the next
section, and you only do it once. `aura-adb` shells out to this machine's
own `adb` binary — it can't pair for you, and it will fail with
`error: no devices/emulators found` until one of the two paths below has
already succeeded.

> This pairing is between **this machine's adb key and the phone**. It is
> completely independent of AURA's own pairing (the 6-digit PIN flow
> in the app) and does not require the AURA app to be running, or even
> installed.

### Path 1 — USB (simplest, works on any Android version)

1. On the phone: **Settings → Developer options → USB debugging** → turn on.
   *(If Developer options isn't visible: **Settings → About phone** → tap
   **Build number** 7 times.)*
2. Plug the phone into this machine with a USB cable.
3. The phone shows **"Allow USB debugging?"** with this computer's RSA
   fingerprint — tap **Allow**. Tick **"Always allow from this computer"**
   so this stays a one-time step.
4. Verify:
   ```bash
   adb devices
   ```
   The phone must be listed as `device`. If it says `unauthorized`, the
   dialog in step 3 wasn't accepted — unplug, replug, and look at the phone.

### Path 2 — Wireless Debugging (Android 11+, no cable)

1. On the phone: **Settings → Developer options → Wireless debugging** →
   turn on.
2. Tap **"Pair device with pairing code"**. The phone shows a **6-digit
   code** and an **IP:PORT**.
3. On this machine, using the IP:PORT from the *pairing dialog*:
   ```bash
   adb pair 192.168.1.42:37129
   ```
   Enter the 6-digit code when prompted.
4. Go back to the **Wireless debugging** screen and note the IP:PORT shown
   at the *top level* — this is a **different port** from the pairing one:
   ```bash
   adb connect 192.168.1.42:41235
   ```
   *(Both ports are assigned randomly by Android — yours will differ, and the
   pairing port is never the connect port.)*
5. Verify with `adb devices` — the phone should be listed.

### Troubleshooting

- **Multiple devices attached** — `adb` refuses to guess. Run `adb devices`
  to see each serial, then set `AURA_ADB_SERIAL` to the one you want; every
  `aura-adb` call is then prefixed with `-s <serial>`.
- **Wireless pairing doesn't survive a reboot** — Android resets the
  connection on restart. Afterwards, redo **step 4** only (`adb connect`).
  If the IP or port changed, redo from **step 3** (`adb pair`).

## The `aura-adb` tool

When armed, the daemon exposes one extra MCP tool, `aura-adb`, to every
connected client. It takes a single `command` string and runs it as
`adb <command>` on the machine running the daemon:

```
aura-adb(command: "install app-debug.apk")
aura-adb(command: "logcat -d -t 200")
aura-adb(command: "shell pm list packages -3")
```

It is **off by default** and must be armed explicitly:

```bash
aura-mcp daemon --enable-adb          # works in any shell
```

To arm the daemon your editor auto-starts, set the env var. **`VAR=1 cmd` is
bash-only** — it fails if pasted into PowerShell or cmd:

```bash
AURA_MCP_ENABLE_ADB=1 aura-mcp        # bash / zsh / Git Bash
```
```powershell
$env:AURA_MCP_ENABLE_ADB=1; aura-mcp  # PowerShell
```
```
set AURA_MCP_ENABLE_ADB=1 && aura-mcp   :: cmd.exe
```

Best of all, put it in the MCP client's own config so it survives restarts —
this is the recommended setup, because the stdio shim spawns the daemon with
a fixed argv and `--enable-adb` can only ever arm a daemon you launched
yourself:

```json
"aura": { "command": "aura-mcp", "args": [], "env": { "AURA_MCP_ENABLE_ADB": "1" } }
```

Two things that bite:

- **Upgrade first.** `npm publish` does not update your own machine — run
  `npm install -g aura-mcp-connect@latest` and check `aura-mcp --version`
  reports 0.10.0 or newer. Older bridges can't pair a new computer with the
  current app (0.9.x can still reconnect one that is already paired).
- **A running daemon cannot be re-armed** — its tool set is fixed at startup.
  Stop the old one first (it may be an older version still holding port 4816).

When disabled the tool isn't registered at all — it does not appear in
`tools/list`, so a client cannot call it. Check which state a running daemon
is in:

```bash
curl http://127.0.0.1:4816/health     # → {"ok":true, …, "adbToolEnabled":false}
```

### What connecting agents are told

When armed, the daemon appends a guidance section to the handshake
`instructions` every MCP client receives (the phone's own instructions stay
first and unedited — it owns the safety doctrine). That section tells agents to
treat adb as a **second, independent channel to the device**, not just an
install/logcat utility: fall back to it when accessibility gestures mis-target
or a Flutter/RN/WebView surface exposes no usable tree, use `dumpsys`/`logcat`
to diagnose a failure instead of retrying blindly, and use `am force-stop` /
`am start` to reach a known state cheaply.

It also states one hard rule. `adb shell input tap` reaches the same pixels
**without passing through the on-device MCP server**, so none of the device-side
safety checks fire. Anything the phone refused with `policy_blocked` (banking,
payments, authenticator apps, card numbers, PINs, passwords) must stay refused —
agents are told never to route around a refusal with adb, and to ask before
destructive commands (`pm clear`, `uninstall`, `rm`).

Notes and limits:

- **Host-side only.** This never touches the phone link, the on-device MCP
  server, or WebRTC. It is exactly equivalent to you typing `adb …` in a
  terminal on this machine — which is why the phone cannot know it happened,
  and why the arming decision lives here rather than in the app.
- **No host shell.** The command runs via `execFile`, so `;`, `&&`, `|` and
  backticks are never interpreted on *this computer* — an MCP client cannot
  turn `aura-adb` into arbitrary local command execution.
- **The device's shell is a different story.** Everything after `adb shell` is
  handed to a shell *on the phone*, which does chain and pipe. `shell rm x;
  reboot` really does both. Treat any `adb shell` line as a shell command on
  the device.
- **Arguments are split on whitespace and quotes are not stripped.** Write
  pipes unquoted — `shell dumpsys window | grep mCurrentFocus` works (and
  returns one line instead of a truncated 20k dump), while
  `shell "dumpsys window | grep x"` fails with *inaccessible or not found*.
  Arguments that genuinely need embedded spaces are not supported.
- Output combines stdout and stderr, truncated at 20,000 characters; the
  call times out after 60s. A failing `adb` returns an MCP error result with
  adb's own text rather than throwing.

Why the switch lives here and not on the phone: the phone never sees these
commands (they go from this computer's adb straight to the device), so only
this computer can decide whether to allow them.

## Architecture & security model

1. **Local network only.** The phone listens on port 47821 (up to 47825 if
   taken) and answers only peers with private addresses (`10/8`, `172.16/12`,
   `192.168/16`, `169.254/16`, and `100.64/10` for Tailscale) that did not
   arrive over mobile data. There is no STUN or TURN server, and both sides
   drop every non-LAN ICE candidate, so the WebRTC link cannot cross the
   internet. There is no cloud relay at all.
2. **Finding the phone.** The address saved at pairing, then an mDNS lookup
   of `_aura-mcp._tcp`, then a scan of this computer's local /24 subnets.
   Each hit is confirmed with `GET /aura/info` and its device id.
3. **Signaling is not trusted.** One HTTP request carries this computer's
   offer and the phone's answer. Someone on the same Wi-Fi could read or
   change it, and still couldn't get in, because of steps 4 and 5.
4. **First pairing: PIN, then matching codes.** A new computer needs the
   PIN from the phone's screen before the phone even shows an approval
   dialog. The PIN works once; five wrong guesses change it and lock
   pairing for a minute (doubling each time). Then both screens show a
   6-digit code derived from the two sides' DTLS fingerprints. An
   interceptor changes the fingerprints, so the codes wouldn't match. The
   phone trusts this computer only after the user approves matching codes.
5. **Reconnects: proof, not a password.** This computer keeps a random token
   in `~/.aura/webrtc.json` and never sends it again. It answers the phone's
   random challenge with an HMAC over the token, the challenge and both
   fingerprints. Protocol details: `src/pairing-crypto.js`.
6. **End-to-end encryption.** MCP JSON-RPC flows directly between computer
   and phone over WebRTC's mandatory DTLS.
7. **Browsers can't talk to the phone.** The phone refuses any request with
   an `Origin` header and any non-JSON POST, so a web page open on a
   computer in the same network can't reach it.
8. **Local-only HTTP on this computer.** The daemon binds `127.0.0.1` and
   rejects non-loopback `Host`/`Origin` headers (DNS-rebinding protection).
   Tool calls from all sessions are serialized — there is one physical
   screen.

## Failure modes

| What happens | What you see |
|---|---|
| Phone not found (different network, AURA closed, router hides devices) | `Couldn't find your phone on this network` plus a checklist. Same Wi-Fi, or the phone's hotspot, or `--host=<address>` |
| Phone not found, and this computer can't even ping it or the router | Tailscale on this computer is routing your home subnet to another device. See "At home with Tailscale running" above |
| Phone on mobile data only | `The phone has no local-network address` — connect it to Wi-Fi or turn on its hotspot |
| Wrong PIN | `Wrong PIN` — check MCP Center; the PIN changes after each pairing |
| Too many wrong PINs | `Too many wrong PINs. Wait N s` — then use the new PIN on the phone |
| Not paired yet | `Not paired with a phone. Run aura-mcp pair <PIN> first` |
| Phone forgot this computer (revoked, app data cleared) | `This phone doesn't know this computer any more. Pair again` |
| Approval not granted in time | `timed out during device approval` — keep the phone in hand on first connect |
| User taps Deny | `Connection denied by the phone: Denied on the phone` |
| Bridge older than the phone expects | `Couldn't find your phone` (0.8 and older look for it through a cloud relay that is gone) → `npm i -g aura-mcp-connect@latest` |
| Port already in use | Daemon exits with a clear message; use `--port=<n>` |
| `--enable-adb` but a daemon is already up | Daemon exits telling you to restart the existing one with the flag — an already-running daemon can't be re-armed |
| `aura-adb` missing from `tools/list` | The daemon wasn't armed — check `GET /health` for `adbToolEnabled` |
| `aura-adb` → `no devices/emulators found` | The phone isn't adb-paired to this machine — see "Pairing your phone to this machine" |
| Second computer connects | Newest computer wins the phone link; the first reconnects on its next request |

All log output goes to **stderr** — stdout is reserved for MCP JSON-RPC
frames (stdio mode).

## Development

```bash
npm test   # syntax checks, daemon & shim tests, LAN + pairing end-to-end (no phone needed)
```

`test/lan-e2e.test.mjs` pairs and reconnects over real WebRTC against a fake
phone. It needs a private IPv4 address on this machine and skips itself
without one.

## License

MIT. See `LICENSE`.
