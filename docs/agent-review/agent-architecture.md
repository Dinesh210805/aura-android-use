# AURA agent architecture: the loop, the screen, tools, memory and learning

> **Status:** approved and being built on branch `feat/agentcore`. Section 22 tracks what is done and what is left.
> **Date:** 2026-11-14.
> **Builds on:** [`hermes-port-plan.md`](hermes-port-plan.md), which has the full Hermes and Koog feature tables
> (port or not, and why). This document is the system design: how the parts fit together and
> what changes in each.

Each claim about today's code is marked **[code]**, meaning read in the source with its location
given, or **[hypothesis]**, meaning plausible but not yet shown by a run log. No device was
connected while this was written, so none of it comes from a failing trace.

---

## 1. What we are building and why

AURA's agent today finishes short tasks and loses its way on long ones. It is slow because every
step sends a screenshot, and it asks for approval in confusing ways. The goal is an agent that:

- **finishes long tasks:** dozens of apps and hundreds of steps, across pauses, hand-offs and the
  app being killed;
- **uses few model calls and few tokens per step:** text first, pictures only when asked;
- **knows when it is done**, and says so only with evidence;
- **learns:** facts about the user and procedures for this phone, safely;
- **reaches the user's accounts:** Google Workspace and other services, through connectors as
  well as the screen;
- **stays safe:** one clear approval per committed action, and nothing on a screen can argue its
  way past the gate.

**Non-goals:** sub-agents that act at the same time (one screen means one actor), a second agent
framework, and third-party plugin code inside the app.

**Anti-goals** (these count as failure even if the benchmark score goes up):
- an agent that says "done" when it is not;
- a send or payment without the user's approval;
- memory that quietly saves what a web page told it to.

**How we know it worked** (section 16): AndroidWorld success up, false "done" down, model calls
per task down, tokens per task down, wall time per task down. All of them are measured against
a baseline taken before the first change.

---

## 2. Approaches

| | Approach | For | Against |
|---|---|---|---|
| **A** | **Keep the Koog `functionalStrategy` loop and its guardrails** (`ToolHub`, `SafetyGate`, `ThreadStore`, `RunJournal`); rebuild what sits around the loop: context, observation, the ledger, compaction, the tool manifest, approvals and memory | The weak parts are what the model is shown and when, not the loop itself. The gate, taint tracking, journal and threads work and are tested (46 agentcore tests). | `AgentEngine` must be split up as it grows (section 10) |
| B | Rewrite `AgentEngine` from scratch | Clean slate | Throws away a working gate and taint tracking; months before it matches what exists |
| C | Port the old agent's `AuraVisionStrategy` | It has the ledger, the completion gate and turn-defect repair | It was built around one screenshot per step and a graph of prompts; it is the agent we are replacing |

**Recommended: A.** Bring the old agent's best parts across into agentcore, namely the run ledger, `TurnDefect`
repair, the completion gate and learned paths, and leave its strategy behind.

---

## 3. The system at a glance

```
             chat · overlay · voice · scheduler · notifications · MCP (desktop)
                                     │
                                     ▼
 ┌──────────────────────────── AgentCore (composition root) ───────────────────────────┐
 │                                                                                      │
 │  RunController ── one run per thread; checkpoints every step; resume after kill      │
 │      │                                                                               │
 │      ▼                                                                               │
 │  AgentEngine (Koog functionalStrategy loop)                                          │
 │      │  before each model call: ContextBuilder                                       │
 │      │      system prompt (frozen) · run ledger · memory snapshot · device block     │
 │      │      newest screen (full grid or diff) · tool schemas in load order           │
 │      │  ModelRouter: main model · aux model · fallback · retries · thinking level    │
 │      │                                                                               │
 │      ▼  model reply: tool calls ─────────────────────────────┐                       │
 │  Hook chain (ordered, internal)                              │                       │
 │      pre-model · pre-tool · post-tool · post-step · post-run │                       │
 │      │                                                       ▼                       │
 │      │                        ToolHub ── SafetyGate (always first, no hook bypasses) │
 │      │                           │       approvals: propose_action, scoped grants    │
 │      │                           ▼                                                   │
 │      │                ToolManifest (one spec per tool)                               │
 │      │                ├─ phone   (:mcp-server in process)   screen · phone · browser │
 │      │                ├─ native  (memory, skills, plan, ask_user, schedule, search)  │
 │      │                └─ connectors (remote MCP: Composio/Google, GitHub, …)         │
 │      │                                                                               │
 │      ▼  results (an action result carries the new screen)                            │
 │  RunLedger ── goal · plan · done steps · facts · dead ends · evidence                │
 │  Compactor ── summarise the middle, keep the tail verbatim, ledger survives          │
 │                                                                                      │
 │  After the run, when idle: Reviewer (aux model) ──► staged memory · skill drafts     │
 │  Memory: core block · thread summaries · FTS · (later) semantic recall · skills      │
 │  ThreadStore (Room) · RunJournal + OpenTelemetry · Eval exporter                     │
 └──────────────────────────────────────────────────────────────────────────────────────┘
```

New names in this diagram (`RunController`, `ContextBuilder`, `ModelRouter`, `ToolManifest`,
`RunLedger`, `Compactor`, `Reviewer`) are roles. Each becomes one file in `agentcore/` only
where today's files cannot hold it (section 10).

---

## 4. One run, step by step

1. **Accept.** The `refusal()` check runs. A run is created on the thread and checkpointed
   (section 5.4). The voice, overlay and chat entries all reach this same call; the chat does not
   hand off by re-sending the request (5.5).
2. **Route before thinking.** The preamble resolves the named app's deep links and system
   intents. A one-call task (alarm, timer, call, play) runs at the lowest thinking level (11.3).
3. **Build the context** (section 6): the frozen system prompt; the ledger; the memory snapshot
   (first message of a thread); the device block; the newest screen; the loaded tool schemas.
4. **Call the model** through `ModelRouter`, with retries, the server's retry-after delay and a
   fallback model (11.2).
5. **Repair a bad turn.** Tool-call syntax written as text, or thinking with no call, is re-asked
   once (`TurnDefect` from the old agent). Today agentcore only strips the debris from the
   answer and the task ends **[code: `tidy` in `AgentEngine.kt:331`, `ToolCallDebris`]**.
6. **Run the tools.** Each call goes pre-tool hooks → `SafetyGate` → approval if needed → source
   → post-tool hooks. Read-only, screen-free calls in one reply run in parallel (7.2).
7. **Observe once.** A screen-changing action returns its own result **plus** the settled screen
   (section 6.3). No separate `read_screen` call follows it.
8. **Update the ledger** from the step: what ran, what changed, any fact the model noted.
9. **Check limits and pause.** Steps, wall clock and request cap are checked. While the user
   holds the phone the run waits, as today.
10. **Compact if over budget** (5.3). Loop to 3.
11. **Finish.** When the model claims "done", the completion check runs (8.4). The answer is
    saved, the run is closed, and the idle-time review is queued (section 13).

---

## 5. Long tasks

What caps a long task today **[code]**:

| Limit | Where | Effect |
|---|---|---|
| Compaction drops history | `compactIfOver` uses Koog `FromLastNMessagesHistoryCompressionStrategy` (`AgentEngine.kt:410`) | Everything older than the last N messages is deleted **unsummarised**; then the last N are summarised, so the newest screen and tool results become prose |
| 80 steps, 20 minutes | `TurnLimits` (`AgentModels.kt:114`) | A long task stops mid-way |
| Hand-off repeats the request | `TurnEnd.HANDED_OFF`, `AgentCore.handOff` | The overlay starts from scratch |
| No checkpoint per step | `save()` runs only at turn end or cancel | A killed app loses the run |

### 5.1 The run ledger (from the old agent's `RunLedger`)

The ledger is a small structured record that lives outside the chat history and is rendered fresh
into every model call. Because it holds the plot of the run, history can be compacted hard
without the run losing its thread.

```
<run>
goal: Book a cab to the airport for 6am tomorrow, cheapest of Uber and Ola
plan: [x] check Uber price  [x] check Ola price  [ ] book cheaper  [ ] confirm time
done: opened Uber → fare ₹612 (6:00) · opened Ola → fare ₹548 (6:00)
facts: pickup = Home (saved) · Ola cheaper by ₹64
dead ends: Uber "Schedule" hidden behind "Now ▾" menu
evidence: Ola fare screen 05:58 "₹548"
step 23 of 300 · 6 min
</run>
```

- **Writes:** `update_plan` (exists) becomes the plan part. The engine fills `done`
  automatically, one line per screen-changing step, and the model adds `facts` and `dead_ends`
  through `note(kind, text)`.
- **Size:** bounded (about 1,500 tokens). The oldest `done` lines fold into a count when it fills.
- **Placement:** in the newest user message, never the system prompt, so the cached prefix holds.
- **Stored:** with the checkpoint, so a resumed run gets it back.

### 5.2 Limits that fit long tasks

- **No step limit for foreground runs** (your call). A run ends when it is done, when the user
  stops it, when it is stuck (below), or at a **time limit**: 60 minutes by default, set in
  Settings → Brain. Near the limit the overlay offers "Keep going" (+30 minutes) instead of
  ending silently. Koog's own `maxAgentIterations` is set out of reach. (Hermes does have a
  configurable iteration budget; we drop it because stuck detection and the user's Stop do its
  job better on a phone.) Background runs keep their small step limit: nobody is watching them.
- **No limit on tokens summed across a run.** The old agent's `RunBudget` found that summed
  prompt tokens grow quadratically with turns and ended 8 of 25 bench tasks. Tokens are
  measured, never a stop condition.
- **Progress, not size, decides when to stop:** the same screen signature and the same action
  seen 3 times, or no plan item checked off and no new fact in 25 steps, triggers a "stuck" step.
  `done` lines do not count, because the engine adds one after every action. The model is told
  what repeated and must change approach or ask the user.

### 5.3 Compaction that keeps what matters (A2 to A5 in the port plan)

1. **Free first:** clear old tool outputs and old screens (exists, `pruneOldToolResults`).
2. **Then summarise the middle:** messages `[after the first user message … cut)` become one
   summary from the **aux** model; everything from `cut` on stays verbatim. The cut moves back so
   a tool call is never separated from its result.
3. **The summary must keep verbatim:** names, numbers, app names, recipients, sent text and
   the user's last instruction (a Fable prompt task).
4. **Before it summarises,** the idle-time memory review runs on the part being dropped (13.1).
5. **Real token counts:** the provider's prompt tokens from the last reply plus an estimate for
   what was appended since, not `estimateTokens` over everything **[code: `ContextAssembler.estimateTokens`, 4 chars/token, 1,000 per image]**.
6. **Test first:** a long thread compacted once must still answer a question about its first
   half, and its last tool result must survive verbatim.

### 5.4 Checkpoint and resume (A7)

After every step: messages, ledger, loaded toolsets, taint and the step counter are written to the
thread with `running = true`. On restart, a thread left running offers **Continue: <goal>** in the
overlay and in the chat. Koog's `AgentPersistence` does not fit: it checkpoints between graph
nodes and this loop has none. About 40 lines in `ThreadStore`.

### 5.5 Hand-off without re-running

The chat turn decides **before its first model call** whether the task needs the screen. It uses
the preamble's app match plus a cheap rule, not a model. If it does, the run starts in the overlay
directly, on the same thread. If the chat turn discovers mid-way that it needs the screen, it
**checkpoints** and the overlay **continues that run** (5.4) instead of starting a new one.

### 5.6 Goal mode (A8)

For "go through my last 20 emails and…", after each finished turn the aux model judges
`done | continue | blocked` against the original request (typed output with Koog structured
output). `continue` runs another turn within a goal budget. The user sees "Working on: <goal> ·
turn 3".

---

## 6. The screen: what the model sees

### 6.1 Today **[code]**

- `ReadScreenImageStore` defaults to **on** (`ReadScreenImageStore.kt`, `MutableStateFlow(true)`
  and `getBoolean(…, true)`). So every `read_screen` also captures a screenshot, draws boxes on it
  and returns the image.
- After every screen-changing step, agentcore calls `read_screen` itself
  (`ToolHub.observeScreen`, `AgentEngine.kt:262`). **So every action step pays for a screenshot
  capture, an annotation pass and an image in the prompt.** This is the most likely single cause
  of slow steps and high token use **[hypothesis until E2 measures it]**.
- The phone server also adds its own text summary of the screen after each write action
  (`PostActionObservation`, wired in `scopedTool`). It does **not** refresh the som_id cache. That
  is two observations of the same change.
- `perceive_screen` is vision only: OmniParser (YOLOv8 plus ML Kit OCR) boxes on a fresh
  screenshot, numbered from 1001 (`PerceiveScreenTool.kt` header).

### 6.2 Target: text first, pictures on request

| Tool | Returns | When |
|---|---|---|
| `read_screen()` | Character grid plus actionable table from the accessibility tree. **No image.** | Default look |
| `read_screen(image = true)` | A fresh settled read: the same grid **plus** the annotated screenshot with the same som_ids | The model asks for it: colour, picture, icon, layout doubt |
| `read_screen(changes_only)` / `(query)` | As today | Cheap follow-ups |
| `perceive_screen()` | **Vision only:** OmniParser boxes and OCR text on a fresh screenshot, numbered on the image. No tree. | Surfaces the tree cannot describe: games, maps, canvas, video, custom views, or when a tap on a tree element did nothing |

- **The Settings → Tools switch** ("Always send the screenshot") becomes **off by default**. When
  on, every `read_screen` and every post-action screen carries the image. The setting is read
  live, as today.
- **This also changes the MCP target** (Claude Code driving the phone) and the old agent, which
  read the same store. The benchmark's MCP numbers must be re-baselined after the change.
- **`perceive_screen` som_ids are CV boxes, not accessibility nodes.** A tap on one is a
  coordinate gesture at the box centre. Rules:
  - They are numbered from 1001 (`PerceptionCache.VISION_ID_BASE`; the tap schema is numeric),
    so a tree id and a CV id can never be confused.
  - A vision look never replaces the tree look that `read_screen changes_only` diffs against, and
    a vision id is never rescued by the tree (its OCR label could match a different tree node).
  - The next `read_screen` invalidates them.
  - The ONNX execution provider stays picked per device by `OnnxYoloRunner` (AGENTS.md rule).
- **Stale descriptions to rewrite:** the `read_screen` description ("Use perceive_screen instead
  when the answer depends on how something looks"), the server `Doctrine` and `SCREEN_GUIDE` all
  point visual questions at `perceive_screen`. They must point at `read_screen(image = true)`
  instead (a Fable task).

### 6.3 One observation per action, in one place

Every screen-changing tool returns:

```
tap 14 → ok · "Send" pressed
screen: com.whatsapp · IDLE · changed
<grid or diff>
```

- **Where:** in the **phone server**, inside `scopedTool`. The existing `PostActionObservation`
  is upgraded from a text summary to a real settled `read_screen` render that **does** refresh
  `PerceptionCache`, so som_ids from it are tappable. Then `ToolHub.observeScreen` and the
  engine's own observe step are deleted.
- **Why server-side:** one place, and MCP clients on the desktop get the same saving. The cost is
  that the server, not the engine, decides what "changed" means. That is fine, because the server
  already owns `ScreenSettle` and `LastSeenScreen`.
- **Full grid or diff.** The decision uses the two hashes `ScreenSignature` already computes in
  about 1 ms per read **[code]**: `layout` (structure only) and `content` (structure plus text and
  checked state). No model is involved: the tree gives exact text, and an embedding answers
  "similar", which is the wrong question (₹548 → ₹612 is tiny in meaning and is the whole point).

  | What happened | Signal | The model gets |
  |---|---|---|
  | Nothing | `content` unchanged | `screen unchanged`, one line. A tap that did nothing is itself the news. |
  | Same screen, values changed (field filled, toggle, counter, list loaded) | `layout` unchanged, `content` changed | Diff: changed rows, plus every actionable som_id by name |
  | Overlay appeared (dialog, menu, keyboard) | `layout` changed, same package, few rows changed | Diff |
  | Scrolled | The action was a scroll | `Revealed`: rows newly visible (exists) |
  | New screen or app | Package or activity changed, or more than half the rows changed (`changesAgainst`, exists) | Full grid |
  | Tree-blind surface (game, map, video) | Tree too thin | Image hash (dHash) of the screenshot for "changed?"; OCR text diff for "what?" |
- **Eviction** keeps the **last full grid plus the diffs after it**. Older screens are stripped
  **from inside** their tool results, and each result keeps its outcome line. A tool result
  message is never deleted, because every tool call needs its paired result and providers reject
  a request without one. `evictStaleScreens` changes accordingly; today it keeps only the newest
  screen read, which would orphan a diff.
- **The server's staleness gate** (named in `PostActionObservation`'s header: som-targeted taps
  need a real look) must accept the post-action read as that look.
- **This changes the MCP target too:** every write result now carries a grid. Re-baseline the
  MCP benchmark together with the image switch change.
- **The image** is attached to the post-action screen only when the switch is on.
- **Silent intents** (`EXTRA_SKIP_UI` alarm, timer) keep today's exception: no observation.

### 6.4 Stable ids, and a screen change that means a new screen

**Today [code]:**
- `ScreenPayload.render` renumbers som_ids by element area on every look (`ScreenPayload.kt:85`),
  and the payload tells the model so. One new or resized element shifts many ids.
- `ScreenSignature.layout` mixes **every** node's bounds, quantized to 8 px
  (`ScreenSignature.kt:125-134`). A moving carousel card, a scrolled list or a new feed item
  changes it, so a **content** change reads as a **screen** change.
- `PerceptionCache.resolve` rescues a tap when the screen moved but the element's fingerprint
  still matches (added after an Instagram reel deadlock). That covers the symptom; this section
  fixes the cause.

**Target:**
1. **Stable ids.** An element's id is keyed to its identity: class, resource id, label or
   description, and its parent chain. Bounds are not part of it. A screen keeps an id map
   across reads: a known element keeps its number, a new one gets the next free number, and a
   gone one's number is retired, never reused, until the screen changes. The ids reset only on a
   `screen` change. (Playwright's aria-snapshot refs work the same way.) Duplicate identities, such
   as list rows with the same label, are told apart by their order within the parent.
2. **Four change classes**, from three hashes:
   - `frame`: package, window or activity, and the nodes **not** inside a scrollable, pager or
     carousel container, by class and resource id only (no bounds).
   - `content`: every node's identity, text (whole, not the first 24 characters) and checked state.
   - `windows`: the accessibility window list, for overlays.

   | Class | Rule |
   |---|---|
   | `unchanged` | `content` equal |
   | `content` | `frame` equal, `content` differs (values, items, positions inside lists, moving cards) |
   | `overlay` | A new window (dialog, menu, sheet, keyboard) over the same `frame` |
   | `screen` | `frame` differs |

   The post-action payload (6.3) and the run ledger use these classes. Only `screen` resets the
   ids and counts as a new page in the screen history (6.5).
3. **The "look before you write" guard, rebuilt on them.** The old agent's hook chain
   (`ToolHookChain`: perceive-before-act, loop detection) and the server's `PerceptionCache` gate
   stay, with a new rule:
   - after a `content` change, a write by id is allowed when **that element** is unchanged and
     not covered; it goes to the element's **current** bounds;
   - it is refused when the element itself changed, disappeared or is covered, or after a
     `screen` change;
   - **the refusal carries the fresh diff**, so the model needs no extra look.
4. **Tests first** (in `:mcp-server`, JVM, recorded trees): a carousel advancing, a feed adding
   an item, a counter ticking, a toggle flipping, a dialog opening, a scroll, and a page change.
   Each must give the expected class, and ids must stay the same across ten reads of an
   animating screen.

### 6.5 Screen history ("git for screens")

A per-run record of every screen the run saw. It is stored on the phone, queried by the model
and drawn for people.

- **Commits and diffs:** each `screen` change is a commit `S1, S2, …` (app, title, full grid).
  Each `content` or `overlay` change on it is a diff `S7.1, S7.2, …`.
- **Commit messages written by the model, at no extra cost.** Every screen action takes an
  optional `seen` argument: one line, at most about 15 words, describing the screen it is acting
  on ("Ola ride options: Mini ₹548, Prime ₹612, pickup Home"). The model has just read that
  screen anyway, so this costs about 20 output tokens and no extra call. It becomes the commit
  message of that screen. When the model leaves it out, the server's deterministic `SEEN:` line
  (app plus most prominent labels, already built) is used. It is screen-derived text, so it is
  stored and returned as untrusted. The same line feeds the status strip and the run log's story
  view.
- **Stored** as text (grid plus elements JSON, a few KB each) in the run's journal, next to the
  screenshots that already exist. It is redacted and kept 7 days like the rest of the run log,
  and included in the run export.
- **The model never gets the whole history in its prompt** (100 screens × ~700 tokens). The run
  ledger carries one line per commit (`S7 Ola · ride options · ₹548`). Tools fetch the rest:
  - `screens(query?)`: commits whose message or screen text matches (full-text search), one
    line each;
  - `screen(id)`: that screen's grid as it was;
  - `screen_diff(a, b)`: what changed between two screens or two diffs.

  These replace going back to a screen just to re-read it.
- **Diff markers for the model are text:** `+` added, `-` removed, `~` changed (old → new). A
  model cannot see colour in text, and columns side by side interleave two screens on every line,
  so the model reads screens one after another.
- **For people:** a screen-history strip on the run's log page. Columns are screens; each
  column's cells come from its tree (the UI-stream frame format), changed cells are highlighted
  in colour, and the screenshot sits alongside.
- **Feeds skill learning:** a successful run's commits are the path a skill's `steps:` block is
  built from (13.2).
- **Privacy:** the history holds screen content, so it taints the turn when read back, like any
  screen read.

### 6.6 Why this saves calls

Today a typical step is `tap` → engine `read_screen` (with image) → model. The target is `tap` →
model, with the screen already in the tap's result and no image. Two tool calls become one, with
no image tokens. Verifying an action needs no extra look, because the result shows the new state.

---

## 7. Fewer model calls

### 7.1 Direct routes before the screen (exists; tighten)

`core` holds system intents, deep links, contacts, files, media and web search, and the system
prompt's `<routes>` gives their order. The eval table (16.2) counts tasks that loaded `screen`
although a direct route existed. Missing deep links for the top 20 apps go into the catalog.

### 7.2 Parallel read-only calls (S7)

When every call in a reply is `READ`, does not touch the screen, and is marked `parallel` in its
manifest (section 8), the calls run concurrently and their results return in order. Examples:
`web_search`, `find_files`, `resolve_contact`, `read_notifications` and connector reads.
Screen actions stay sequential.

### 7.3 Grounded action chains (not blind macros)

`run_steps` is hidden today **[code: `HIDDEN` in `ToolSources.kt:210`]**, and blind chains did not
work in the old agent. Recent work points at a different shape:
- **Jev-Mobile:** a planner sets a local goal, a fast executor picks one candidate per step from
  a fresh accessibility tree, and control returns to the planner on BLOCKED. It cut model cost by
  73% and total time by 33%, for 5 points of success.
- **AppAgentX / EAM:** shortcuts learned from repeated successful paths. EAM reports 6× fewer
  tokens per step.

AURA's version, `do_steps(steps: [{action, target_label, expect?}], stop_if?)`:
- Each step **re-reads the screen** and finds its target **by label in the fresh tree**, never by
  an old som_id.
- It stops at the first step whose target is missing, whose screen is unexpected, or whose
  `expect` text is absent, and returns what ran plus the current screen. The model takes over
  from there.
- Every step goes through `SafetyGate`. A commit step inside a chain still needs its approval.
- **Where chains come from:** skills (section 13). A learned skill can carry a verified step
  sequence for this phone ("open Ola → Where to? → type → pick first → Ride options"). The model
  calls `do_steps` with it, and the deterministic runner does in one call what took eight.
- **Measured:** model calls per task and success on tasks with a matching skill. It ships only if
  success does not drop.
- **Later experiment: a local step-picker.** `laya-android` (a 322M-parameter text encoder
  fine-tune; model card self-reports 76.7 on AndroidControl-High, not independently checked)
  ranks accessibility-tree candidates for a goal. It could choose each `do_steps` target on the
  phone in tens of milliseconds, the role Jev-Mobile's executor plays. Try it only after phase 5,
  and only if `do_steps` stops early too often on label matching.

### 7.4 Batch input

`type_text(som_id, text, submit = true)` types and presses Enter in one call. `set_value` covers
pickers. Forms are filled field by field in one `do_steps`.

---

## 8. Tools: one manifest, everything in sync

### 8.1 Today **[code]**

One tool's behaviour is spread across eight lists:
- `CORE_TOOLS`, `SCREEN_TOOLS`, `EGRESS_TOOLS`, `COMMIT_TOOLS`, `TRUSTED_RESULTS` and `HIDDEN`
  (`ToolSources.kt`);
- `McpToolScopes` (risk);
- `OBSERVED_TOOLS` (`PostActionObservation`);
- `SCREEN_CHANGING` (`ContextAssembler.kt:192`).

Adding a tool means editing up to five of them, and missing one is silent.

### 8.2 Target: `ToolSpec` manifest

```kotlin
ToolSpec(
    name = "tap",
    toolset = SCREEN,               // core | screen | phone | browser | <connector id>
    risk = ACT,                     // READ | ACT | COMMIT | FORBIDDEN
    egress = false,                 // arguments can leave the phone
    resultOrigin = SCREEN,          // null = trusted (harness/OS)
    changesScreen = true,           // server attaches the settled screen
    parallelSafe = false,
    descriptionBudget = 120,        // tokens; a test fails when exceeded
)
```

- **Where it lives:**
  - `ToolSpec` and the phone tools' specs are in `:mcp-server` (`:app` depends on it, not the
    other way round). The server's own `OBSERVED_TOOLS` and `McpToolScopes` become reads of it.
  - Native tools (memory, skills, plan, ask_user, schedule) declare theirs in agentcore with the
    same type.
  - Connector tools derive theirs at runtime from MCP annotations (`readOnlyHint`,
    `destructiveHint`, `openWorldHint`): read-only becomes `READ`, destructive becomes `COMMIT`,
    and anything else is `ACT` with egress.
- **A sync test fails** when a phone or native tool has no spec, or a spec names no registered
  tool. Connector tools are not covered, because they change at runtime.

### 8.3 Schemas and the provider cache

- Tool schemas are sent **in load order**, so loading a toolset appends to the tools field and
  keeps the cached prefix. Today `hub.visible()` filters the catalog in catalog order, so a load
  inserts in the middle **[code: `ToolHub.kt:297`]**.
- Descriptions are kept within budget. What applies to the whole run lives once in the system
  prompt, not in every tool.
- **Tool search** (needed once connectors are added; section 14): `find_tools(query)` returns
  names and one-line hints. `load_tools(names)` puts only those schemas in the next request.
  `load_toolset` stays for the built-in groups.

### 8.4 Result envelope

Every tool returns the same shape, so the model reads results one way:

```
<tool>  ok | error(code)  · one-line outcome
[screen block, for changesScreen tools]
[data, clamped; "…N more, ask with query=" when cut]
[hint: one line, only when the next move is non-obvious]
```

Errors carry a code the model can act on: `not_found`, `covered`, `stale_id`, `needs_permission`,
`declined_by_user`, `blocked_by_policy`, `timeout`.

### 8.5 The completion check

When the model claims "done" after phone actions, the check is:
1. **Counted** (`CompletionGate` from the old agent): if the plan declared N items, N pieces of
   evidence must exist in the ledger.
2. **Judged:** the aux model reads only the request, the ledger and the last screen, and returns
   `done | not_done(reason)`. It replaces today's `proofCheck`, which re-sends the whole history
   on the main model **[code: `AgentEngine.kt:323`]**.
3. **Fail-safe:** an unreachable judge marks the answer "unverified" rather than failing a run
   that worked.

The answer must cite one piece of evidence: a screen line or a tool result.

---

## 9. Safety and approvals

The gate's rules and order stay as they are (agentcore README, "Safety rules"). What changes is
the approval design from [`hermes-port-plan.md` section 1](hermes-port-plan.md#1-approval-why-the-whatsapp-send-was-blocked-and-the-new-design):

- **`propose_action(app, target, payload, summary)`** raises one card before a commit: Send ·
  Edit · Cancel. The grant `{package, target, sha256(payload), expires 10 min}` is stored **on
  the thread**. It covers the deep link, the typing and the Send tap that match it, and survives
  a hand-off or resume.
- **Scopes:** once (default); for this chat; always for this contact in this app (table
  `approval_grants`, revocable in the drawer). Never "always" for payments, deletes, or a payload
  taken from untrusted content.
- **`open_deeplink` into an installed app is `ACT`, not egress.**
- **Timeout:** fails closed after 5 minutes.
- **Denial:** tells the model the user declined this exact action and to ask what to change.
- **The PIN and password regexes** stop catching ordinary words ("the pin is ready").
- **User deny lists:** "never open app X", "never message Y".
- **Background runs never commit.**
- **No model in the gate,** no "smart" approvals, no bypass mode.

---

## 10. Hooks: one ordered list instead of twenty lambdas

`AgentEngine` takes about twenty function parameters today (`beforeTurn`, `onTurn`, `onDriving`,
`preamble`, `research`, `refusal`, `tidy`, …) **[code: `AgentEngine.kt:47-95`]**. They become one
internal hook list:

| Point | Examples |
|---|---|
| `beforeRun` | refusal, preamble (deep links, open tabs), `startTask`, start research |
| `beforeModel` | ledger render, device block, steering messages, research notes, compaction |
| `beforeTool` | repeat-call guard, pause wait (`SafetyGate` is **not** a hook: it runs first, always) |
| `afterTool` | ledger update, card building, journal, status line, taint marking |
| `afterRun` | save, title, queue the review, report card, status registry |

Hooks are internal Kotlin objects in order, each with one job and its own test. There is no
third-party code, and no hook can skip the gate. The engine shrinks to the loop itself.

---

## 11. Models

### 11.1 Roles

| Role | Used for | Today |
|---|---|---|
| **Main** | Acting | The model picked in Settings |
| **Aux** (new setting, default: the provider's fast tier) | Titles, compaction summaries, the completion judge, the goal judge, the memory and skill review, `/btw` | `FAST` resolves to `MAIN` **[code: agentcore README]** |
| **Fallback** (new setting) | Used on 429 (after retries), 5xx and network errors | None |

### 11.2 Failures

- Retries with backoff and the server's retry-after delay already wrap the OpenAI-compatible
  client agentcore uses **[code: `OpenAiCompatProvider.client` → `RetryingLLMClient`; `ModelGateway` via `ProviderRegistry.chatModel`]**.
  **[check]** whether the native Claude wire has the same.
- After retries: switch to the fallback model and say so in the run log.
- On 401 or 403: never fall back silently; tell the user the key was rejected.
- The Gemini thought-signature relay is already on that client **[code: `OpenAiCompatProvider.kt:121`]**.

### 11.3 Thinking and speed

- A one-call route runs at the lowest thinking level; loading `screen` raises it.
- The **system prompt is frozen per thread.** New skills are announced once in the next user
  message and the prompt is rebuilt only at compaction (S4), so the provider cache keeps hitting.
- **Streaming** of the final answer into the chat bubble and TTS uses Koog's Streaming API.
  Tool-call turns stay non-streamed.

---

## 12. Memory

| Layer | Holds | Store | Change |
|---|---|---|---|
| 1. Working | This run's messages | Koog prompt | Ledger (5.1), compaction (5.3) |
| 2. Run ledger | Goal, plan, done, facts, dead ends | Checkpoint | New (5.1) |
| 3. Chat | Every thread | `ThreadStore` + `items_fts` | **Thread summary** written when a thread goes idle (aux model, 3–5 lines). `search_history` gains `around = item_id` to read neighbouring messages. |
| 4. Core memory | Facts about the user and this phone | `memories` table, a bounded block in each thread's first message | One `memory` tool: `add` / `patch` (unique substring) / `remove`, in batches. Usage header `About you [61% — 860/1,400]`. Duplicates are no-ops. On overflow, the error lists entries and how much to free. |
| 5. Recall (later) | Meaning-based search over thread summaries and saved memories | Plain SQLite with vectors, cosine similarity computed in Kotlin | **EmbeddingGemma** on LiteRT. Built only if the eval shows FTS missing things a user expects AURA to remember. No vector extension in the APK. |
| 6. Procedural | How to do tasks on this phone | Skills (`SKILL.md`) | Section 13 |

**Rules:**
- **Memory enters as fenced data** in the first user message, never the system prompt (as today).
- **Snapshot frozen per thread:** a write mid-thread updates the store, and the model sees it in
  its tool result; the block itself refreshes at compaction or in a new thread.
- **Thread boundaries:** start a fresh context after 6 hours idle. The visible thread and its
  search stay unchanged.
- **One store:** the old agent's and the voice companion's memories migrate into `agentcore.db`.
- **The memory screen** shows each entry with its source ("you said", "learned from a task") and
  offers edit, delete and pin.

---

## 13. Learning

### 13.1 The idle-time review (Hermes background review, adapted)

After a run ends, and only when the phone is idle and charging or above 30% battery, the aux model
reads a digest of the run (request, ledger, outcome) and proposes:
- **memory operations** (layer 4);
- **a skill draft** when the run succeeded with 6 or more phone actions and no skill matched.

**Grounding rule:** save only what a user message or tool result shows. Never infer traits, and
never turn a one-off request into a standing preference unless the user said "always", "from now
on" or "remember".

**Taint rule:** in a run that read screens, notifications, web pages or connector data, every
proposed write is **staged**. A chip, "AURA learned 2 things", lets the user approve or discard
them. Untainted runs (the user's own words only) may write core memory directly.

### 13.2 Skills with a verified path

A skill is `SKILL.md`, the agentskills.io layout (as today), plus an optional `steps:` block: the
label-anchored step sequence from the successful run, PII removed (the old agent's
`PathStepSanitizer` does this). `skill_view` returns the procedure. `do_steps` can run the
`steps:` block (7.3).
- A skill whose app is not installed is hidden (Hermes conditional skills).
- Every skill file passes the same injection scan as memory.
- **Curator** (deterministic, no model): marks skills unused for 30 days as stale and archives
  them after 60; pinned skills are skipped. A skill whose `do_steps` failed twice in a row is
  marked "needs re-learning".

---

## 14. Connectors and Composio (Google Workspace)

**Today [code]:** `ConnectorToolSource` speaks MCP over HTTP with `Authorization: Bearer`. Tools are
pinned to the definitions the user approved (`ConnectorPins`) and prefixed with the connector id.

**Composio adds Gmail, Calendar, Drive, Docs and Sheets** (and about 1,000 other services) through
one MCP endpoint per user session. What changes:

- **Sign-in by OAuth, not an API key** (your call). Composio's managed auth runs the Google
  consent screen and stores the user's tokens as a "connected account"; the user never sees a key.
  To support that, `ConnectorToolSource` learns **MCP OAuth 2.1**: authorization code with PKCE,
  dynamic client registration, a Custom Tab for the consent screen, and an app-link redirect back
  into AURA. Tokens and refresh are kept in the secret store. This also makes any other OAuth MCP
  server (GitHub, Notion, Linear) a one-tap connect.
- **One thing to verify in a spike before building:** Composio's docs say managed OAuth needs no
  user key, but a public report says its MCP endpoint asked for an API-key header before starting
  OAuth. If that holds, AURA needs a small backend that holds AURA's Composio project key and
  creates a session per user. The project key must never ship in the APK.
- **Headers:** `ConnectorConfig` also gains a list of headers (values in the secret store) for
  servers that use keys.
- **Size:** Google Super alone lists about 522 tools. Approving each pin and sending schemas every
  request does not scale. So:
  - pinning works **per toolkit** (approve "Gmail");
  - schemas are sent only after `find_tools` / `load_tools` (8.3).
- **Risk:** tools are mapped from MCP annotations (8.2). Gmail send, Calendar create and Drive
  delete are `COMMIT` and go through `propose_action`.
- **Privacy, said plainly in the connect screen:** connector data passes through the provider's
  servers. Connector results are untrusted, fenced, and taint the run.
- **Routing:** for "email Ravi the report", `<routes>` prefers a connected Gmail tool over driving
  the Gmail app. It is one call instead of ten taps, and the result is checkable.

---

## 15. Things that run without the user

As in the port plan (sections 5 and 8):
- **Scheduler**: read-only, can attach a skill.
- **Ambient notification triage.**
- **Heartbeat** inside a thread ("tell me when the order is out for delivery"), notification-driven
  where possible.
- **`/btw`**: a read-only, screen-less side question on the aux model while a task runs.
- **Daily briefing.**

All of them run in `RunMode.BACKGROUND`, which can never commit.

---

## 16. Measuring it

### 16.1 Instruments (first, before any change)

- **E1 exporter:** agentcore runs write the trace format `benchmark/` and `scripts/agent_eval.py`
  read. Today agentcore writes nothing to `files/mcp_logs`, so the benchmark's step reports are
  empty for this agent **[code: agentcore README, "Not built yet"]**.
- **Per run:** pass/fail (AndroidWorld's grader), wall time, model calls, prompt and output
  tokens, cached tokens, images sent, phone actions, approvals raised, false "done" (agent said
  done, grader says no), steps per screen-changing action.
- **Prompt surface snapshot** (E4): the rendered system prompt plus core tool schemas are checked
  in; a prompt change shows up as a diff.
- **Mock-LLM engine tests** (E5, Koog `agents-test`): limit, pause, hand-off, defect repair,
  compaction and completion-check paths, with no network.

### 16.2 The loop for each phase

1. **Fixed set of about 20 AndroidWorld tasks** across apps and difficulty, plus 5 long tasks
   (multi-app, 50+ steps) from the AndroidLens style of nested sub-goals.
2. **Build first, measure at the end** (your call): no run of today's agent, and no benchmark
   runs between phases. The instruments from 16.1 are built first, so the end run produces the
   full table. Unit tests, recorded-tree tests and mock-model tests run on every change.
3. At the end: the fixed set, then the full 91 tasks, with the same model and thinking level
   throughout. A change is kept only if no task that passed now fails and false "done" does not rise.

You start every benchmark run yourself (`benchmark/suite`); I read the results.

---

## 17. Phases

| Phase | Contents | Done when |
|---|---|---|
| **0. Instruments** | E1 exporter, metrics table, E4 snapshot, E5 mock tests. No benchmark run. | A run on the emulator produces the metrics table |
| **1. Screen pipeline** | Stable ids and the four change classes, with recorded-tree tests (6.4); the write guard rebuilt on them; image switch off by default; `read_screen(image)`; one post-action observation in the server with a real grid or diff; eviction keeps grid plus diffs; `perceive_screen` vision-only with its own id prefix; screen history with `screens` / `screen` / `screen_diff` (6.5); descriptions rewritten | Ids unchanged across ten reads of an animating screen; images per task near 0 by default; one tool call per action step; success not lower |
| **2. Long-task core** | Run ledger; compaction rewrite (A2–A5); per-step checkpoint and resume; hand-off continues instead of re-running; no step limit, a time limit with "Keep going"; stuck detection; `TurnDefect` repair | A 150-step task finishes; a killed app offers Continue; the compaction test passes |
| **3. Tools and models** | Tool manifest and its sync test; schemas in load order; result envelope; parallel reads; aux and fallback models; completion check on aux; thinking by route; frozen prompt; streaming | Model calls per task down; cache hits up; no turn dies on one 429 |
| **4. Approvals** | `propose_action`, grants on the thread, scopes, deny lists, regex fix, E3 bench scenarios | WhatsApp, SMS, email and Telegram sends ask exactly once |
| **5. Memory and learning** | `memory` tool, usage header, thread summaries, migration, idle review with staging, skills with `steps:`, `do_steps`, curator, memory screen | Review stages facts; a repeated task runs with fewer calls through its skill |
| **6. Connectors** | Header support, toolkit pinning, `find_tools` / `load_tools`, annotation-based risk, Composio Google Workspace | "Email Ravi the report" runs with one connector call and one approval |
| **7. Assistant features and recall** | Goal mode, heartbeat, `/btw`, briefing, personality; semantic recall only if the eval asks for it | Each has an eval task |
| **8. The chat** | Section 21: previews, documents, image handling, `web_extract` with citations, streaming display, edit and regenerate, find-in-chat, meters | Attaching a photo shows its thumbnail before and after sending |

Phases 1 and 2 are the "beast loop". Phase 0 is small and comes first so that the benchmark run
at the end can measure what 1 and 2 changed.

### Every Hermes "yes" or "adapt" from the port plan, and where it lands

| Hermes feature (port plan section 8) | Here | Phase |
|---|---|---|
| Concurrent tool calls | 7.2 parallel reads | 3 |
| Fallback providers | 11.1, 11.2 | 3 |
| Auxiliary model | 11.1 | 3 |
| `/btw` side question | 15 | 7 |
| Prompt caching; Anthropic `cache_control` check | 11.3 frozen prompt; check cache-read tokens > 0 per provider in the metrics table, add `cache_control` on the Claude wire if 0 | 3 |
| Skills-index delta | 11.3 | 3 |
| Personality (SOUL) | 15, a "how AURA talks" block | 7 |
| Platform hints (voice vs chat) | Fable task 1: short, no markdown for voice and overlay | 3 |
| `@` references | Composer picker for files, contacts, recent screenshots | deferred: attachments cover most of it |
| Prompt-surface snapshot | 16.1 (E4) | 0 |
| Compression fixes; usage anchor | 5.3 | 2 |
| `memory` add/patch/remove, usage header, dedupe, staging | 12, 13.1 | 5 |
| Background review | 13.1 | 5 |
| Session search scroll | 12 layer 3 | 5 |
| Agent-managed skills, `/learn`, curator, conditional skills, skill scanning | 13.2 | 5 |
| Skills Hub installs | deferred: same `SKILL.md` format, add when a hub is chosen | — |
| `/goal` | 5.6 | 7 |
| Heartbeat | 15 | 7 |
| Event-triggered jobs | 15: "when a notification from X arrives, do Y", read-only | 7 |
| Trajectory export | 16.1 (E1) | 0 |
| Approvals, scopes, timeout, `approvals suggest` | 9; after 3 identical "once" approvals, offer "always for this contact" | 4 |
| User deny rules | 9 | 4 |
| Credential vault | Trigger Android Autofill / Credential Manager; AURA never types a password | 4 |
| Tool availability (`check_fn`) | 8.2: a spec can name a required permission; tools without it are hidden, not failed | 3 |
| Tool search | 8.3 | 6 |
| Document extraction (PDF, DOCX) | Attachments read as text through on-device extraction | 5 |
| Crash and failure reporting | Crashlytics non-fatal for `TurnEnd.FAILED` with the error class, never the content | 0 |

---

## 18. Defects found while writing this

| # | Defect | Status |
|---|---|---|
| 1 | Screenshot sent on every read, including the engine's automatic read after every action (image switch on by default) | [code] cause; cost [hypothesis] until E2 |
| 2 | Two post-action observations (server summary plus engine `read_screen`) | [code] |
| 3 | Compaction deletes old history unsummarised and summarises the newest | [code] |
| 4 | Text-shaped tool calls end the task (stripped, not repaired) | [code] |
| 5 | Loading a toolset inserts schemas mid-list, breaking the cached prefix | [code] |
| 6 | Completion check re-sends the whole history on the main model | [code] |
| 7 | Hand-off re-runs the request from scratch | [code] |
| 8 | No per-step checkpoint | [code] |
| 9 | One tool's facts spread over eight lists | [code] |
| 10 | Tool descriptions point visual questions at `perceive_screen` | [code] |
| 11 | PIN and password regexes block ordinary messages | [code] (port plan section 1) |
| 12 | som_ids are renumbered by area on every look, so one new element shifts many ids | [code] `ScreenPayload.kt:85` |
| 13 | The screen-identity hash includes every node's bounds, so moving cards, scrolls and new feed items read as a new screen | [code] `ScreenSignature.kt:125-134` |

---

## 19. Decisions

| Question | Decision |
|---|---|
| Approach | A: keep the loop, rebuild around it |
| Composio auth | OAuth sign-in through MCP OAuth 2.1; a small backend only if the spike shows Composio's MCP endpoint needs AURA's key |
| Step limit | None for foreground runs; a time limit (60 minutes default, "Keep going" extends it) plus stuck detection |
| Baseline | No run of today's agent; build, then measure |
| Aux model default | Proposed: the same provider's fast tier, changeable in Settings → Brain (not yet confirmed) |

---

## 20. Prompt work for Fable 5.2

Fable writes these, from this document. Each one lands with the E4 snapshot updated and a
fixed-set run compared against baseline:
1. System prompt: routes order, ledger usage, evidence rule, `propose_action`, `read_screen(image)` vs `perceive_screen`.
2. `read_screen`, `perceive_screen`, `do_steps` and `find_tools` descriptions within budget; server `Doctrine` and `SCREEN_GUIDE`.
3. Compaction summary prompt (what survives verbatim).
4. Completion judge and goal judge (typed outputs).
5. Idle-time review prompt with the grounding and taint rules.
6. Approval card text, the denial message and the voice read-out.

---

## 21. The chat (after the automation loop)

The chat is also an everyday chatbot, so it gets the same care. It comes **after** phases 1 to 7,
as phase 8. What Hermes' chat surfaces (Desktop, web dashboard, TUI) have, and what AURA does:

| Feature | Hermes | AURA today | Plan |
|---|---|---|---|
| Attachment previews | Badges per image; a preview rail for files and pages | **[code]** the composer shows only "N attached"; a sent bubble shows file names (`ChatUi.kt:655`, `:794`) | Thumbnails in the composer (tap to remove one, not all); image thumbnails and file chips with type icons in the sent bubble; tap opens full screen |
| Images to a text-only model | An aux vision model describes the image, and the description joins the chat (`image_input_mode`: auto, native, text) | Images dropped unless the model has vision | Same: the aux model describes images for text-only main models |
| Image size control | Downscale above 256 KB or 1,568 px; recent images replayed on rebuilt sessions (3) | Raw bytes, at most 4 images | Downscale to JPEG within a budget; keep the last 3 images across compaction, older ones become `[image: caption]` |
| Documents | PDF, DOCX, XLSX and more become paginated markdown | Text and JSON only | PDF (Android `PdfRenderer` plus ML Kit OCR for scanned pages), DOCX and XLSX as text, paged with a `read_attachment(offset)` tool |
| Web search and extract | `web_search` plus `web_extract`, several providers with a keyless fallback ring; long pages cut head and tail with the full text saved for paging; results cached 20 minutes | `web_search` (Tavily key) and the browser tools | Add `web_extract` with head and tail cuts and paging; cache per run; citations as tappable source chips under the answer |
| Streaming | Live text and live tool activity | Whole reply at the end | Koog streaming (11.3); tool steps fold into one "Worked for 40 s · 12 steps" row that expands |
| Tool display | Collapsible tool rows; image results open full size | Folded steps (`ChatCards.kt`) | Keep; add screenshots from the screen history inline |
| Artifacts | A gallery of generated files, linked to their session | File cards | A per-chat "Files" sheet listing everything the chat produced or received |
| Search | Find in transcript; search across sessions | Full-text search in the drawer | Add find-in-chat |
| Model picker, reasoning effort | Picker plus effort pill | Picker in the composer | Add an effort pill |
| Context meter, cache hit rate | Status bar | None | A small meter in the model sheet (from the run's real token counts) |
| Copy | Code blocks copy with whitespace | Copy, plain-text copy, share | Copy button on each code block |
| Edit and regenerate | Not documented | None | Edit the last user message and re-run; regenerate the last answer |
| Voice | Dictation, read-aloud, wake word, conversation | Overlay voice; chat mic opens the overlay | Read-aloud button on replies; dictation inside the composer |

---

## 22. Progress (as of 2026-11-14)

Code lives in `aura-android/app/src/main/java/com/aura/aura_ui/agentcore/` (see its README) and
`aura-android/mcp-server/`. Each finished piece is a commit on `feat/agentcore`.

| Phase | State |
|---|---|
| 0. Instruments | Check against the code before the benchmark run; not re-verified in the last sessions. |
| 1. Screen pipeline | Done. |
| 2. Long-task core | Done. |
| 3. Tools and models | Done except: result envelope (8.4), thinking by route (11.3), frozen prompt, streaming (moved to phase 8). The `ToolSpec` manifest is partial: `ToolListSyncTest` checks the lists, the eight lists of 8.1 are not yet one manifest. |
| 4. Approvals | Done (`propose_action`, chat grants, deny lists, two security-review rounds). E3 bench scenarios for sends: check whether they exist; add if not. |
| 5. Memory and learning | Done: `memory` tool with usage header and staging, `agentcore.db` v2 with `MIGRATION_1_2`, memory screen, run digests, idle review (`Learning.kt`), skill drafts, skills with `app:`/`steps:`, `do_steps`, curator, 6-hour fresh start with thread summary, one-time import of the old agent's facts. Not built: layer 5 recall (only if the eval asks); the voice companion still uses its own store. |
| 6. Connectors | Built: key in any header (Composio preset), risk from MCP annotations, per-call risk for Composio multi-execute, `find_tools`/`load_tools`, subject on the send card, routing prompt. Left: verify "email Ravi the report" = one connector call and one approval against the live Composio server (needs the user's consumer key and a phone); generic MCP OAuth 2.1 (PKCE, dynamic client registration, Custom Tab, app-link redirect) for GitHub/Notion/Linear; per-toolkit pinning (not needed for Composio Connect's 7 meta-tools). |
| 7. Assistant features | Done: goal mode (5.6, `Goals.kt`, eval task 33). Left: heartbeat, `/btw`, daily briefing, personality (section 15). |
| 8. The chat | Not started: section 21, plus streaming. |
| Hardening | Not started. Fixed: `AgentRunLoggerTrailTest` read the first session folder on disk, which another test's writer could still be filling; it now matches its own session. Known: section 18 defects to re-check; no device smoke test of phases 4 to 6 yet. |
| Benchmark | The user starts it (`benchmark/suite/suite.py`); never start or restart it yourself. |

Fable prompt work (section 20) done: system prompt sections (routes, tools, memory, skills,
safety's `propose_action`), compaction summary, completion judge, idle review, thread summary,
`memory`/`do_steps`/`find_tools`/`load_tools`/`propose_action` descriptions, goal judge. Left: approval card text, denial message and voice read-out (item 6), and a re-check of the
`read_screen`/`perceive_screen` descriptions against their budget.

## References

- Hermes Agent developer guide: https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- Koog 1.3 documentation: https://docs.koog.ai/
- Executable Agentic Memory for GUI Agent (EAM): https://arxiv.org/html/2605.12294v1
- Jev-Mobile, a fast executor for mobile GUI agents: https://arxiv.org/html/2609.30186v1
- AppAgentX, learned shortcuts for smartphone agents: https://arxiv.org/abs/2503.02268
- Efficient GUI Agents, a systems survey: https://arxiv.org/abs/2609.02309
- AndroidLens, long tasks with nested sub-targets: https://arxiv.org/pdf/2512.21302
- AndroidWorld: https://arxiv.org/html/2405.14573v3
- Composio Google Super toolkit: https://composio.dev/toolkits/googlesuper
- Composio MCP review (Tool Router GA, `x-api-key`): https://chatforest.com/reviews/composio-mcp-server/
- EmbeddingGemma 2 on the edge: https://developers.googleblog.com/en/google-ai-edge-with-embeddinggemma-2/
- Thoughtworks Radar, on-device retrieval: https://www.thoughtworks.com/radar/techniques/on-device-information-retrieval
- Composio managed authentication: https://docs.composio.dev/docs/managed-authentication
- Composio connected accounts: https://docs.composio.dev/kb/guide/platform-connected-accounts.md
- Report of Composio's MCP endpoint asking for a key: https://forum.cursor.com/t/composio-mcp-oauth-issue/161560
- Hermes Desktop: https://hermes-agent.nousresearch.com/docs/user-guide/desktop
- Hermes Web Dashboard: https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard
- Hermes Vision: https://hermes-agent.nousresearch.com/docs/user-guide/features/vision
- Hermes Web Search: https://hermes-agent.nousresearch.com/docs/user-guide/features/web-search
- Hermes Document Extraction: https://hermes-agent.nousresearch.com/docs/user-guide/features/document-extraction
- laya-android model card: https://huggingface.co/ByunByun/laya-android
