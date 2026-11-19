# Spec: a reliable agent

> **Status:** planned. **Written:** 2026-11-16. **Branch:** `feat/agentcore`.
>
> Tick an item here in the commit that finishes it.

What has to happen before the new agent (`agentcore/`) is ready for real users. The aim is that it
works reliably, not that it is perfect: each item is small, tested and committed on its own.

The design this builds on is `docs/agent-review/agent-architecture.md`; section numbers below
(§6.5) point into it.

## Already done

- [x] **A browser window left after a run no longer locks the phone** (`1306e46`). The full-size
  card lets touches outside it through (`BrowserChromeBehavior.windowFlags`), Back works on it,
  and `AppBrowserBridge.tidyAfterRun` shrinks it when a turn ends.

## 1. Screen history in the run log

> Moved to `feature-long-tasks.md` (steps 2 and 7), which also stores the agent's note per screen.

The design (§6.5) says every screen a task saw is stored with the run and drawn for people.
Today `ScreenHistory` keeps it in memory for one turn only, and the automatic screen read after
each action (`ToolHub.observeScreen`) never reaches the journal. So the run log cannot show what
the agent was looking at when it tapped.

### 1.1 Store each screen with its run

- Where a screen gets its id (`turn.screens.record` in `ToolHub.kt`), also write one journal
  record: type `screen`, `detail` = the id (`S3`, `S3.1`), `text` = the `SEEN:` caption,
  `result` = the full screen text.
- It goes through the journal's existing redaction (`SecretPatterns`), retention (`LogKeep`,
  7 days by default), delete and export. Nothing new to build for those.
- **Sensitive apps:** when `SensitivePolicy.screenPackage` blocks the app in front (the rule
  `ToolSources.shotFor` already uses for tap screenshots), write only
  `S3 · <app> (not recorded)`.
- **Done when:** a test feeds two screens and a diff through the hub and reads three `screen`
  records back with ids `S1`, `S2`, `S2.1`; a blocked app's record has no screen text.

### 1.2 Show it on the run's page

- `RunLogBuilder` turns each screen record into a `LogStep` of kind `SCREEN`, titled
  `S3 · WhatsApp — Chats, Search, Mom`, with the full text folded under Details.
- A diff row shows its `+` lines green and its `-` lines red.
- Rows sit in time order with the taps, so a tap's screenshot (`shot-*.jpg`, already drawn)
  appears right after the screen it acted on.
- No separate screens tab and no gallery across tasks: screens only make sense next to the taps
  that caused them, and a cross-task gallery would only collect private screens.
- **Done when:** `RunLogTest` builds a run with screen records and gets the rows in order, with
  the right titles.

### 1.3 Keep the history across one task's turns

`ScreenHistory` lives in `TurnState`, so it starts again at `S1` when the chat hands the task to
the overlay, when `/goal` starts its next turn, and when a run is continued after a crash.

- Save the history with the thread's context (`ThreadStore` context save) and load it with it.
- **Done when:** a hand-off test sees the overlay's turn continue at `S4`, not `S1`.

### Left out on purpose

- The model-written `seen` caption (§6.5): the server's `SEEN:` line works and costs nothing.
- `~` (changed) markers: `+` and `-` are enough to read.
- The column-per-screen strip from §6.5: the timeline gives the same information with far
  less UI.
- Using screen commits for skill learning: skills learn from the tap trace (`Steps.kt`), which
  works.

## 2. Run log privacy

> Moved to `feature-long-tasks.md` step 1.

- [ ] **Keep the run log out of the user's cloud backup.** `files/agentcore/traces/` holds
  screenshots and (after 1.1) screen text, and it is not excluded. Add
  `<exclude domain="file" path="agentcore/traces/" />` to `res/xml/backup_rules.xml` and to both
  sections of `res/xml/data_extraction_rules.xml`.
  - **Done when:** the three excludes are in place. Do this before 1.1 ships.

## 3. Correct times in the run log

The base numbers are right: a run lasts from its start record to the end of its last step, and
each tool's own time comes from `System.nanoTime()`. Three things mislead:

- [ ] **3.1 Show the time spent waiting for the user.** A send step's time includes the user
  reading the approval card (`HubTool.run` times the whole `gated` call), and a paused run
  includes the pause. Show `1m 40s · 39s waiting for you` on the run and on the step.
  - The journal already has what is needed: each card is an `ask` record and its answer a
    `note`, and pauses write `Waited N s while you held the phone`.
  - **Done when:** `RunLogTest` builds a run with a 30-second card and shows the wait apart.
- [ ] **3.2 Put model calls in the right run.** `RunLogBuilder.build` puts a model call in a
  run when its time falls inside the run's window. A `/btw` answer, a chat title or a background
  heartbeat that overlaps a task lands inside the task, and its tokens count toward the task.
  - Match calls by a run id on the span instead of by time. **Check first** which id Koog's
    OpenTelemetry feature puts on its spans; if none fits, set one as a span attribute when the
    turn starts.
  - **Done when:** a test with two overlapping runs gives each only its own calls.
- [ ] **3.3 Link a hand-off to the run that continues it.** A screen task that the chat hands to
  the overlay shows as two runs. Add "continued in the next run" on the first and the total
  time on the second.

## 4. Reliability of the agent itself

- [ ] **4.1 Measure it.** Moved to `feature-long-tasks.md` steps 3–5. No benchmark numbers exist for the new agent: the only on-device
  result (`benchmark/results/aura-on-device/ClockStopWatchRunning`, October) was the old agent.
  - agentcore does not write `files/mcp_logs`, so the harness's step reports come out empty.
    Write the run's steps there in the shape the harness reads (see `benchmark/README.md`).
  - Then the user runs a 20–30 task AndroidWorld subset (`benchmark/suite/suite.py`; the user
    starts it, never an agent). Whatever fails in it decides what to fix next.
  - **Done when:** one suite run produces step reports and a pass rate for agentcore.
- [ ] **4.2 Continue after the app is killed.** Steps are checkpointed after each one, but the
  overlay's Continue chip only offers the old agent's runs. Have it read agentcore's "was still
  running" mark (`markRunning` in `AgentEngine`).
  - **Done when:** kill the app mid-task on the phone; reopening offers Continue and the task
    goes on from where it was.
- [ ] **4.3 Voice during a task.** "How's it going" and steering by voice read
  `ActiveRunRegistry`, which only the old agent fills. Register agentcore's runs there too.
  - **Done when:** asking by voice mid-task gets the current step back.
- [ ] **4.4 Old agent tidies the browser too.** Call `AppBrowserBridge.tidyAfterRun` where the
  old agent (`agent/AuraAgent.kt`) ends a run, as agentcore does. Only while the old agent is
  still the fallback.
- [ ] **4.5 Crash reports from release builds.** Crashlytics is wired in
  (`app/build.gradle.kts`). Check that a deliberate test crash in a signed release build reaches
  the console; it is the only way to hear about failures on users' phones.

## 5. Open items carried from the design doc (§22)

Not part of the order below; listed so they are not lost.

- Security review of `watch_for`, `/btw` and the goal judge (a model-written condition runs as a
  background request for up to 24 hours).
- A phone smoke test of approvals, memory and connectors (phases 4 to 6).
- "Email Ravi the report" against the live Composio server: one connector call, one approval.
- Fable prompt work left: approval card text, the denial message, the voice read-out.

## Order

1. Run log out of cloud backup (2).
2. Store screens (1.1), then show them (1.2).
3. Times: waiting for you (3.1), model calls by run (3.2).
4. Measure: `mcp_logs` adapter, then the user's suite run (4.1).
5. Fix what the suite run shows, then 4.2 and 4.3.
6. The rest (1.3, 3.3, 4.4, 4.5) when the logs show they matter.

## Not doing now

A local model, a separate setting for the light model, real background blur on the glass, the
remote trace export screen, and deleting the old agent. The old agent is the fallback while the
"Use for voice and the overlay" switch exists; delete it once the suite run looks good.
