# agentcore

> **Last reviewed:** 2026-11-13.
>
> **Keep this file and the KDoc current.** If you change a file listed here, update its header
> comment and this README in the same commit.

The new on-device chat agent for AURA: one Koog agent loop with threads, memory, skills, scheduled
tasks, connectors and a safety gate in front of every tool. The chat is the app's Home
(`AuraRoutes.HOME` in `AuraAppShell.kt`).

**The agent switch.** `AgentCore.enabled` (the "Use for voice and the overlay" switch in the chat
drawer) decides which agent runs a task started from the overlay or the voice companion:
`AuraOverlayService` and `AuraAgentPhoneRunner` call `AgentCore.ifEnabled(context)?.runTask(...)`
and fall back to the agent under `agent/`. On by default.

**Hand-over to the overlay.** A chat turn does not drive the screen itself. The moment it asks
for the `screen` toolset it ends with `TurnEnd.HANDED_OFF`, having saved nothing, and
`AgentCore.handOff` sends the same request to `AuraOverlayService` (`ACTION_RUN_TASK`), where the
user sees the transcript and can pause. The overlay's turn continues in the chat's thread with
the toolset already loaded. This is skipped when the overlay cannot be drawn or the switch is off;
the chat then drives the screen itself. Attachments do not travel with a hand-over.

**Pausing.** While a turn drives the screen it marks a local run in `ControlLockStore`, which
arms pause-on-touch and shows the overlay's Pause pill. While the user holds the phone the loop
waits instead of calling tools (up to 10 minutes, then it ends and "continue" picks it up).
`runTask` with `remember = false` (benchmark and eval tasks) runs in a thread of its own that is
deleted afterwards.

**The run log is complete.** Nothing in it is shortened; only secrets are redacted. It is two
files per day in `files/agentcore/traces/`, plus pictures:
- `spans-<date>.jsonl` (`Telemetry.kt`): OpenTelemetry spans, always verbose. Each model call
  carries the full input messages, the system prompt, the reply, token counts and the response
  metadata (cached tokens on providers that report them).
- `steps-<date>.jsonl` (`RunJournal.kt`): the request, every tool call with its full arguments
  and result, the model's words, and notes. A note records what the harness did or decided
  between tool calls: each safety-gate refusal or question with the rule's inputs and the
  user's answer, the model's thinking, compaction with its summary, nudges, the completion
  check, steering, research notes, pauses and raw errors.
- `shot-<ms>.jpg` and `screen-<ms>.png`: the screen before each press, and the annotated
  screenshot sent to the model.

The Run log page draws them (`RunLogUi.kt`, `RunDetail.kt`). Its share button exports every log
file as a zip, and a run's page exports just that run (`RunExport` in `Telemetry.kt`). On a debug
build, `adb exec-out run-as com.aura.aura_ui.feature.debug tar c files/agentcore/traces > traces.tar`
also pulls the folder.

## Files

| File | What it holds |
|---|---|
| `AgentCore.kt` | Composition root. `AgentCore.get(context)` builds everything; `runTask` (the overlay and voice entry); the request preamble (catalog deep links for the named app, open browser tabs); the 15-minute `AgentWorker`, started from `AuraApplication`; notification intake; bundled skills. |
| `AgentEngine.kt` | The loop. `runTurn()` returns a `Flow<AgentEvent>`; `steer()` adds a message to a running turn. |
| `AgentModels.kt` | Shared vocabulary: `ToolRisk`, `Origin`, `RunMode`, `AgentEvent`, `TurnLimits`. Pure Kotlin. |
| `Cards.kt` | `ChatCard`: files, contacts and results, built by the hub from `find_files` and `resolve_contact` results, stored as `CARD` items. Pure Kotlin. |
| `SafetyGate.kt` | Allow, ask or deny for every tool call; taint tracking; secret patterns; the fence for untrusted text. |
| `ToolHub.kt` | Merges tool sources, runs each call through the gate, owns `load_toolset`, `ask_user`, `update_plan`, and `UserBroker`. |
| `ToolSources.kt` | Where tools come from: the phone (`:mcp-server`, in process) and remote MCP connectors (`ConnectorStore`). |
| `ContextAssembler.kt` | System prompt, device context, stale-screen eviction, tool-output pruning, token budget. |
| `ModelGateway.kt` | Resolves the model the user picked in Settings into a Koog client. |
| `ThreadStore.kt` | Room database `agentcore.db`: threads, visible items, full-text search, memories, jobs. |
| `Memory.kt` | Bounded always-in-context memory and its `remember`, `forget`, `search_history` tools. |
| `Skills.kt` | Saved procedures as `SKILL.md` folders under `files/agentcore/skills/`. |
| `Scheduler.kt` | Scheduled prompts (`schedule_task`), run read-only in the background. |
| `Ambient.kt` | Notification triage and digests. |
| `Telemetry.kt` | OpenTelemetry spans (model calls: timing, tokens) in `files/agentcore/traces/`; `recent()` joins them with the journal into `RunLog`s. |
| `RunJournal.kt` | The readable run log: request, each step in plain words (`StepPhrase`), the screen before each press with the pressed element's box, the answer. Redacted, kept 7 days. |
| `ChatTheme.kt` | The chat's colours (Apple system colours, light and dark), Inter type, the glass surface. |
| `ChatUi.kt` | The chat screen and its ViewModel: greeting with the app marquee, transcript, glass composer with the model picker. No agent logic. |
| `ChatCards.kt` | Cards: files (grid and rows, tap opens, hold shares), contacts (call, message, WhatsApp; tap to choose), results, plan, the question and Allow/Edit/Deny cards, folded steps. |
| `ChatDrawer.kt` | The drawer (account, chats by day, panels, switches, appearance) and the panels: run log, schedules, memory, skills, connections. |
| `RunLogUi.kt` | Draws `RunLog`: a timeline per run, screenshots with the pressed element outlined, raw values under Details. |

Tests are in `app/src/test/java/com/aura/aura_ui/agentcore/`. Run them with
`./gradlew :app:testDebugUnitTest --tests "com.aura.aura_ui.agentcore.*"`.

## How a turn runs

1. `AgentEngine.runTurn` loads the thread's saved context and taint, then appends the user's
   message. Saved memory goes in the first user message of a thread, device context in every one.
   Neither goes in the system prompt, which stays byte-identical so provider prompt caches hit.
2. The model sees the `core` toolset plus a one-line menu of the others. `core` holds the direct
   routes (system intents, deep links, contacts, files, media, notifications, web search), so most
   tasks finish without the screen; the system prompt's `<routes>` gives their order. It calls
   `load_toolset` to get `screen`, `phone`, `browser` or a connector. Saved skills are listed in
   the system prompt and read with `skill_view`.
3. Each tool call goes through `ToolHub.HubTool`: `SafetyGate.evaluate`, then the user if the
   verdict is `Ask`, then the source. Results from untrusted origins are fenced and taint the turn.
4. After a step whose action changed the screen, the engine reads the screen itself
   (`ToolHub.observeScreen`) and appends it, so the model's next request already has it.
5. Between steps the engine keeps only the newest screen read, clears old long tool outputs, and
   summarises when the context passes half the model's window.
6. The first time a turn loads the `screen` toolset, web research on the task starts beside it
   and is never awaited; its note arrives after a later tool result, fenced as web content.
7. A reply with neither words nor a tool call gets one nudge. When the model says it is done
   after three or more successful phone actions, it is asked once to check the result against
   the request and name what it saw (`proofCheck`). Tool-call syntax written as text is removed
   from the answer (`ToolCallDebris`).
8. The turn ends on an answer, the step or time limit (or the request cap set in Settings → Brain),
   cancel, or failure. What was completed is saved either way.

A request to delete the user's data in bulk is refused before any model sees it. The screen is
kept on for the length of a foreground turn.

There is one agent and no sub-agents: the phone has one screen, so two agents cannot act at once.

## Safety rules (the order in `SafetyGate.evaluate` is the policy)

1. `FORBIDDEN` tools never run.
2. A background run (scheduled task, digest) may only read.
3. Arguments that leave the phone must not contain a password, key, card number or code.
4. A commit (send, post, order, book, delete, or a tap on a button labelled that way) shows one
   verification card: a title from the gate ("Send in WhatsApp?") and the line the model wrote
   about the step. The label comes from the tool server (`labelLookup` in
   `InProcessMcpServer.connect`). In Hands-free mode only orders, deletes, unlabelled commits and
   commits in a turn that read a notification or web page ask.
5. A connector write asks the user, except in Hands-free mode on a turn that read nothing private.
6. After the turn has read private content (screen, notification, file, connector), the first call
   that sends data off the phone asks once per turn, in every mode. A link or intent that lands in
   an installed app (`https://wa.me/…` into WhatsApp, `system_intent`) is not a send-out: that
   app's own Send is the commit.
7. In Ask often mode, opening an app, link, intent or file also asks.

The mode (`ApprovalMode`: Ask often, Confirm sends, Hands-free) is picked on the agent page and
stored in `AgentCore.approvalMode`; Confirm sends is the default. A declined card tells the model
to ask the user what to change, not to find another route.

No model is consulted for any of this, so nothing on a screen or a web page can argue its way
past it. The phone server's own `SensitivePolicy` and `ActionGuard` still run after the gate.
A connector's tools are pinned to the definitions the user approved when adding it
(`ConnectorPins`); a tool that changes later is hidden.

## Borrowed from the current agent

These imports cross into `agent/` and `mcp/bridge/`, and must move here before that code is
deleted: `McpToolSchemaParser` (in `ToolSources.kt`); `ProviderRegistry` plus the key and endpoint
stores (in `ModelGateway.kt`); and in `AgentCore.kt`, `TaskResearch` and `WebResearch`,
`AskUserBroker`, `RunKeepAwake`, `DestructiveGoal`, `readDeviceFacts`, `AgentStreamEvent`,
`profileBlockOrEmpty`, `AppLinksPreamble`, `ResearchText` and `ToolCallDebris`.

## Not built yet

- A local model. `Ambient.kt` has the `LocalModel` seam; digests are written by rules until one
  is plugged in. It has to be LiteRT-LM: ML Kit's Gemini Nano refuses to run in the background.
- Streaming replies, a fallback model, and separate FAST model selection (FAST resolves to MAIN).
- Remote trace export UI (`RemoteTraces` exists, nothing sets it).
- Resuming a turn after the app is killed. The overlay's resume chip still offers the other
  agent's runs only.
- The voice companion's "how's it going" and mid-task steering read `ActiveRunRegistry`, which
  only the other agent fills.
- Runs are not written to `files/mcp_logs`, so the Activity screen, the benchmark's step reports
  and eval traces are empty for this agent; its log is the drawer's Run log and its export.
- Memories saved by the other agent are not migrated, and the voice companion still uses that store.
- A real backdrop blur on the glass (`ChatTheme.glass`).
- Voice input inside the chat screen (its mic button opens the overlay), and the app's own
  visual style for it.
- The voice companion's own conversation is not in the run log.
