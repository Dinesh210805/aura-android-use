# On-device agent tests, driven by a coding agent

> **Status:** planned. **Written:** 2026-11-19. **Branch:** feat/agentcore.

## Goal
Running the benchmark to check a change to the agent is slow. The user wants a coding agent
(Claude Code on the PC) to test the agentcore agent directly on the real phone over USB. The
coding agent should be able to see and swap what the agent knows (memory, skills, learnings,
scheduled tasks), feed in test data instead of the user's real data, give the agent a goal, watch
what it did on screen, and judge the result. It then reports wrong behaviour and wrong saved data,
and fixes or deletes them once the user approves.

The user's agent data (chats, memories, skills, skill drafts, app maps, jobs, agent prefs) must
come back unchanged after a test session. The run log (`files/agentcore/traces/`) is the
exception: test runs are added to it like any other run.

## End product
On the PC, with the phone on USB and unlocked:

```
python benchmark/agenttest/agenttest.py backup
python benchmark/agenttest/agenttest.py run all
python benchmark/agenttest/agenttest.py verify
```

`run all` prints one line per case:

```
memory-recall      done      checks 0/0  benchmark/agenttest/out/memory-recall/1795100000000/
skill-followed     done      checks 1/1  benchmark/agenttest/out/skill-followed/1795100090000/
schedule-fires     tick      checks 2/2  benchmark/agenttest/out/schedule-fires/1795100180000/
```

The coding agent reads each folder, compares it with the case's `expect` text, and tells the user
pass or fail with a reason quoted from the trace or reply. `verify` prints `unchanged`.

## Safety rules
Test runs drive the real phone with the user's real accounts and the approval mode they chose.
- A case goal only reads, or acts in apps with no outside effect (Clock, Calculator, Settings
  screens without toggling, AURA itself). No case sends a message or an email, posts, pays,
  deletes, calls, or uses a connector. The bundled `send-email` and `whatsapp-message` skills are
  never the target of a case.
- Seeded skills and memories follow the same rule: their steps never send, post, pay or delete.
- On a timeout the script cancels the run (`--es mode cancel`), so nothing keeps going on its own.
- The script never sends `adb shell input`. AURA reads outside input as the user taking the phone
  back, and pauses.
- Provider and connector keys (`shared_prefs/aura_secure_keys.xml`) are never read, copied or
  written.

## Out of scope
- Answering the agent's mid-run questions (`ask_user`). A case that triggers one times out and is
  cancelled; such goals are rewritten to need no question.
- Multi-turn chats. A run started over adb gets a throwaway thread (`harnessTaskText` in
  `AuraOverlayService`), so each case is one goal. Memories, skills and jobs are global and still
  reach it.
- Seeding connectors. Their keys live in the key store, which is off limits; add it when a case
  needs it.
- The old `AuraAgent` (`CompanionTestReceiver` `mode agent`). The app runs agentcore now.
- AndroidWorld scoring. `benchmark/suite/` stays as it is.

## Steps

- [ ] **1. Back up and restore the agent's data**
  - **What changes:** `benchmark/agenttest/agenttest.py` (new) with commands `backup`, `restore`,
    `verify`. `.gitignore`: add `benchmark/agenttest/.backup/` and `benchmark/agenttest/out/`.
    `benchmark/README.md`: an "On-device agent tests" section (the commands so far and the Safety
    rules above), with **Last reviewed** bumped.
  - **Logic:**
    - Package `com.aura.aura_ui.feature.debug`. Serial from `--serial`, or the only attached device.
    - The data set, under the app's data dir: `databases/agentcore.db`, `-wal`, `-shm`;
      `shared_prefs/agentcore.xml`; and everything in `files/agentcore/` except `traces/`.
    - `backup`: `am force-stop` so SQLite and prefs are on disk, then one
      `adb exec-out run-as <pkg> tar -cf - <data set>` into `.backup/<ms>.tar`, plus `<ms>.sha256`
      with one `hash path` line per file (hashed on the PC from the tar). Keeps every backup.
    - `restore [<ms>]` (newest by default): force-stop, `adb push` the tar to
      `/data/local/tmp/agenttest.tar`, then as the app delete the data set and untar it. Delete the
      pushed tar. Start the app with `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`, then
      wait up to 40 s until a goal-less `com.aura.aura_ui.AGENT_SPIKE` broadcast logs
      `Missing --es goal` under `AuraAgentSpike` (the liveness probe `benchmark/suite/device.py`
      uses; copied, because that module imports AndroidWorld at load).
    - `verify [<ms>]`: force-stop, tar the data set again, hash it the same way, and print
      `unchanged` or the paths that differ. Restart the app as `restore` does.
  - **Done when:** on the USB phone, `backup`, then `restore`, then `verify` prints `unchanged`;
    and after `restore`, `adb shell dumpsys accessibility` lists AURA's service among the bound
    services.

- [ ] **2. A debug trigger for background work**
  - **What changes:** `aura-android/app/src/debug/java/com/aura/aura_ui/debugtools/CompanionTestReceiver.kt`:
    a `"tick"` mode, also named in the unknown-mode message.
  - **Logic:** `"tick" -> { AgentCore.get(app).tick(); report("tick", "", true, "tick done") }`.
    `tick()` is what `AgentWorker` runs every 15 minutes: due jobs (`Scheduler.runDue`), skill
    curation, and the learning and screen reviews (those two only when the engine is idle and the
    battery allows).
  - **Done when:** after `./gradlew :app:installDebug`,
    `adb shell am broadcast -n com.aura.aura_ui.feature.debug/com.aura.aura_ui.debugtools.CompanionTestReceiver -a com.aura.aura_ui.debug.COMPANION_TEST --es mode tick`
    is followed by a `result:` line containing `tick done` in `adb logcat -d -s CompanionTest`.

- [ ] **3. Seed a case, run its goal, restore**
  - **What changes:** `agenttest.py`: command `run <case>`. `benchmark/agenttest/cases/memory-recall.json`
    (new). README section: the case format.
  - **Logic:** a case file is JSON. Only `expect` is required, plus `goal` or `tick: true`:
    ```json
    {
      "goal": "What's my sister's name? Answer from what you know, don't open any app.",
      "sql": ["INSERT INTO memories(kind,text,createdAtMs,source) VALUES('USER','My sister is called Priyanka',0,'SAID')"],
      "files": {},
      "tick": false,
      "timeout_s": 300,
      "checks": [],
      "expect": "Says Priyanka. Calls no phone tool."
    }
    ```
    `run <case>`, with step 6 in a `finally`:
    1. Refuse unless a backup exists.
    2. Restore the newest backup (so every case starts from the same state), then force-stop.
    3. Seed: pull `agentcore.db` with its `-wal` and `-shm` into a temp folder. Open it with
       Python's `sqlite3` (this replays the WAL), run each `sql` statement, and close it (this
       folds the WAL back in). Push the db back and delete the phone's `-wal` and `-shm`. Push each
       `files` entry (path relative to `files/agentcore/`). Pushes go to `/data/local/tmp`, then
       `run-as <pkg> cp` into place.
    4. Start the app and wait for the liveness probe. Run `svc power stayon usb`, and require
       `mWakefulness=Awake` in `dumpsys power`. Send the `CompanionTestReceiver`
       `--es mode pause --es op resume` broadcast, so a leftover takeover lock does not refuse the
       first tap. Run `logcat -c`.
    5. If `goal`: `am broadcast -a com.aura.aura_ui.AGENT_SPIKE --es goal <goal>`, with the goal
       shell-quoted for the device shell. Poll `logcat -d -s AuraAgentSpike` every 2 s for
       `✅ Agent result: ` (status `done`, the rest of the line is the reply) or `Spike run threw`
       (status `error`). Past `timeout_s` (default 300), send `--es mode cancel`; status `timeout`.
    6. Restore the newest backup.
    Print `<case>  <status>  <first 80 chars of reply>`.
  - **Done when:** `python benchmark/agenttest/agenttest.py run memory-recall` prints status `done`
    with a reply that names Priyanka, and `verify` prints `unchanged`.

- [ ] **4. Collect what happened, run the checks, tick cases**
  - **What changes:** `agenttest.py`: collection, checks, `tick`, and `run all`.
  - **Logic:**
    - Before the goal, note each `files/agentcore/traces/steps-*.jsonl` file's line count. After
      it, write into `benchmark/agenttest/out/<case>/<ms>/`:
      - `steps.jsonl`: the lines added since (all day files, in order),
      - every `image` file those lines name, pulled from `traces/`,
      - `final.png` from `adb exec-out screencap -p`,
      - `logcat.txt`: `logcat -d -s AuraAgentSpike CompanionTest`.
    - If `tick`: after the goal (or instead of one), send the step-2 broadcast and wait up to 120 s
      for `tick done`; status `tick` when it arrives.
    - Checks: force-stop, pull the db as in the seed step, and run each check's `sql`. The first
      column of the first row is compared with `equals`. `{now}` in the SQL is replaced with the
      current time in ms, taken before the run.
    - `result.json`: `case`, `goal`, `status`, `reply`, `seconds`,
      `checks` (list of `sql`, `equals`, `got`, `ok`), and `expect`.
    - Summary line: `<case>  <status>  checks <ok>/<total>  <out folder>`.
    - `run all` runs every case in `cases/` in name order.
  - **Done when:** `run memory-recall` writes `result.json` with a non-empty `reply`, a
    `steps.jsonl` containing a `"type":"start"` and a `"type":"end"` record, and `final.png`; then
    `verify` prints `unchanged`.

- [ ] **5. Two more starter cases and the judging routine**
  - **What changes:** `benchmark/agenttest/cases/skill-followed.json`, `schedule-fires.json` (new).
    README section: the judging routine.
  - **Logic:**
    - `skill-followed`: seeds `skills/next-alarm/SKILL.md`:
      ```
      ---
      name: next-alarm
      description: Tell the user when their next alarm rings, by reading it in the Clock app.
      ---
      Open the Clock app, go to the Alarm tab, and read out the earliest enabled alarm.
      ```
      Goal: "When does my next alarm ring?" Check: the run called `skill_view`, so
      `steps.jsonl` has a `tool` record with `"tool":"skill_view"`. That is checked by the script as
      a `trace_has` entry (a string that must appear in `steps.jsonl`), the one non-SQL check kind.
    - `schedule-fires`: needs a saved provider key, like every run. Seeds
      `INSERT INTO jobs(prompt,everyMinutes,nextRunMs) VALUES('Say the word pineapple.',1440,0)`,
      no goal, `tick: true`. Checks: `SELECT lastResult IS NOT NULL FROM jobs` equals 1, and
      `SELECT nextRunMs > {now} FROM jobs` equals 1 (it ran and was rescheduled). A failed turn
      leaves `lastResult` null, and `logcat.txt` shows why.
    - Judging routine, for any coding agent: read `result.json`, `steps.jsonl` and the screenshots.
      Give each case pass or fail with one reason quoted from the trace or reply. List any saved
      memory, skill draft or learning that is wrong. Propose fixes. Change code or delete data only
      after the user approves.
  - **Done when:** `python benchmark/agenttest/agenttest.py run all` prints three summary lines,
    with `skill-followed` at `checks 1/1` and `schedule-fires` at `checks 2/2`. The coding agent
    gives the user a pass or fail with a reason for each case. Then `verify` prints `unchanged`.

## Left out on purpose
- **A new app-side seeding API.** Swapping files while the app is stopped reaches every store with
  no app code, and stays right when tables change.
- **Restoring `traces/`.** It is a log. Test runs add to it like any run, and the run-log screen
  keeps its own retention.
- **Automatic lock-screen detection.** The keyguard state in `dumpsys` differs by OEM. The user
  keeps the phone unlocked, and `final.png` shows it if not.
- **Machine-only verdicts.** SQL and `trace_has` checks cover state. Whether a reply or a screen
  was right needs the coding agent to read it.
- **The aura MCP screen tools.** `screencap` is read-only and always available. The MCP tools can
  be used alongside when they are connected.
