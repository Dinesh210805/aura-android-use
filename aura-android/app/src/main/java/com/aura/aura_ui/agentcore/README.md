# agentcore

> **Last reviewed:** 2026-11-14.
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
| `AgentEngine.kt` | The loop. `runTurn()` returns a `Flow<AgentEvent>`; `steer()` adds a message to a running turn. With `stream` (the chat sets it) each request streams for display (`AgentEvent.Streaming`) and the reply is assembled from the frames, so the prompt and everything after it are the same as without; a broken stream is asked again without streaming. |
| `AgentModels.kt` | Shared vocabulary: `ToolRisk`, `Origin`, `RunMode`, `AgentEvent`, `TurnLimits`. Pure Kotlin. |
| `Cards.kt` | `ChatCard`: files, contacts and results, built by the hub from `find_files` and `resolve_contact` results, stored as `CARD` items. Pure Kotlin. |
| `SafetyGate.kt` | Allow, ask or deny for every tool call; taint tracking; secret patterns; the fence for untrusted text. |
| `ToolHub.kt` | Merges tool sources, runs each call through the gate, owns `load_toolset`, `ask_user`, `update_plan`, and `UserBroker`. |
| `ToolSources.kt` | Where tools come from: the phone (`:mcp-server`, in process) and remote MCP connectors (`ConnectorStore`). |
| `ContextAssembler.kt` | System prompt (with the `TalkStyle` block the agent page picks; Balanced adds none), device context, stale-screen eviction, tool-output pruning, compaction (summarise the middle, keep the request and the tail verbatim), token budget. |
| `RunLedger.kt` | The plot of a long run (request, plan, last actions, step and minutes), shown as one message from step 8; only the newest is kept. |
| `ScreenHistory.kt` | Every screen a task saw (`S3`, diffs `S3.1`), read back by `screens`, `screen`, `screen_diff`; counts distinct screens for stuck detection. |
| (context meter) | `AgentEngine.usage`: each chat's last request as the provider counted it (`Usage` in `AgentModels.kt`). Only Koog's Anthropic client reports a cache (`cacheReadInputTokens`, left out of its input count, so added back); the OpenAI-compatible client drops it and the meter shows no cache share. The model sheet shows "Context: 12k of 128k tokens · 64% from cache" with a bar. In memory only. |
| `CodeBlocks.kt` | ``` fences in a reply (`blocks`) drawn monospace, scrolling sideways, each with its own copy button. |
| `ReadAloud.kt` | The read-aloud button on each reply: one process-wide `AuraTTSManager` in the voice from Voice settings, warmed when the chat opens. |
| `ModelGateway.kt` | Resolves the model the user picked in Settings into a Koog client. FAST is that model at its lightest thinking (titles, compaction summaries). FALLBACK is the first other provider the user set up; `FallbackClient` switches a turn to it when the main provider is rate limited, erroring or unreachable after its retries, never on a rejected key, and says so in the chat. |
| `ThreadStore.kt` | Room database `agentcore.db` (version 4, with `MIGRATION_1_2`, `MIGRATION_2_3` and `MIGRATION_3_4`): threads, visible items, full-text search, memories, jobs, run digests. |
| `Memory.kt` | Bounded always-in-context memory and its tools: `memory` (batched add, patch, remove; a tainted turn stages adds for the user) and `search_history` (with `around` to read a hit's neighbours). |
| `Skills.kt` | Saved procedures as `SKILL.md` folders under `files/agentcore/skills/`, with optional `app:` and `steps:` frontmatter, usage in `usage.json`, and the curator (archives unpinned skills unused for 60 days to `skills-archive/`; two failed `do_steps` chains flag a skill for re-learning). |
| `Steps.kt` | `Step`: one label-anchored phone action. The run's trace, a skill's `steps:` and `do_steps` input share it; `Step.forSkill` strips typed text, links and personal details. |
| `Learning.kt` | The idle review: each finished run leaves a digest (`digests` table); when the agent is idle and the phone is charging or above 30%, the 15-minute worker has the FAST model read up to three and propose memory changes and a skill draft. A tainted run only stages memory adds; drafts always wait in `skill-drafts/` until the user keeps them. The chat shows "AURA learned N things". |
| `ToolSearch.kt` | Ranking for `find_tools`. A toolset with more than `ToolHub.BIG_TOOLSET` tools is a count in the menu and is reached with `find_tools` then `load_tools`, which appends just those schemas in load order. |
| `ConnectorRisk.kt` | Connector tool risk from MCP annotations; per-call risk for Composio's multi-execute from the app tools it runs. |
| `DoSteps.kt` | `do_steps`: runs a known step list in one call, finding each target by label on a fresh screen and sending every action through the hub's own gate. |
| `Btw.kt` | `/btw <question>` in the chat: a side question answered on the FAST model with no tools, from the fenced recent chat, while a task runs. Checked before steering, so it never reaches the running turn; the Q and A are visible items only. |
| `Goals.kt` | Goal mode: `/goal <text>` in the chat runs up to 8 turns in the chat (no hand-off). After each turn the FAST model judges `DONE`, `CONTINUE` or `BLOCKED: reason` from the goal, each turn's tool names and its fenced answer. The next turn gets a fixed "keep going" message, never the judge's words; anything unparseable stops the goal. |
| `WebExtract.kt` | `web_extract`: one web page as text, 12,000 characters a page, cached 20 minutes. Public addresses only (`PublicDns` checks every host and redirect hop), http(s) only, 2 MB bodies. A send-out to the gate, like `web_search`. Pages a turn read become a `ChatCard.Sources` row of chips under its answer. |
| `Scheduler.kt` | Scheduled prompts (`schedule_task`), run read-only in the background; heartbeats (`watch_for`): a job bound to the chat that asked, checked every 15 minutes or at once when a notification shares a word with the condition (always-on only), reporting once into that chat and then ending, or giving up after its hours; the morning briefing (`Briefing`), a daily 08:00 job whose existence is its on/off switch on the agent page. |
| `Ambient.kt` | Notification triage and digests. |
| `Telemetry.kt` | OpenTelemetry spans (model calls: timing, tokens) in `files/agentcore/traces/`; `recent()` joins them with the journal into `RunLog`s. |
| `RunJournal.kt` | The readable run log: request, each step in plain words (`StepPhrase`), the screen before each press with the pressed element's box, the answer. Redacted, kept 7 days. |
| (edit and regenerate) | A foreground turn that changed nothing (every call past the gate was a read; `TurnState.changed`) and ended answered or failed leaves `threads.undo`: the context and taint from before it and its first item. The chat then offers Regenerate on the reply and Edit (Try again after a failure) on the message; both take the turn back (`ThreadStore.undo`) and send again. A turn that sent, tapped or saved anything can never be run twice this way. |
| `ChatTheme.kt` | The chat's colours (Apple system colours, light and dark), Inter type, the glass surface. |
| `ChatUi.kt` | The chat screen and its ViewModel: greeting with the app marquee, transcript, glass composer with the model picker. No agent logic. |
| `AttachmentReader.kt` | What the user attached, read for the model: pictures as JPEG at most 1,568 px (same bytes every time, so a replayed picture keeps the prompt cache); document text a 20,000-character window at a time (text, PDF page by page with its text layer or ML Kit OCR of the rendered page, Word, Excel); OCR text of a picture for a model that cannot see. `read_attachment` pages a file attached in the current chat. |
| `DocText.kt` | Word and Excel files as text (zipped XML, read with a size cap and no DTD); `DocChunk`, one window of a document. Pure. |
| `ChatAttachments.kt` | Attachments in the chat: image thumbnails and file chips with type icons, in the composer (× removes one) and in the sent bubble; a tap opens the image full screen or the file in its app. Pictures decode off the main thread at display size into a 16 MB cache keyed by URI. |
| `ChatFind.kt` | Find in chat: the search button in the top bar opens a bar that steps through the user's and AURA's messages holding the words, newest first; the transcript scrolls to and tints the match. |
| `ChatCards.kt` | Cards: files (grid and rows, tap opens, hold shares), contacts (call, message, WhatsApp; tap to choose), results, plan, the question and Allow/Edit/Deny cards, folded steps. |
| `ChatDrawer.kt` | The drawer (account, chats by day, panels, switches, appearance) and the panels: run log, schedules, memory, skills, connections. |
| `RunLogUi.kt` | Draws `RunLog`: a timeline per run, screenshots with the pressed element outlined, raw values under Details. |

Tests are in `app/src/test/java/com/aura/aura_ui/agentcore/`. Run them with
`./gradlew :app:testDebugUnitTest --tests "com.aura.aura_ui.agentcore.*"`.

## How a turn runs

1. `AgentEngine.runTurn` loads the thread's saved context and taint, then appends the user's
   message. Saved memory goes in the first user message of a thread, device context in every one.
   A chat idle for more than 6 hours starts the model over: the first message then carries a short
   summary of the chat (FAST model, saved in `threads.summary`); the visible chat is unchanged.
   Neither goes in the system prompt, which stays byte-identical so provider prompt caches hit.
2. The model sees the `core` toolset plus a one-line menu of the others. `core` holds the direct
   routes (system intents, deep links, contacts, files, media, notifications, web search), so most
   tasks finish without the screen; the system prompt's `<routes>` gives their order. It calls
   `load_toolset` to get `screen`, `phone`, `browser` or a connector. Saved skills are listed in
   the system prompt and read with `skill_view`.
3. Each tool call goes through `ToolHub.HubTool`: `SafetyGate.evaluate`, then the user if the
   verdict is `Ask`, then the source. Results from untrusted origins are fenced and taint the turn.
4. After a step whose action changed the screen, the engine reads the screen itself
   (`ToolHub.observeScreen`, a changes-only read) and appends it, so the model's next request
   already has it; the action's own settle summary is then cut to settled/changed. When the server
   proves the action changed nothing, the read is skipped.
5. Between steps the engine keeps only the newest full screen and the diffs after it, clears old
   long tool outputs, and past half the model's window summarises the middle of the conversation
   (`ContextAssembler.compactionRange`), keeping the request and the recent tail verbatim. From
   step 8 it adds the run ledger (`RunLedger`). Fifteen steps with no screen it had not seen and
   no plan step done add a "not making progress" note. The context is saved after every step.
6. The first time a turn loads the `screen` toolset, web research on the task starts beside it
   and is never awaited; its note arrives after a later tool result, fenced as web content.
7. A reply with neither words nor a tool call gets one nudge. When the model says it is done
   after three or more successful phone actions, the FAST model judges once, from the request,
   the actions, the last screen and the answer, whether it is done (`notDone`). Only a "not done"
   goes back to the main model; a judge that fails counts as done. Tool-call syntax written as text is removed
   from the answer (`ToolCallDebris`).
8. The turn ends on an answer, the time limit (60 minutes; the chat then offers "Keep going"),
   the request cap set in Settings → Brain, cancel, or failure. Background turns also have a
   small step limit. A foreground run marks itself in prefs while it runs; a mark left after the
   app died shows "Continue" in the chat.
9. A chat turn that needs the screen hands the task to the overlay, saving what it did; the
   overlay's turn continues from it instead of sending the request again.

A request to delete the user's data in bulk is refused before any model sees it. The screen is
kept on for the length of a foreground turn.

There is one agent and no sub-agents: the phone has one screen, so two agents cannot act at once.

## Safety rules (the order in `SafetyGate.evaluate` is the policy)

1. `FORBIDDEN` tools never run.
1b. The user's deny lists (`AgentCore.deniedApps`, `deniedContacts`, set on the agent page under
   "Never") refuse any action in or opening a listed app, and any send naming a listed person, by
   whole word or by number (`SafetyGate.blocked`).
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
8. `propose_action(app, to, message)` shows one card with the exact text before a send. A yes is a
   `Grant` on the chat (in memory, 10 minutes): it covers a link or connector call carrying that
   text and one Send/Post tap in that app while the screen shows it (`SafetyGate.covered`), then is
   used up. "Don't ask again in this chat" (not offered after untrusted text was read) covers sends
   to that person in that app for the chat, but never the private-content send-out ask. Orders,
   bookings and deletes always get their own card. A deep link's phone number counts as routing
   only when the user said it or `resolve_contact` returned it this turn.

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
- A separate FAST model setting (FAST is MAIN at its lightest thinking).
- Remote trace export UI (`RemoteTraces` exists, nothing sets it).
- Resuming a turn after the app is killed. The overlay's resume chip still offers the other
  agent's runs only.
- The voice companion's "how's it going" and mid-task steering read `ActiveRunRegistry`, which
  only the other agent fills.
- Runs are not written to `files/mcp_logs`, so the Activity screen, the benchmark's step reports
  and eval traces are empty for this agent; its log is the drawer's Run log and its export.
- The other agent's live, non-sensitive facts about the user are copied in once, before the first turn (`MemoryImport`); the voice companion still reads and writes its own store.
- A real backdrop blur on the glass (`ChatTheme.glass`).
- Voice input inside the chat screen (its mic button opens the overlay), and the app's own
  visual style for it.
- The voice companion's own conversation is not in the run log.
