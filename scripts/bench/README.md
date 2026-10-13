# AndroidWorld benchmark

> **Last reviewed:** 2026-10-13.

Scores AURA on Google's [AndroidWorld](https://github.com/google-research/android_world)
benchmark.

## How it works

AndroidWorld grades a task by reading **device state** afterwards, through
`task.is_successful(env)`. It checks the row in the app's database, the file on disk, or
the setting that should have flipped. It never looks at how the agent got there.

So AURA, which brings its own perception, actions and model, is not driven step by step
like M3A. For each task:

1. AndroidWorld seeds the app data and pins the clock (`task.initialize_task`).
2. The goal goes to AURA over the debug broadcast `com.aura.aura_ui.AGENT_SPIKE`, or from
   the on-device eval cockpit.
3. AURA runs its own loop until it logs `Agent result:`, or until it times out and is
   cancelled.
4. AndroidWorld's validator gives the verdict. Nothing here edits or second-guesses it.

## How this differs from a stock AndroidWorld run

Publish these alongside any number. `report.py` prints them on the site.

| Deviation | Why |
|---|---|
| The **information-retrieval** family (25 of 116 tasks) is excluded, so the score is out of 91. | Those tasks are graded on a typed answer (`env.interaction_cache`), and AURA has no channel to set it. Their seed data also uses literal 2023 dates that the clock shift cannot move. Run them anyway with `--include_information_retrieval`, but they fail for harness reasons. |
| The device clock is shifted from 2023-10-15 to **2026-10-15**. | AURA calls a cloud model from the device. TLS on a phone that thinks it is 2023 rejects today's certificates. Every date in the scored tasks derives from `device_constants.DT`, so each task stays internally consistent. Weekdays change. |
| AURA gets a **wall-clock budget** (`--goal_timeout`, default 300 s) instead of AndroidWorld's step budget of 10 × complexity. | AURA's loop runs on the phone, so AndroidWorld sees a single step. |
| Seed **30**, one instance per task. | 30 is upstream `run.py`'s default, so task parameters match other published runs. |

## One-time setup

Everything below is git-ignored. Nothing in this folder needs an API key: AURA's provider
key is entered inside the app on the emulator and never leaves it.

1. **Emulator.** In Android Studio, create an AVD with hardware **Pixel 6**, system image
   **API 33 "Tiramisu", Google APIs, x86_64**, and the name **`AndroidWorldAvd`**. Use the
   Google APIs image, not the Play Store one: AndroidWorld needs `adb root`.
2. **Tools.** Install Python 3.11 and `ffmpeg`. Python 3.12 does not work, because
   AndroidWorld pins matplotlib 3.6.1, which has no 3.12 wheels. `ffmpeg` joins recordings
   longer than 180 s.
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
4. **Start the emulator** from the command line. The gRPC port is required:
   ```
   emulator -avd AndroidWorldAvd -no-snapshot -grpc 8554
   ```
5. **Install the benchmark apps.** This runs once per fresh AVD and takes several minutes:
   ```
   androidworld-venv/Scripts/python scripts/bench/aura_androidworld.py --perform_emulator_setup
   ```
6. **Install AURA.** It must be a debug build, because the broadcast receiver only exists
   in debug builds:
   ```
   cd aura-android && ./gradlew :app:installDebug
   ```
   Open AURA on the emulator and finish onboarding. Open it again after every reinstall
   or reboot. Then turn on its accessibility service
   and pick a provider, model and key in Settings → Agent Brain.

Check the setup with `scripts/bench/referee.py --selfcheck` and
`scripts/bench/report.py --demo`. Neither needs the emulator.

## Running

Start the emulator first, every time. There are three ways to run tasks:

| Entry point | Use it for |
|---|---|
| `aura_androidworld.py [--tasks=A,B]` | Batch runs from the terminal. It writes AndroidWorld's own checkpoints to `./runs/`. Resume a run with `--checkpoint_dir`. |
| `bench_server.py` → http://localhost:8777 | One task at a time, screen-recorded, with logs kept next to the verdict. |
| `referee.py` (port 8778) + Settings → Eval → AndroidWorld on the phone | Starting runs from the on-device cockpit. The phone reaches the PC at `10.0.2.2:8778`. |

`bench_server.py` and `referee.py` write `bench-results/<Task>/`. `report.py` builds the
static site in `bench-site/` from that folder.

**Before publishing results**, note that `agent.log` and the recordings contain everything
AURA logged and showed during the run. Use an emulator with no personal accounts or data.

## Gotchas the code handles

Each of these looks like an agent failure if it breaks:

- **AndroidWorld switches AURA's accessibility service off.** Building the env overwrites
  `enabled_accessibility_services` with only its own forwarder, and so does
  `refresh_env`. `restore_aura_a11y` adds AURA back before every task.
- **IPv6.** AndroidWorld's `svc wifi enable` gives the emulator an IPv6 address with no
  route, so model calls fail. IPv6 is disabled before every task.
- **AURA not listening.** After an install or reboot, the broadcast receiver is not
  registered until AURA is opened once. `aura_androidworld.py` checks for it up front and
  exits with that instruction, instead of timing out every task.
- **Timeouts.** A timed-out run is cancelled (`--es mode cancel`). Otherwise it keeps
  tapping during the next task's setup.
- **Goal quoting.** Goals are shell-quoted, because AndroidWorld goals contain apostrophes.

## Files

| File | What it is |
|---|---|
| `aura_androidworld.py` | The adapter and the shared pieces: `scored_tasks`, `restore_aura_a11y`, `shift_device_year`, `find_adb`. |
| `bench_server.py` | Local control room: runs one task at a time and screen-records it. |
| `referee.py` | Host side of the on-device cockpit: `/prepare` seeds a task, `/score` grades it. Binds to loopback only. |
| `gen_androidworld_tasks.py` | Regenerates `app/src/main/assets/eval/androidworld.json`, which is the cockpit's task list and `report.py`'s denominator. Run it after bumping the pinned commit. |
| `report.py`, `theme.py` | Build the static results site from `bench-results/`. |
| `judge_eval.py` | Unrelated to AndroidWorld. It replays AURA's completion judge against human verdicts. |

To bump AndroidWorld: check out the new commit, reinstall it into the venv, run
`gen_androidworld_tasks.py`, then update the commit hash above and in the setup steps.
