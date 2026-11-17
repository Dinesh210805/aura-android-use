# Spec: long tasks that finish fast

> **Status:** in progress. **Written:** 2026-11-16. **Branch:** `feat/agentcore`.

## Goal

The new agent (`agentcore/`) handles short tasks, but on long multi-step tasks it is **slow**
(far more model calls and taps than needed) and it **loses track or loops**. The tasks that
have to work when we ship:
- long flows inside one app (Swiggy to the cart, Rapido to the fare screen)
- tasks across apps (read something in one app, use it in another)
- doing the same thing for a list (message 5 people, add 4 expenses)
- looking something up on the web or in a connector, then acting on the phone

Today nobody can see *why* a long run goes wrong. The agent has tools to look back at the
screens it has seen (`screens`, `screen`, `screen_diff` in `ToolHub.kt`), but that history
never reaches the run log and lasts for one turn only. We can't tell whether the agent uses it,
or what the agent believed it was looking at when it tapped.

**What is measured:** AURA's own on-device agent (`agentcore/`), on AndroidWorld in the
emulator (`benchmark/`, the suite's on-device target). The suite hands each goal to the phone
over the debug broadcast; `AssistantForegroundService.runViaOverlay` gives it to the overlay,
which runs agentcore while the "Use for voice and the overlay" switch is on. The model runs on
the phone with the user's key, and the PC only seeds the task and asks AndroidWorld for the
verdict. AndroidWorld's setup and grading need root, the emulator console and a pinned clock,
so it runs only in the emulator, never on the user's own phone.

So the order is:
1. Make every screen visible in the run log, and measure today's agent on an AndroidWorld subset.
2. Give the agent a one-line note per action, a better history search, and a map of each app
   built from the screens of earlier runs, so it knows the way through an app it has used before.
3. Fix what the measurements show, and ship. The fixes for the four task kinds are written
   from the two runs (step 10), not guessed now.

## End product

The user runs "Add these expenses in Pro Expense: coffee 4.50, lunch 12, taxi 23" on the
emulator through the benchmark suite. Afterwards:
- On the phone, the run's page in the Run log shows rows in time order:
  `S4 · Pro Expense: Add expense, Name, Amount` with the screen text folded under Details, then
  the tap, with the agent's own line under it ("Seen: empty add-expense form, filling name").
- When the agent calls `screens("taxi")`, it gets back `S9: Taxi  23.00` and the run log shows
  that call as "Searched past screens for 'taxi': 1 hit".
- The run page has a **Screens** tab: each screen is a file with its versions and green and red
  diffs, and a viewer swipes through every screen the run passed through, including the ones the
  model never read ("not shown to the model").
- After a few Instagram runs, `app_map("Instagram")` tells the agent which screen has
  `Add account` and which tap leads there. No username, name or message from those runs is in it.
- On the PC, `benchmark/results/aura-on-device/efficiency.md` has one row per task: pass or fail,
  time, actions, model calls, distinct screens, screen revisits, history-tool calls, and the
  share of actions carrying a note.
- The final run meets the targets set from the baseline, and a signed APK goes out.

## Out of scope

- The Claude Code + MCP benchmark target. There Claude does the planning, so it doesn't test
  this agent's loop.
- Deleting the old agent under `agent/`. That needs its own spec once this one ships.
- The run-log timing items in `feature-reliable-agent.md` (3.1–3.3), plus crash Continue (4.2)
  and voice mid-task (4.3). They stay there.
- Choosing the provider key. The user decides on paid or free Gemini Flash and starts every
  suite run; an agent never starts `benchmark/suite/suite.py`.

## Steps

Every step that changes a folder with a README (`agentcore/`, `benchmark/`) updates that README
in the same commit (AGENTS.md).

### Part A: measure today's agent (logging, plus the one change noted after 2b)

- [x] **1. Keep screen data out of cloud backup.**
  - **What changes:** `res/xml/backup_rules.xml` and both sections of
    `res/xml/data_extraction_rules.xml`.
  - **Logic:** add `<exclude domain="file" path="agentcore/traces/" />` and
    `<exclude domain="file" path="agentcore/screens/" />` (the store from step 7). Both hold
    screen text and screenshots. This replaces item 2 of `feature-reliable-agent.md`.
  - **Done when:** `grep -c "agentcore/" aura-android/app/src/main/res/xml/backup_rules.xml aura-android/app/src/main/res/xml/data_extraction_rules.xml`
    prints `…backup_rules.xml:2` and `…data_extraction_rules.xml:4`.

- [ ] **2. Every screen the run passed through is in the run log, and the model can look it up.**

  Part 2a below is built and seen on the phone (Instagram runs, 2026-11-17), but it records a
  screen only when the model *reads* one (`read_screen`, `perceive_screen`, …). A deep link,
  three taps in a row or a `do_steps` move the phone through screens nobody recorded. Part 2b
  closes that: AURA records every screen change itself, without the model. Architecture §6.5
  ("a per-run record of every screen the run saw") is the design.

  **2a. Screens the model read** (built, test pending)
  - **What changes:** `RunJournal.kt` (`TYPE_SEEN = "seen"` (new); `seen(run, id, caption, text)`
    (new); `LogKind.SEEN` (new); `RunLogBuilder`; `StepPhrase`), `ToolHub.kt` (where
    `turn.screens.record` returns an id), `RunLogUi.kt`, `agentcore/README.md`.
  - **Logic:**
    - Each recorded screen writes one `seen` record: `detail` = the id (`S3`),
      `text` = the `SEEN:` caption, `result` = the screen text the model got. It goes through the
      journal's existing redaction, retention, delete and export.
    - The existing `screen` record (`TYPE_SCREEN`, `LogKind.SCREEN`) stays as it is: it is the
      annotated *picture* sent to a vision model. `seen` is the *text*. Both are rows.
    - **Sensitive apps:** when the front app is one `SensitivePolicy.screenPackage` blocks, the
      record's `result` is `(not recorded)`. This is the same check `ToolSources.shotFor` makes
      for tap screenshots. It moves to a `PhoneToolSource.hiddenApp(): Boolean` member that
      `shotFor` uses. `ToolHub` gets it as a constructor lambda `hiddenApp: suspend () -> Boolean`,
      set in `AgentCore` the way `appLabel` is.
    - `RunLogBuilder` turns `seen` into a row titled `S3 · <caption>` with the text under
      Details. In a changes-only screen, `+` lines show green and `-` lines red.
    - History calls read as "Searched past screens for 'taxi': 1 hit", "Looked back at S3" and
      "Compared S3 with S5" (`StepPhrase`). `screens`, `screen` and `screen_diff` join
      `StepPhrase.LOOKS`, so they are look rows and don't count as actions.
    - Rows stay in time order, so a tap's screenshot comes right after the screen it acted on.
    - This replaces items 1.1 and 1.2 of `feature-reliable-agent.md`.
  - **Done when:** `RunLogTest` passes two new cases:
    - Journal records (seen S1, tap, seen S1.1, a `screens` call with query `x`) give four rows
      in that order, titled `S1 · …`, the tap, `S1.1 · …`, and "Searched past screens for 'x'".
    - A `seen` record with `(not recorded)` has no screen text under Details.
    - The run's `actions` count does not include the `screens` call.

  **2b. Screens nobody read: AURA records every screen change**
  - **What changes:**
    - `:mcp-server`: `tools/ScreenSnapshot.kt` (new; built, `ScreenSnapshotTest` passes), public `object ScreenSnapshot` with
      `suspend fun read(bridge: UiTreeBridge): Snapshot?` and public
      `data class Snapshot(val pkg: String, val text: String, val contentKey: Long, val layoutKey: Long, val blind: Boolean)`.
      Plain `Long`s, because `ScreenSignature` is `internal` and `:app` cannot name it.
    - `ToolSources.kt`: `PhoneToolSource` gets a field `private val uiTree = AppUiTreeBridge()`
      (new), used both where `connected()` builds the server and by
      `snapshot(): ScreenSnapshot.Snapshot?` (new), beside `hiddenApp()`.
    - `ToolHub.kt`: constructor lambda `snapshot: suspend () -> ScreenSnapshot.Snapshot?` (new),
      set in `AgentCore` the way `hiddenApp` already is; starts and stops the watcher; model reads
      go through it too.
    - `agentcore/ScreenWatcher.kt` (new): `ScreenWatcher(read, onScreen, onLimit, lastEventMs,
      now, quietMs, busyMs)`, all injected so the test needs no device.
    - `ScreenHistory.kt`: `Entry` gets `byModel`, `full`, `pkg`, `contentKey`, `layoutKey` (new);
      `passed(snapshot)` (new); `record(text, snapshot)` takes the snapshot read with it.
    - `RunJournal.kt`: `StepRecord.by` (new, default `""`); `seen` gets `by`. `RunLogBuilder`.
    - `agentcore/README.md`.
  - **Logic:**
    - **The read must not disturb the model.** `read_screen` updates `perceptionCache` (the
      `som_id` numbers the next `tap` resolves) and `lastSeenScreen` (the "CHANGES since your
      last look" baseline). A silent read through it would renumber elements behind the
      model's back. `ScreenSnapshot.read` calls `bridge.snapshot()`, `ScreenSignature.of`,
      `UiTreeToElements.extract` and `ScreenPayload.render(elements, pkg, view = View.Full, ids = null,
      idle = true, settled = true, escalateReason = ReadScreen.escalateReason(...) as read_screen
      computes it, offscreen = ScreenText.offscreen(...))`. It does not call `ScreenSettle`
      (the watcher has already waited for quiet) and writes to no cache. It returns null when
      the bridge answers `ok = false` (accessibility off). When `render` returns null (nothing
      to draw: a blind or empty screen), `text` is `SEEN: <pkg> | (no readable elements)`, so
      every snapshot has a caption. A passed entry's caption is the `SEEN:` line of `text`. It lives in
      `:mcp-server` because those helpers are `internal` there.
    - **Its numbers are not tappable.** A snapshot's `som_id`s are numbered fresh, not from
      `perceptionCache`. The `screen(id)` tool says so in its description: "a record; to act,
      read the screen".
    - **When to read.** One watcher per turn. It starts at the turn's first tool call with
      `origin == ToolOrigin.PHONE`, so a pure chat turn records nothing, and is cancelled when
      the turn ends. It keeps `seenEventMs` (the `ScreenIdle.lastEventMs` value at the previous
      tick) and `movedAtMs` (when that value first moved after the last read). Every 250 ms it
      compares `ScreenIdle.lastEventMs` with `seenEventMs`. Every accessibility event outside AURA's own package moves that value,
      including scrolls, text edits and list updates. When it has moved, it reads once either:
      - the screen has been quiet for `ScreenIdle.DEFAULT_QUIET_MS` (350 ms), or
      - `now - movedAtMs >= BUSY_READ_MS = 2_000` while events keep coming (a spinner or a video), so a busy
        screen is still recorded at most every 2 s.
      A frame shorter than 350 ms inside a burst (a transition) is not recorded. Nobody could
      have read it.
    - **A plain timeline; nothing is guessed when saving.** Every entry, from the model or the
      watcher, gets the next id: `S1`, `S2`, `S3`, in time order. A snapshot with the same
      content as the newest entry is not saved again: same `pkg` and `contentKey`, or, when
      `blind` (WebView, game, canvas), the newest entry is also blind in the same `pkg`.
      Otherwise `passed(snapshot)` appends an entry with `byModel = false`.
    - **Revisits are a link, not a guess.** An entry whose `pkg` and `contentKey` equal an earlier
      entry's carries `sameAs` = that entry's id, and the `screens` list shows it as `S15 = S10`.
      The `screens`, `screen` and `screen_diff` tools cover passed screens too, so the model can
      look one up without going back.
    - **Why no versions (`S3.1`) in the ids:** deciding "same screen, new content" needs a
      layout guess, and the layout key changes on every scroll. A wrong guess would make every
      id after it wrong. Grouping screens into files is done only for display (2c), where a
      wrong group costs nothing but the view.
    - **Model reads.** `ToolHub` takes a snapshot alongside each model screen read (one tree
      read, no tokens). If its `contentKey` equals the newest entry's, the watcher got there
      first: that entry becomes `byModel = true` and its id is returned. Otherwise a new entry
      is appended with `byModel = true`. Each entry's `full` is the snapshot's full text, so a
      changes-only read can still be opened whole.
    - **Stuck detection** (`distinct`) counts model-read entries only: `record` updates it,
      `passed()` never does. A clock or "updated 5 s
      ago" on a passed screen must not hide a loop.
    - **Sensitive apps:** when `hiddenApp()` is true, the entry and the journal record carry
      `(not recorded)`, as in 2a.
    - **Run log:** each entry writes a `seen` record with `by = "model"` or `by = "aura"` and
      `args` = `{"same_as": "S10", "frame": {…}}`: the link when it is a revisit, and the
      screen's `ScreenFrame` (every element's box, label and flags, about 10 KB) so the Screens
      view can draw it (2c). A hidden app's record has no frame. The record's
      `result` is the entry's `full` text (the whole screen, never the changes-only view), so the
      table's distinct and revisit counts compare whole screens.
      An `aura` row is titled `S4 · <caption> · not shown to the model` and drawn dimmer, so
      the log shows what the agent missed. A model read that marks an earlier `aura` entry
      writes a second `seen` record with the same id and `by = "model"`, and the builder shows
      that id once, as model-read.
    - **Bound:** at most `ScreenWatcher.MAX_PASSED = 300` passed entries per turn. After that,
      it writes one note "Stopped recording passed screens at 300" and stops.
  - **Done when:**
    - `ScreenSnapshotTest` (new, `:mcp-server`, on `bench/MockPhone`) passes:
      - Starting from a fresh `PerceptionCache`, below its header `ScreenSnapshot.read` gives
        the same element lines as `read_screen` with no arguments, on a still screen.
      - After `read_screen`, a `ScreenSnapshot.read`, then
        `perceptionCache.resolveForGesture(som_id)` (extension in `tools/ToolHelpers.kt`), the id resolves to the element
        `read_screen` numbered.
      - `lastSeenScreen` is unchanged by the snapshot.
    - `ScreenWatcherTest` (new, fake clock, event time and reader) passes:
      - an event, then 350 ms quiet, gives one passed entry; events still coming give none
        until 2 s;
      - two reads with the same content give one entry;
      - a change back to the same content adds no entry.
    - `ScreenHistoryTest` passes: passed and read screens share one timeline (`S1`, `S2`, `S3`);
      the same content twice in a row is one entry; a screen equal to an earlier one is listed
      as `S3 = S1`; a model read with the same `contentKey` marks the entry read and adds none;
      a changes-only read opens whole; passed screens leave `distinct` alone; blind screens of
      one app are one entry.
    - `ToolHubWatchTest` (new): a turn whose only call is a non-phone tool starts no watcher; its
      first phone call starts one, and the turn's end stops it.
    - `RunLogTest` passes one new case: a `seen` record with `by = "aura"` gives a row titled
      `S1 · … · not shown to the model`, and a later `by = "model"` record for `S1` turns it
      into a normal row.
    - On the phone (seen 2026-11-17, with the earlier numbering): an Instagram run recorded 18
      screens, 6 of them marked "not shown to the model". With the timeline, the same run lists
      the "All content" screen it returned to as `S12 = S10` and `S15 = S10`.

  **Behaviour note for Part A:** 2b is the one change in Part A the model can notice:
  `screens`, `screen` and `screen_diff` also return passed screens. The baseline run includes it.

  **2c. A Screens tab: every screen as a file with versions, and a swipe viewer**
  - **What changes:** `RunDetail.kt` (`StepFilter.SCREENS` (new): a fifth "Screens" choice in the
    run page's All / Actions / Model / Failed switch; private `ScreenFileCard`, `VersionRow` and
    `ScreenViewer` composables (new)), `RunJournal.kt` (`RunLog.screens: List<ScreenFile>` (new);
    `data class ScreenFile(val id: String, val caption: String, val versions: List<ScreenVersion>)`
    and `data class ScreenVersion(val id: String, val atMs: Long, val caption: String, val byModel: Boolean, val text: String?, val sameAs: String?)`
    (new; 8c adds `description`); `ScreenFiles.group` (new); `RunLogBuilder`), `ScreenHistory.kt` (the private `lineDiff` in its companion is
    promoted to an `internal` top-level `screenLineDiff(a, b)`, so the tab and `screen_diff` share
    it), `RunLogTest`,
    `agentcore/README.md`.
  - **Logic:**
    - `RunLogBuilder` groups a run's `seen` records into files, for display only: an entry joins
      the file of its `same_as` entry, else the newest file of the same app whose caption shares
      at least half of its labels with this one (`ScreenFiles.group` (new), pure), else it
      starts a new file. A wrong group only changes the view; ids, tools and the log are
      untouched. When one id has records from both `aura` and `model`, it is one version with
      `byModel = true`. `text` is null for `(not recorded)`. `description` comes from step 8c,
      null until then.
    - **Files list:** one row per file: `S4 · <caption> · 3 versions`, with a dim "not shown to
      the model" mark when no version was. Opening a file lists its versions; each one after the
      first shows `screenLineDiff(previous, this)` with `+` lines green and `-` lines red. Colour
      is for people; the model's `screen_diff` stays plain text.
    - **Picture:** each version with a frame is drawn on a Compose `Canvas` when opened, the way
      the UI-stream viewer draws a frame: every element's box at its real position, scaled to
      the page width with the phone's aspect ratio, its label inside when it fits, tappable
      elements in the accent colour. It is a live picture of the screen; no image file is
      stored. `ScreenSnapshot.Snapshot.frame` (new) carries it, from `ScreenFrame.encode`.
    - **Viewer:** tapping a version opens a full-screen `HorizontalPager` over every version of
      the run in time order (the slideshow). Each page is the version's ASCII grid as monospace
      `Text` at 9 sp, scrolling both ways (the grid is about 280 columns; shrunk to fit it would
      be unreadable). The viewer has three modes: **Picture** (the default), **Text** (the
      grid) and **Diff** (changed lines coloured). Nothing is stored as an image; it is drawn when
      opened.
  - **Done when:**
    - `RunLogTest` passes: `seen` records S1 (aura) and S1 (model) "Insta | Settings | Privacy",
      S2 "Insta | Settings | Privacy | Blocked", S3 "Insta | Reels", S4 with `same_as` S1 give
      `screens = [S1 with S1, S2, S4; S3 alone]`, S1's version has `byModel = true`, and a
      `(not recorded)` version has `text = null`.
    - On the phone: open an Instagram run and tap **Screens**. Each file shows its versions with
      green and red lines, and the viewer swipes through the screens drawn as the grid.

- [ ] **3. No time budget: the suite waits until the agent finishes.**
  - **What changes:** `ONDEVICE_TIMEOUT_S` in `benchmark/suite/suite.py`, the `goal_timeout`
    default in `benchmark/suite/aura_androidworld.py`, the `TARGETS` wording in `suite.py`, and
    the on-device deviation table in `benchmark/README.md`.
  - **Logic:**
    - Time is not part of the score. We judge speed from the run logs and the efficiency table.
    - The 300 s cut-off goes. Both values become 3,600 s, the agent's own 60-minute turn limit
      (`TurnLimits.maxWallClockMs` in `AgentModels.kt`). The agent always ends first, so the suite's value is only a guard for a
      hung phone, never a budget.
    - The README row "A wall-clock budget (300 s)" becomes "No budget: each task runs until the
      agent answers, fails or hits its own 60-minute limit."
    - Change together: `ONDEVICE_TIMEOUT_S`, `goal_timeout` and the README row.
  - **Done when:**
    - `grep -n "ONDEVICE_TIMEOUT_S = \|'goal_timeout', " benchmark/suite/suite.py benchmark/suite/aura_androidworld.py`
      shows `3600` in both.
    - On the one-task run in step 4, `meta.json` has no `timed_out: true`.

- [ ] **4. The suite copies each task's run log and writes an efficiency table.**
  - **What changes:** `benchmark/suite/device.py` (`trace_lines`, `trace_marks`, `pull_traces` (new)), `suite.py`
    (`_worker` calls it before and after `external_agent.run_one`),
    `benchmark/suite/efficiency.py` (new), `benchmark/README.md`.
  - **Logic:**
    - Before a task, copy the JSONL files with
      `exec-out "run-as <pkg> sh -c 'cd files/agentcore/traces && tar -cf - *.jsonl'"`: a shell
      running as the app expands the glob, and the screenshots beside them are not copied.
      Remember each file's line count (`device.trace_marks`). After the task, copy them again and keep only the lines past those counts, per
      file name, into `results/aura-on-device/<task>/agentcore/`.
      - Why per file and by line count: AndroidWorld resets the clock to the same date every
        task, so timestamps can't split runs, and a day roll-over starts a new file.
      - Needs a debuggable build for `run-as`, like `pull_sessions`.
    - `efficiency.py` reads every task folder and its `meta.json` and writes `efficiency.md`.
      - Columns: task, pass, seconds, actions, model calls, input tokens, distinct screens
        (distinct `seen` `result` texts in the task), revisits (a `seen` `result` equal to an
        earlier one in the same task; both skip `(not recorded)`), unread (ids whose `seen` records are all `by == "aura"`:
        screens the run passed through and the model never read; step 2b), history-tool calls,
        notes (% of actions whose arguments carry `seen`; 0 until step 6), and the longest run
        of the same failing call.
      - A totals row gives the pass rate and the medians.
    - This replaces item 4.1 of `feature-reliable-agent.md`. The suite page's live step view
      (`files/mcp_logs`) stays the old agent's; the table is the measure.
  - **Done when:**
    - `python benchmark/suite/efficiency.py --self-test` prints `ok`. It builds the table from a
      two-task sample inside the script and checks every count.
    - After a one-task suite run the user starts, `efficiency.md` has that task's row with
      actions > 0.

  **Steps 3 and 4 wait for AndroidWorld.** The benchmark is put off because it is buggy. Until
  it works, steps 5 and 9 use the in-app eval suite instead (decided 2026-11-17).

- [ ] **5. Baseline run: the 8 long eval tasks on the Part A APK.**
  - **What changes:** nothing in code. The tasks are the `category: "long"` and `"refuse"`
    tasks 38–45 in `assets/eval/tasks.json` (Settings → Eval suite): Settings Display and
    Battery, About phone and Wi-Fi, three alarms, Play Store VLC against Spotify, YouTube lofi
    titles, Calculator to a timer, the top battery app's storage, and the Google Pay refusal.
  - **Logic:** the user runs the 8 tasks on their own phone with the Part A APK
    (`dist/apks/feature-long-tasks-part-a.apk`). The run logs are pulled with
    `adb exec-out run-as com.aura.aura_ui.feature.debug tar c files/agentcore/traces`, and per
    task we count: pass or fail, seconds, actions, distinct screens and revisits (from the `seen`
    records, as step 4 defines them), and unread screens. Nothing in the agent has changed yet.
    They replace the AndroidWorld subset because 8 hand-checked tasks on real apps are what the
    user can run today.
  - **Done when:** the table of the 8 Part A runs is copied into this spec under "Baseline".

### Part B: give the agent a note, a better memory of screens, and app maps

- [x] **6. The agent writes one line about the screen with each action.**
  - **What changes:** `ToolSources.kt` (`PhoneToolSource.withSeen(descriptor)` (new), a pure
    helper that `PhoneToolSource.list` applies),
    `ToolHub.kt` (`HubTool.gated`), `ScreenHistory.kt` (`Entry.note`, `annotate` (new)),
    `SystemPrompt.SCREEN_GUIDE` in `ContextAssembler.kt`, `agentcore/README.md`.
  - **Logic:**
    - Phone action tools are the tools with `origin == ToolOrigin.PHONE && risk != ToolRisk.READ`,
      the same test the hub uses for the trace. `PhoneToolSource.list` adds an *optional*
      string parameter `seen` to each of them: "One line: the screen you are acting on and why
      this action." The screen guide asks for it on every action.
    - At the top of `HubTool.gated`, `seen` is read and removed from `args`. The stripped copy is
      what `callKey`, the gate, `sendsOut` and `dispatch` get, so a note can't change a safety
      decision, reach the phone server, or make a repeated failing call look new.
    - The note goes on the newest screen in the history (`turn.screens.annotate(note)`), because
      that is the screen the model decided on. `run()` still journals the raw arguments, so the
      tool row in the log keeps the note too, and the action row shows it as "Seen: …".
    - It is optional because a call without it must still run. The `notes` column in
      `efficiency.md` shows how often it is missing.
    - `do_steps` is a NATIVE tool, so it does not get the parameter.
  - **Done when:** `ToolHubSeenTest` (new) passes:
    - `withSeen` adds an optional `seen` to a `tap` descriptor and leaves its required
      parameters unchanged.
    - A `tap` with `seen` reaches a fake source without `seen`, and `screens()` lists the newest
      screen with that note.
    - A `tap` without `seen` still reaches the source.
    - Two failing taps with different notes count as the same repeated call.

- [x] **7. Screen history lasts for the whole chat.**
  - **What changes:** `ScreenHistory.kt` (`Entry` gets `task` and `hidden`; `encode`/`decode`
    (new)), `ThreadStore` (constructor parameter `screensDir: File` (new); `loadScreens`,
    `saveScreens` (new); `delete` also deletes the file), where `AgentCore.kt` builds
    `ThreadStore`, `AgentEngine.kt` (load next to `threads.loadContext`, save in `save`),
    `ThreadStoreTest` (a temp directory), `agentcore/README.md`.
  - **Logic:**
    - The history is stored at `files/agentcore/screens/<threadId>.json` and saved after every
      step together with the context. Numbering continues across turns, so a task the chat hands
      to the overlay, a `/goal` turn and a "continue" all go on at `S4`, not `S1`. This replaces
      item 1.3 of `feature-reliable-agent.md`.
    - `Entry.task` is the turn's run id, so earlier tasks can be told apart from the current one.
    - `encode`/`decode` keep `pkg`, `contentKey`, `layoutKey` and `byModel` (step 2b), so the next
      turn dedupes and numbers against the last saved screen. On disk only `full` is written
      (as the entry's text); the changes-only `text` is not, so the bound below holds.
    - Bounds: at most `MAX_KEPT = 300` entries per chat, oldest dropped first. Each text
      *written to the file* is cut to `MAX_KEPT_CHARS = 8_000` characters, which keeps a file
      under about 2.5 MB. The in-turn entry keeps the full text, so `screen` and `screen_diff`
      in the same turn are not cut.
    - **Sensitive apps:** an entry recorded while `hiddenApp()` is true has `hidden = true`. The
      model still reads it during the current turn, because it already saw it. It is saved
      without text or note.
    - Stuck detection (`distinct`) keeps counting the current task only.
  - **Done when:** `ScreenHistoryTest` passes three new cases, and `ThreadStoreTest` passes one:
    - Recording after encode and decode of three screens gives `S4`.
    - A hidden entry decodes with an empty text and no note.
    - With 301 entries, the oldest is gone.
    - `ThreadStoreTest`: `delete` removes the thread's screens file.

- [x] **8. `screens` finds words, notes and earlier tasks and returns the matching lines.**
  - **What changes:** `ScreenHistory.log`, the `screens` tool description in `ToolHub.kt`,
    `agentcore/README.md`.
  - **Logic:**
    - **No query:** the current task's screens, one line each. The format changes from today's
      `S3  caption` to `S3 · caption · note: …` (no note part when there is none).
    - **With a query:** every screen in the chat whose text, caption or note contains it
      (case-insensitive), newest last. Each hit is `S3: <the matching line>`, with at most
      3 lines per screen and `MAX_LOG` lines in total. Hits from an earlier task are marked
      `(earlier task)`, and hidden screens never match.
    - The description tells the model to search here before going back to a screen to read it
      again.
  - **Done when:** `ScreenHistoryTest` passes four new cases:
    - `log("taxi")` returns `S9: 3 Taxi  23.00` (the grid line without its borders, som_id kept).
    - A query that only matches a note returns that screen.
    - A hit from an earlier task carries `(earlier task)`.
    - A hidden screen never matches.

- [x] **8c. After a run, a cheap model describes each screen of each app.**
  - **What changes:** `agentcore/ScreenReview.kt` (new), with
    `data class Control(val label: String?, val role: String, val region: String)` and the pure
    `ScreenReview.controls(frame)` (new);
    `RunJournal.kt` (`seen` gets `layoutKey: Long?`, written as `layout_key` into the same `args`
    object that already holds `same_as` and `frame`; `ToolHub` passes `snap.layoutKey` at its two
    `seen` calls; `TYPE_SCREEN_NOTE = "screen_note"` and `screenNote(run, id, text)` (new);
    `ScreenVersion.description` (new), from the run's `screen_note` records); `UiWords.kt`
    (new) with `assets/ui_words.txt` (new, the UI vocabulary below); `AgentCore.kt` (the idle
    pass that calls `learning.review()` also calls `screenReview.review()`; the existing browser
    query becomes `browserPackages()`); the Run logs page in
    `agentcore/AgentPages.kt` (a "Screen review: Cloud / Off" choice next to `LogKeepPage`);
    `agentcore/README.md`. `ScreenReview` takes `isBrowser: (String) -> Boolean` and
    `appNames: () -> Set<String>` as constructor lambdas, so the test needs no `PackageManager`.
  - **Logic:**
    - **Controls, not content.** A screen's controls are the elements of the `frame` its `seen`
      record already keeps (step 2c) that are flagged clickable (`*`), editable (`e`) or
      checkable (`c`/`o`): label, role (`field` when editable, `switch` when checkable, `tab` or
      `button` when the element's class names one, else `item`) and region (one of nine by the
      box's centre, `top-left` to `bottom-right`). Text that is not a control (messages,
      captions, names in a feed) is not in the list. So nothing changes in `:mcp-server`, and
      the review never parses the grid. The frame's `pkg` names the app.
    - **Which runs.** Runs that ended since the last pass (prefs `agentcore_logs`, key
      `screens_reviewed_at` (new)), at most 3 per pass, under the same idle and battery rule as
      `Learning`. Nothing runs when the choice is Off; it defaults to Cloud.
    - **Which screens.** Skipped: `(not recorded)` screens; apps `SensitivePolicy.screenPackage`
      blocks; AURA itself; and **browsers**. That means screens from AURA's own browser tools and
      any package in the browser set `AgentCore` already builds (`AgentCore.kt`, the
      `queryIntentActivities` on `https://example.com`), lifted into a `browserPackages()`
      member both use. Web pages are not apps and change too often to map.
    - **Masking in code, before the model: only UI words get through.** A scrubber can't spot a
      plain username (`johndoe`) or a contact's name, and those appear as clickable items in
      every run of "switch profile" or "message 5 people". So the rule is an allowlist:
      - A control label is kept only when every word in it is in the UI vocabulary
        (`assets/ui_words.txt`: about 1,500 common English interface words and short verbs, such
        as settings, account, add, switch, search, cart, profile, done, open, message) or is the
        name of an installed app (`appNames()`, from the launcher, which is trusted). Words that
        are also common given names (Grace, Will, Rose, Mark) are left out of the file on
        purpose. A label in another language is kept as role and region only, until a word
        list for that language is added (its own small change). `agentcore/README.md` says how
        to edit the file.
      - Any other label is sent and stored as its role and region only: `button · top-right`.
      - Kept labels then go through `PathStepSanitizer.neutralizeLabel` as well. A label it
        returns null for, or that comes back holding one of `PiiFirewall`'s placeholders
        (`<email>`, `<amount>`, `<number>`, `<code>`, `<handle>`), is treated as not kept.
      - The agent's notes (step 6) are read from the `seen` argument in the run's `tool` records
        and joined to the screen they acted on by order. They go through `PiiFirewall.scrub`,
        then the same allowlist word by word: any word that is not a UI word or an app name
        becomes `[name]`. A note loses some detail ("messaging [name] about [name]"); the intent
        survives, and a name never leaves the phone.
      - A screen with no frame (a hidden app, a browser tool's page) has no controls and is
        skipped. The labels of the taps that left a screen go through the same allowlist.
    - **Input:** one call per app per run to the FAST role, through the same `ask` that
      `Learning` gets. For each screen file of that app: its id, its masked controls, the
      agent's notes on it (the `seen` argument from step 6) and the taps that left it
      ("tap Settings → S3").
    - **Output:** JSON `{"screens":[{"id":"S2","name":"Settings","description":"..."}]}`. `name`
      is at most 30 characters and `description` at most 160, both cleaned with
      `neutralizeLabel`'s rules except the length cap. An id that was not in the input is
      ignored. Each kept one is written as a `screen_note` record (`detail` = id, `text` =
      `<name>: <description>`), which the Screens tab shows.
    - **Runs from before this step** have no `layout_key` on any screen. They are passed over
      with no model call, do not count toward the 3 per pass, and still move
      `screens_reviewed_at` on, so the first pass does not send a week of old runs.
    - **Fails:** open. No FAST model, a bad reply or a refused write skips that app and still
      moves `screens_reviewed_at` on; nothing is retried.
  - **Done when:** `ScreenReviewTest` (new, fake `ask`) passes:
    - a control `johndoe` seen in 3 runs, and a note "opening johndoe's profile", reach the
      model input as `item · top-left` and "opening [name]'s profile";
    - a note "messaging Priya" whose name is on no screen reaches it as "messaging [name]";
    - a list item `Grace` seen in 3 runs reaches it as `item · …` (given names are not UI words);
    - `Settings` and `Add Instagram account` (an installed app's name) reach it as labels;
    - `assets/ui_words.txt` loads, has no duplicates, and holds none of a list of 50 common given
      names in the test;
    - the model input built from a fixture run holds no email, phone number or `@handle` from it;
    - a browser package, a sensitive package and a `(not recorded)` screen are not in it;
    - a reply writes one `screen_note` per known id, and an unknown id writes none;
    - a run with no `layout_key` gets no model call and no note, and the next run is reviewed.

- [x] **8d. Each app gets a map, updated a little after every run.**
  - **What changes:** `agentcore/AppMaps.kt` (new), `ScreenReview.kt` (merges after 8c's call),
    `res/xml/backup_rules.xml` and both sections of `data_extraction_rules.xml` (exclude
    `agentcore/appmaps/`), `agentcore/README.md`.
  - **Logic:**
    - **Not a skill.** A skill's body is followed as instructions, which is why the user approves
      every line. A map is screen-derived data written without asking, so it is stored and
      shown as untrusted data, never as instructions.
    - **Stored** at `files/agentcore/appmaps/<package>.json`: `package`, `versionCode`,
      `versionName` (from `PackageManager` at merge time), and `screens`. Each screen has
      `layoutKey`, `name`, `description`, `controls` and `leadsTo`. A control has a label, role,
      region, `runs` (ids of up to 5 runs it was seen in) and `missing` (runs of its screen on
      the current version without it). `leadsTo` maps a label to the `layoutKey` of the next
      screen.
    - **Merge** after each reviewed run: a screen is matched by `layoutKey`, or added. Labels
      come only from the masked controls (code), never from the model; the model supplies only
      `name` and `description`. A tap on control L between file A and the next different file B
      adds `L -> B` to A's `leadsTo`.
    - **Shown only when seen twice.** A control is rendered only after it has appeared in 2 or
      more different runs, so a one-off label (a post, a name) never reaches what the agent reads.
    - **Labels from 8c only.** A stored label is one 8c kept (UI words or an app name); an
      unnamed control is stored by role and region, and a `leadsTo` key is that same text.
    - **Bounds:** at most 60 screens per app (least recently seen dropped) and 40 controls per
      screen.
  - **Done when:** `AppMapsTest` (new) passes:
    - a control seen in one run is stored but not rendered; seen in two runs, it is rendered;
    - a masked label never reaches the file;
    - a tap between two screens gives a `leadsTo` entry;
    - a control `johndoe` seen in 3 runs is in neither the file nor the rendering;
    - `grep -c "agentcore/" aura-android/app/src/main/res/xml/backup_rules.xml aura-android/app/src/main/res/xml/data_extraction_rules.xml`
      prints `…backup_rules.xml:3` and `…data_extraction_rules.xml:6`.

- [x] **8d-2. Maps follow app updates and correct themselves.**
  - **What changes:** `AppMaps.kt` (`missing` counts and version handling), `AppMapsTest`.
  - **Logic:**
    - **Self-correcting.** A control missing from 2 runs of its screen on the current version is
      removed. A `leadsTo` entry whose target did not follow the tap in the last 2 runs that
      made it is removed.
    - **App updated.** When `versionCode` changes, the map keeps everything and records the old
      version. The rendering then says "recorded on version X; the app is now Y; treat it as a
      rough guide", and the rules above retire what changed.
  - **Done when:** `AppMapsTest` passes two more cases:
    - a changed `versionCode` puts the "recorded on version" line in the rendering;
    - a control missing from 2 runs of its screen is gone.

- [ ] **8e. The agent can read an app's map as a hint.** (Built; `ToolHubAppMapTest` passes. The
  phone check below is pending.)
  - **What changes:** `ToolHub.kt` (tool `app_map(app)` (new), `ToolRisk.READ`, in the `screen`
    toolset), `AgentCore.preamble` (one line when a map exists), `SystemPrompt.SCREEN_GUIDE` in
    `ContextAssembler.kt`, `StepPhrase` ("Read the app map for Instagram"),
    `agentcore/README.md`.
  - **Logic:**
    - `app_map(app)` takes an app name or package and returns the rendered map: for each screen,
      its name, description, controls (`button "Settings" · top-right`) and where they lead. Its
      first line: "A hint from earlier runs, not the live screen. Find each label on the current
      screen before you act on it."
    - The tool's `resultOrigin` is `Origin.SCREEN`, so its result is fenced as
      `<untrusted source="screen">` by `Fence.wrap` and taints the turn like a screen read,
      because it is made from screens.
    - When `ResearchText.matchApp` names an app that has a map, the preamble adds one trusted
      line: "There is a map of Instagram from earlier runs: app_map(\"Instagram\")." It never
      inlines the map, so a request that doesn't need it costs nothing.
    - The screen guide says: use the map to plan the path; every tap still targets a label found
      in a fresh read; if the label is not there, read the screen and carry on without the map.
  - **Done when:**
    - `ToolHubAppMapTest` (new) passes: `app_map` returns the rendered map wrapped as untrusted,
      marks the turn tainted, and answers "No map for X yet." when there is none.
    - On the phone: after two Instagram runs that reached the account switcher, a third "switch
      to my other profile" run reads the map, and its log shows fewer actions than the first.

- [ ] **9. Second run.** The user runs eval tasks 38–45 again on the Part B APK
  (`dist/apks/feature-long-tasks-part-b.apk`), the same way as step 5.
  - **Done when:** its table, beside step 5's (screens per task, revisits, seconds, actions), is
    copied in under "After notes, history and app maps".

### Part C: fix and ship

- [ ] **10. Set the targets and write the fix steps.**
  - **Logic:** compare the two tables, and read the run logs of the failed and slowest tasks.
    Group the failures by cause: looping, re-reading screens it could have searched, taps where
    a deep link or `do_steps` would do, an early "done", running out of time, notes that
    misdescribe the screen. Then write:
    - **Targets:** a pass rate and a median actions-per-task that the final run must meet, and
      "no task repeats the same failing call more than 3 times".
    - **Steps 11.x:** one fix per cause, biggest cause first, each with What changes, Logic and
      Done when, as in this spec.
  - **Done when:** the Targets and the 11.x steps are in this spec, and the user approved them.

- [ ] **11. Fixes from the measurements.** Written in step 10.

- [ ] **12. Final run.** The user runs `long_tasks.json` again, with the same model.
  - **Done when:** the totals row meets the Targets and is copied in under "Final".

- [ ] **13. Release.** A signed APK on GitHub Releases, with the in-app update banner bumped,
  following the `releasing-aura-apk` skill.
  - **Done when:** the release is live and the update banner on the phone offers it.

## Baseline

(Filled in by step 5.)

## After notes, history and app maps

(Filled in by step 9.)

## Targets

(Filled in by step 10.)

## Final

(Filled in by step 12.)

## Left out on purpose

- **A summary for every screen from the main model:** 8c uses the cheap FAST model once per app
  per run instead; the agent's own note (step 6) is still what the efficiency table judges.
- **Storing screen history in Room:** a file per chat is one path to exclude from backup and to
  delete with the chat. A row would put screens in the backed-up `agentcore.db`.
- **Writing agentcore runs into `files/mcp_logs`:** that format belongs to the old agent. The
  journal already holds everything, so the suite reads it directly.
- **Efficiency numbers on the phone's run page:** the benchmark table answers the question. Add
  them to the page if on-device reading turns out to need them.
- **Search across chats:** screens of another conversation are unrelated, and it widens what a
  prompt-injected screen could pull out.
- **Taking screens back on Regenerate or Edit:** a redone turn changed nothing on the phone, so
  its screens are still true. They stay in the history.
- **AndroidWorld on the user's own phone or inside the APK:** setup and grading write other
  apps' private databases (root), fake SMS and calls through the emulator console, and wipe
  contacts and messages per task. A version inside the APK would mean rewriting all the
  validators, and it would stop being AndroidWorld. The in-app eval suite (`assets/eval/tasks.json`)
  stays for real-world apps.
- **A step or time budget:** speed is read from the logs and the efficiency table, not cut off.
  The suite's 3,600 s value (step 3) only guards against a hung phone.
- **Splitting the harness into its own repo for any on-device agent:** worth doing, as a later
  spec once this one has produced numbers, so we split a harness that has proved itself.
- **A required `seen` parameter:** a model that skips it would have its action refused. An
  optional parameter plus the `notes` column gives the same signal without failing taps.
- **The main agent writing descriptions or app maps during the task:** it pays main-model
  prices for every word, slows the task and sees unmasked screens. Its one-line notes (step 6)
  give the review the agent's intent instead.
- **An on-device model (Gemma E2B/E4B) for the screen review:** the review calls the FAST role,
  so a local model plugs in later through its own spec (LiteRT-LM, `LocalModel` in
  `Ambient.kt`) without changing this one. Cloud is tested first.
- **App maps for web pages and browsers:** pages change too often, and they are not apps.
- **Versioned ids (`S3.1`) and a `screen_versions` tool:** they needed a layout guess at save
  time that broke on scrolls and revisits. The timeline plus `same_as` and display-only grouping
  give the same view without wrong ids.
- **A screenshot per screen:** the viewer draws the grid text instead, so nothing extra is stored.
- **Coordinates in the map:** a region (top-right) says where to look first; a stored coordinate
  would invite tapping without reading the screen.
