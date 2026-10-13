# AndroidWorld benchmark

> **Last reviewed:** 2026-10-13.

Scores AURA on Google's [AndroidWorld](https://github.com/google-research/android_world)
benchmark, two ways: AURA's **on-device agent**, and AURA's **MCP server** driven by Claude
Code from the PC.

## How it works

AndroidWorld grades a task by reading **device state** afterwards, through
`task.is_successful(env)`. It checks the row in the app's database, the file on disk, or
the setting that should have flipped. It never looks at how the agent got there.

So neither target is driven step by step like M3A. For each task:

1. AndroidWorld seeds the app data and pins the clock (`task.initialize_task`).
2. The goal goes to the agent: to AURA over the debug broadcast
   `com.aura.aura_ui.AGENT_SPIKE`, or to Claude Code on stdin.
3. The agent works until it finishes, or until it times out and is stopped.
4. AndroidWorld's validator gives the verdict. Nothing here edits or second-guesses it.

## How this differs from a stock AndroidWorld run

Publish these alongside any number. `report.py` prints them on the site.

| Deviation | Why |
|---|---|
| The **information-retrieval** family (25 of 116 tasks) is excluded, so the score is out of 91. | Those tasks are graded on a typed answer (`env.interaction_cache`); AURA's on-device agent has no channel to set it. Their seed data also uses literal 2023 dates that the clock shift cannot move. Run them anyway with `--include_information_retrieval` / `--include_ir`, but they fail for harness reasons. |
| The device clock is shifted from 2023-10-15 to **2026-10-15**. | AURA calls a cloud model from the device. TLS on a phone that thinks it is 2023 rejects today's certificates. Every date in the scored tasks derives from `device_constants.DT`, so each task stays internally consistent. Weekdays change. |
| Agents get a **wall-clock budget** (on-device default 300 s, MCP 600 s) instead of AndroidWorld's step budget of 10 × complexity. | Both agents run their own loop, so AndroidWorld sees a single step. Command-line agents are also handed the stock step budget as `AW_MAX_STEPS`. |
| Seed **30**, one instance per task. | 30 is upstream `run.py`'s default, so task parameters match other published runs. |

## One-time setup

Everything below is git-ignored. Nothing in this folder needs an API key: AURA's provider
key is entered inside the app on the emulator and never leaves it, and Claude Code uses its
own sign-in.

1. **Emulator.** In Android Studio, create an AVD with hardware **Pixel 6**, system image
   **API 33 "Tiramisu", Google APIs, x86_64**, and the name **`AndroidWorldAvd`**. Use the
   Google APIs image, not the Play Store one: AndroidWorld needs `adb root`.
2. **Tools.** Install Python 3.11 and `ffmpeg`. Python 3.12 does not work, because
   AndroidWorld pins matplotlib 3.6.1, which has no 3.12 wheels. `ffmpeg` records the
   page video and joins phone recordings longer than 180 s.
3. **AndroidWorld.** Clone it into the repo root at the pinned commit, then build a venv.
   On Windows, run these commands from Git Bash.
   ```
   git clone https://github.com/google-research/android_world android_world
   git -C android_world checkout 2dfc5ca3dc590efe9c72ce768f2b04182f2f75c7
   python3.11 -m venv androidworld-venv
   androidworld-venv/Scripts/python -m pip install -r android_world/requirements.txt   # bin/ on macOS/Linux
   androidworld-venv/Scripts/python -m pip install -e android_world
   ```
   Do not edit anything in `android_world/` or the venv. The one Windows fix that is
   needed is applied at runtime by `aura_androidworld._fix_apk_install_on_windows`.
4. **Emulator settings.** Keep the AVD on host-GPU rendering (`hw.gpu.enabled=yes`,
   `hw.gpu.mode=host` in its `config.ini`) and audio off. Software rendering burns CPU the
   agent and the recorder need, and a starved run times out and looks like an agent failure.
   Use the Windows High performance power plan, stay plugged in, and run `gradlew --stop`
   first. If host GPU glitches on your driver, use `-gpu angle_indirect`. The suite starts the
   emulator for you, headless:
   `emulator -avd AndroidWorldAvd -no-snapshot -grpc 8554 -no-audio -no-window`. The page shows
   its screen, so a window only costs CPU; and resizing a host-GPU emulator window can leave its
   display blank until it restarts.
5. **Install the benchmark apps.** This runs once per fresh AVD and takes several minutes:
   ```
   androidworld-venv/Scripts/python scripts/bench/aura_androidworld.py --perform_emulator_setup
   ```
6. **Install AURA.** It must be a debug build, because the debug receiver it is driven
   through only exists in debug builds:
   ```
   cd aura-android && ./gradlew :app:installDebug
   ```
   Open AURA on the emulator, finish onboarding and turn on its accessibility service. For
   the on-device target, also pick a provider, model and key in Settings → Agent Brain.
7. **For the MCP target:** Node 18+, `npm install -g aura-mcp-connect@latest`, and the
   `claude` CLI signed in. Pairing is automatic (see below).

**Use an emulator with no personal accounts or data.** Recordings, `live.log` and
`agent.log` contain everything on the screen and everything the agent read. A synced Google
account puts its contacts, mail and photos into every one of them.

Check the code with `external_agent.py --selfcheck`, `referee.py --selfcheck` and
`report.py --demo`. None of them needs the emulator.

## Running: the suite

```
androidworld-venv/Scripts/python scripts/bench/suite.py      # opens http://localhost:8777
```

1. **Pick a target:** *MCP server* (Claude Code drives the phone through AURA's MCP tools) or
   *On-device agent* (AURA's own agent).
2. **Setup runs by itself** (`device.up`) and shows each step: boot the emulator, check AURA,
   start the phone's MCP server, reach it, pair the bench bridge, connect, build AndroidWorld's
   env. A step it cannot fix says what to do, with a Retry button.
3. **Task list:** all 91 scored tasks with their last result, time and cost. Run one, a
   selection, all, the failed ones again, or resume the ones not run yet. More than 3 tasks
   asks first, with a time and cost estimate from past runs. Each finished task has its
   video and log one click away. *Build report* writes the static site.
4. **While running:** the run's task list on the left, the agent's live activity in the
   middle (Claude's tool calls, results and words, or AURA's log), a time bar, and *Stop
   after this task* / *Stop now*. A stopped task is recorded as stopped, never as a FAIL.

The **phone panel** on the right of every screen is the emulator's display, live: the suite
reads it from the emulator's gRPC port (`streamScreenshot`, about 12 frames a second, only
when the screen changes) and serves it as `/screen.mjpg`. It reads the emulator, not the
phone, so it costs the phone nothing. It is view-only.

**Recording** (per run): *This page* (default on Windows with ffmpeg) films the browser
window titled "AURA Bench", so the video is the task list, the agent's activity and the phone
together, and nothing else on your screen. The window is kept on top while a task runs. Keep
the suite in its own browser window (other tabs in front change the title). It encodes with
Intel Quick Sync when the driver allows, else x264. *Phone only* uses the emulator's own
`screenrecord`. *Off* records nothing.

| Target | Results | Agent |
|---|---|---|
| MCP server | `bench-results/claude-code-aura-mcp/<Task>/` | `agents/claude-code-aura-mcp.json` |
| On-device agent | `bench-results/<Task>/` | `AuraAgent` over the debug broadcast |

Each task folder holds `meta.json` (the verdict and what the agent reported), `run.mp4`,
`live.log`, the agent's raw output, and AndroidWorld's own `checkpoint/`.

### How the MCP target reaches the emulator

The bench runs its **own** bridge: `aura-mcp daemon` on port **4817** with its home in
`bench-results/.bridge-home/`, so your everyday bridge on 4816 and its pairing with a real
phone are never touched. The emulator sits behind its own NAT, so `device.py` forwards the
phone's signalling ports (`47821`–`47825`) with `adb forward` and pairs with
`--host=127.0.0.1:<port>`; WebRTC then connects on its own. The first pairing reads the PIN
from AURA's MCP tab and taps Approve only when the code on the phone equals the bridge's.
AURA's MCP server is started with the debug broadcast `--es mode mcp_start`, because its
service is not exported to the shell.

`bench-results/.bridge-home/.aura/webrtc.json` is the bench's pairing token. It never leaves
this machine; delete it to pair again.

### Other entry points

| Entry point | Use it for |
|---|---|
| `external_agent.py --profile NAME [--tasks=A,B]` | The suite's runner without a page: headless batch runs of any profile. |
| `aura_androidworld.py [--tasks=A,B]` | On-device batch runs with AndroidWorld's stock runner and checkpoints in `./runs/`. |
| `referee.py` (port 8778) + Settings → Eval → AndroidWorld on the phone | Starting on-device runs from the phone's cockpit. The phone reaches the PC at `10.0.2.2:8778`. Do not run tasks from it and the suite at the same time. |
| `device.py up [ondevice]` | Just the setup steps, in a terminal. |

## The rules, for every target

AndroidWorld's own runner (`suite_utils.run`) seeds the task, asks the validator and tears
down; `external_agent.run_one` only adds the camera and `meta.json`.

- The agent gets the **goal only**: not the task name, seed or validator. A command-line
  agent runs in an empty temp directory outside the repo.
- The overlay of touch coordinates is hidden at the start of every task
  (`env.hide_automation_ui()`), as stock M3A, T3A and SeeAct do.
- **`done` = exit 0 before the deadline** for command-line agents. A timeout or non-zero exit
  scores 0 even if the validator would pass, as in stock `suite_utils`. `meta.json` keeps the
  raw `validator_success` beside the gated `success`.
- `AW_MAX_STEPS` carries the stock budget `int(10 * complexity)` for CLIs with a turn cap.
  The hard limit is the wall clock: the suite's time limit, else the profile's `timeout_s`.
- An answer written to `AW_ANSWER_FILE`, or Claude's final message, lands in
  `env.interaction_cache`, where stock agents' `answer` action puts it.

### Writing a profile

`agents/<name>.json`: `command` (argv list), `stdin` (`"goal"` sends the goal on stdin, which
avoids shell quoting), `defaults` and `--set KEY=VALUE` for `{placeholders}`, `env`,
`timeout_s`, `models` (the suite's model menu), and `parser` (`claude-stream` reads
`claude -p --output-format stream-json --verbose`: live events, cost, turns, tokens and MCP
tool calls). Without a parser, each stdout line is shown live as-is, and the agent can write
`AW_USAGE_FILE`: `{"turns","tool_calls","input_tokens","output_tokens","cost_usd"}`. The
contract is in `external_agent.py`'s docstring. Literal braces in a command need doubling
(`{{`, `}}`).

`claude-code-aura-mcp` is Claude Code with `--setting-sources ""` (none of your own settings,
hooks or CLAUDE.md), `--strict-mcp-config` with only the bench bridge, an allowlist of only
`mcp__aura`, and shell, file and web tools disallowed. Its one fixed instruction
(`{framing}`) tells it the requests are about the phone, the way every stock AndroidWorld agent
is told it operates a phone. It is the same for every task.

### Limits to know about

- **The harness cannot confine an agent to its tools.** The host has `adb`. Confinement lives in
  the profile. Check it before trusting a number.
- **MCP and on-device numbers differ in one rule.** `AuraAgent` reports `done=True` even after a
  timeout, so an on-device run that times out can still score. A command-line agent cannot.
- **Tool calls are counted from the agent's own stream**, not on the device: the MCP server
  does not log them.
- **Claude Code takes about 15 s to start** on every task. It counts toward the time limit.

## Gotchas the code handles

Each of these looks like an agent failure if it breaks:

- **AndroidWorld switches AURA's accessibility service off.** Building the env overwrites
  `enabled_accessibility_services` with only its own forwarder, and so does
  `refresh_env`. `restore_aura_a11y` adds AURA back before every task.
- **IPv6.** AndroidWorld's `svc wifi enable` gives the emulator an IPv6 address with no
  route, so model calls fail. IPv6 is disabled before every task.
- **AURA not listening.** After an install or reboot, the debug receiver is not registered
  until AURA has run once. Setup opens AURA itself and checks again.
- **The bridge losing the phone.** Before each MCP task the suite checks the bench bridge
  still reaches the phone and reconnects; if it cannot, the run stops rather than filing the
  rest as FAILs.
- **Timeouts and stops.** The agent's whole process tree is killed (Claude Code spawns
  children), and an on-device run is cancelled with `--es mode cancel`. Otherwise it keeps
  tapping during the next task's setup.
- **Goal quoting.** Goals go on stdin, or are shell-quoted for the broadcast, because
  AndroidWorld goals contain apostrophes and quotes.

## Files

| File | What it is |
|---|---|
| `suite.py`, `suite.html` | The browser suite: target, setup, task list, live run view. |
| `device.py` | The machine: emulator, adb, AURA's MCP server, the bench bridge and its pairing, the live display (`PhoneScreen`), the phone recorder. |
| `desktop.py` | Page recording: finds the suite's browser window and films it with ffmpeg. |
| `external_agent.py`, `agents/` | The runner (`run_one`) and the command-line agent contract. |
| `aura_androidworld.py` | The on-device adapter (`AuraAgent`) and the shared pieces: `RESULTS`, `SEED`, `scored_tasks`, `restore_aura_a11y`, `shift_device_year`, `find_adb`. |
| `referee.py` | Host side of the on-device cockpit: `/prepare` seeds a task, `/score` grades it. Binds to loopback only. |
| `gen_androidworld_tasks.py` | Regenerates `app/src/main/assets/eval/androidworld.json`, which is the cockpit's task list and `report.py`'s denominator. Run it after bumping the pinned commit. |
| `report.py`, `theme.py` | Build the static results site from a results folder; `theme.py` is shared with the suite page. |
| `judge_eval.py` | Unrelated to AndroidWorld. It replays AURA's completion judge against human verdicts. |

To bump AndroidWorld: check out the new commit, reinstall it into the venv, run
`gen_androidworld_tasks.py`, then update the commit hash above and in the setup steps.
