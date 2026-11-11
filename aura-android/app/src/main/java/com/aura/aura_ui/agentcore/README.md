# agentcore

> **Last reviewed:** 2026-11-11.
>
> **Keep this file and the KDoc current.** If you change a file listed here, update its header
> comment and this README in the same commit.

The new on-device chat agent for AURA: one Koog agent loop with threads, memory, skills, scheduled
tasks, connectors and a safety gate in front of every tool. The chat is reachable from the
**New agent** button on Home, which shows when developer mode is on (`DeveloperModeStore.enabled`).

**The agent switch.** `AgentCore.enabled` (the "Use for voice and the overlay" switch in the chat
drawer) decides which agent runs a task started from the overlay or the voice companion:
`AuraOverlayService` and `AuraAgentPhoneRunner` call `AgentCore.ifEnabled(context)?.runTask(...)`
and fall back to the agent under `agent/`. Off by default.

## Files

| File | What it holds |
|---|---|
| `AgentCore.kt` | Composition root. `AgentCore.get(context)` builds everything; `runTask` (the overlay and voice entry); the 15-minute `AgentWorker`; notification intake. |
| `AgentEngine.kt` | The loop. `runTurn()` returns a `Flow<AgentEvent>`; `steer()` adds a message to a running turn. |
| `AgentModels.kt` | Shared vocabulary: `ToolRisk`, `Origin`, `RunMode`, `AgentEvent`, `TurnLimits`. Pure Kotlin. |
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
| `Telemetry.kt` | OpenTelemetry spans and the screenshots sent to the model, in `files/agentcore/traces/`, redacted, kept 7 days; `recent()` reads them back for the run log. |
| `ChatUi.kt` | The chat screen and its ViewModel: drawer with past chats, scheduled tasks, connections, run log. No agent logic. |

Tests are in `app/src/test/java/com/aura/aura_ui/agentcore/`. Run them with
`./gradlew :app:testDebugUnitTest --tests "com.aura.aura_ui.agentcore.*"`.

## How a turn runs

1. `AgentEngine.runTurn` loads the thread's saved context and taint, then appends the user's
   message. Saved memory goes in the first user message of a thread, device context in every one.
   Neither goes in the system prompt, which stays byte-identical so provider prompt caches hit.
2. The model sees only the `core` toolset plus a one-line menu of the others. It calls
   `load_toolset` to get `screen`, `phone`, `browser` or a connector.
3. Each tool call goes through `ToolHub.HubTool`: `SafetyGate.evaluate`, then the user if the
   verdict is `Ask`, then the source. Results from untrusted origins are fenced and taint the turn.
4. After a step whose action changed the screen, the engine reads the screen itself
   (`ToolHub.observeScreen`) and appends it, so the model's next request already has it.
5. Between steps the engine keeps only the newest screen read, clears old long tool outputs, and
   summarises when the context passes half the model's window.
6. The first time a turn loads the `screen` toolset, web research on the task starts beside it
   and is never awaited; its note arrives after a later tool result, fenced as web content.
7. When the model says it is done after three or more successful phone actions, it is asked once
   to check the result against the request and name what it saw (`proofCheck`).
8. The turn ends on an answer, the step or time limit, cancel, or failure. What was completed is
   saved either way.

A request to delete the user's data in bulk is refused before any model sees it. The screen is
kept on for the length of a foreground turn.

There is one agent and no sub-agents: the phone has one screen, so two agents cannot act at once.

## Safety rules (the order in `SafetyGate.evaluate` is the policy)

1. `FORBIDDEN` tools never run.
2. A background run (scheduled task, digest) may only read.
3. Arguments that leave the phone must not contain a password, key, card number or code.
4. A commit (send, pay, delete, post, or a tap on a button labelled that way) asks the user. The
   label comes from the tool server (`labelLookup` in `InProcessMcpServer.connect`), and the
   prompt shows what the model wrote about the step.
5. A connector write asks the user.
6. After the turn has read private content (screen, notification, file, connector), the first call
   that sends data off the phone asks once per turn.

No model is consulted for any of this, so nothing on a screen or a web page can argue its way
past it. The phone server's own `SensitivePolicy` and `ActionGuard` still run after the gate.
A connector's tools are pinned to the definitions the user approved when adding it
(`ConnectorPins`); a tool that changes later is hidden.

## Borrowed from the current agent

These imports cross into `agent/` and `mcp/bridge/`, and must move here before that code is
deleted: `McpToolSchemaParser` (in `ToolSources.kt`); `ProviderRegistry` plus the key and endpoint
stores (in `ModelGateway.kt`); and in `AgentCore.kt`, `TaskResearch` and `WebResearch`,
`AskUserBroker`, `RunKeepAwake`, `DestructiveGoal`, `readDeviceFacts`, `AgentStreamEvent` and
`profileBlockOrEmpty`.

## Not built yet

- A local model. `Ambient.kt` has the `LocalModel` seam; digests are written by rules until one
  is plugged in. It has to be LiteRT-LM: ML Kit's Gemini Nano refuses to run in the background.
- Streaming replies, a fallback model, and separate FAST model selection (FAST resolves to MAIN).
- Remote trace export UI (`RemoteTraces` exists, nothing sets it).
- Resuming a turn after the app is killed.
- Voice input on the chat screen (the mic button is not wired in `AuraAppShell.kt`), and the
  app's own visual style for it.
- The voice companion's own conversation is not in the run log.
