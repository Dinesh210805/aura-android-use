# Changelog

What changed in each release of the AURA Android app. The newest is at the top.
Download any version from [GitHub Releases](https://github.com/Dinesh210805/aura-android-use/releases);
each release page also lists the APK's SHA-256.

The desktop bridge, `aura-mcp-connect`, has its own versions on
[npm](https://www.npmjs.com/package/aura-mcp-connect).

## Unreleased

Work in progress on `main`. See the [roadmap](README.md#roadmap).

## 1.0.712 (2026-11-25)

- **A new AURA agent, with chat as Home.** It streams replies, reads images and documents,
  searches the web, and hands phone tasks to the overlay, where you can watch and pause them.
- **One-tap updates.** Tap Update and AURA downloads the new version, checks it is really AURA,
  and opens Android's install screen.
- **Memory and skills that learn.** AURA remembers facts you allow, shows where each came from,
  and suggests skills from tasks it has done. Edit or pin them in Settings → Memory.
- **Connected apps.** Sign in to Gmail, Calendar, Drive and more through Composio, and switch each
  app on or off.
- **Clearer safety.** Before anything leaves the phone, a card shows the exact text and the
  recipient. Allow it once or for the rest of the chat.
- **New "Hey AURA" wake word**, trained on more accents, and it triggers only on real speech.

On 1.0.592 or older, Update opens the download page one last time.

## 1.0.592 (2026-11-07)

- **Fixed:** agent runs work properly again. `run_steps` (several steps in one call) confused the
  agent, so it is switched off for AURA's agent and for MCP clients.
- On-device tasks now end with the same report card as remote tasks.
- The Logs screen shows the full record of a run, and exports include it. Exports can contain
  screen content, so check one before you share it.
- English is the default language. Add yours in Settings → Memory.

## 1.0.582 (2026-11-05)

- **See and stop AI apps driving your phone.** MCP clients get the same status strip and
  Pause/Stop buttons as AURA's own agent.
- A report card when a remote task ends: outcome, time, steps and the client's summary.
- On-device neural voices and on-device speech-to-text.
- Captions follow what AURA is saying.
- A pointer shows every tap and swipe.
- New MCP tools to set a toggle or field directly, use the clipboard and draw strokes. Taps on
  covered items are caught before they land.
- MCP clients can't read one-time codes, passwords or card numbers you copied.

## 1.0.573 (2026-10-13)

- **Breaking for the bridge:** pairing uses protocol 3. Update with
  `npm install -g aura-mcp-connect@latest` (0.11.0) and pair again. Bridges older than 0.10.0
  can't connect.
- Choose which tool groups each trusted computer can use.
- 56 look-alike MCP tools merged into 42, and each observation says what to do next.
- The safety gate runs inside the phone's tool server, so it applies to every MCP client.
- Safer link, web search, share and upload handling.
- The `aura-phone` skill ships with the bridge.
- Fixes for slow or busy Wi-Fi pairing.

## Before 1.0.573

Versions up to 1.0.534 are in the older
[`aura-releases`](https://github.com/Dinesh210805/aura-releases/releases) repository.
