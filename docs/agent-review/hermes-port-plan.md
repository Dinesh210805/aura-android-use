# AURA agent: what to borrow from Hermes and Koog, and how to make it faster and more accurate

> **Last reviewed:** 2026-11-13, against `agentcore/` on `feat/agentcore`, Hermes Agent `main`
> (shallow clone, docs under `website/docs/`) and Koog 1.3.0 (the version in `libs.versions.toml`).
>
> **Status:** plan only. No code has changed.

This is the plan for turning `agentcore/` into a production-grade personal assistant. It
answers four questions:

1. Why did AURA refuse to send a WhatsApp message, and how should approval work instead?
2. Why is it slow, and what makes it faster?
3. What should change in memory?
4. Which Hermes features do we already have, which do we port, and which don't fit a phone?

The full feature table is in [section 8](#8-every-hermes-feature-and-what-aura-does-with-it).

---

## 0. Where we already are

`agentcore/` already follows Hermes in more places than it looks. Don't rebuild these:

| Hermes idea | AURA today |
|---|---|
| Bounded, curated memory (`MEMORY.md` 2,200 chars, `USER.md` 1,375) | `Memory.kt`: `MemoryKind.NOTES` 2,200 and `MemoryKind.USER` 1,400. A write that would overflow fails and tells the model to forget something first, exactly as in Hermes. |
| Session search over every past conversation (SQLite FTS5) | `search_history` over the `items_fts` index in `ThreadStore.kt` |
| Skills as `SKILL.md` folders, progressive disclosure (`skill_view`) | `Skills.kt`, index in the system prompt, `skill_view` to load |
| Toolsets plus Tool Search (load schemas on demand) | `load_toolset` in `ToolHub.kt`: `core` is always loaded; `screen`, `phone`, `browser` and each connector load on demand |
| Byte-stable system prompt, so provider caches hit | `SystemPrompt.build`; memory and device context go in the user message |
| Scheduled agent tasks (cron), fresh session, no tool writes | `Scheduler.kt`; `RunMode.BACKGROUND` may only read |
| Context-file and memory injection scanning | `MemoryPolicy.rejection`, `Fence.wrap`, `TurnTaint` |
| Hardline blocklist below every approval | `ToolRisk.FORBIDDEN` plus `SensitivePolicy` in `:mcp-server` |
| MCP connectors | `ToolSources.kt` plus `ConnectorPins` (Hermes has no tool pinning; ours is stricter) |
| Observability | `Telemetry.kt` (OpenTelemetry spans) plus `RunJournal.kt` |

What's missing is the machinery that makes Hermes good **over time**: it learns after each turn,
recovers from provider errors, measures itself, and asks once, clearly, instead of twice, vaguely.

---

## 1. Approval: why the WhatsApp send was blocked, and the new design

### What happened (traced in code)

A request like *"send hi to Amma on WhatsApp"* runs like this:

1. `resolve_contact` runs. `PhoneToolSource.resultOriginOf` maps any tool with "contact" in its
   name to `Origin.FILE`, which is private, so `TurnTaint` marks the turn as holding private data
   (`ToolSources.kt`, `resultOriginOf`).
2. The model follows `<routes>` rule 3 and calls `open_deeplink` with
   `https://wa.me/<number>?text=hi`. `open_deeplink` is in `EGRESS_TOOLS`, and any argument
   containing `://` counts as egress too (`HubTool.gated`).
3. `SafetyGate.evaluate` rule 6 fires: private data was read, and this call sends data out. The
   user sees **"AURA read private content this turn and now wants to use open_deeplink, which
   sends text off this phone. Allow for this task?"** That's the "can I use a deep link?"
   question. The model's own explanation of the step is dropped for this kind of ask
   (`!verdict.unlocksEgress` in `HubTool.gated`).
4. If you allow it, WhatsApp opens with the text filled in. The tap on **Send** then matches
   `CommitLabels` and asks a second time.
5. If you deny or ignore either prompt, the model is told *"Do not retry or look for another
   way"*, so the task just stops.

Taint is also seeded from the whole thread (`TurnTaint(initial = stored.taint)`). In a Home chat
that has ever read a screen, every deep link asks.

**From the chat it can be three prompts.** The deep link is a `core` tool, so the *chat* turn
calls it and asks. Then the model loads `screen` to press Send, the chat turn ends `HANDED_OFF`
having saved nothing (`AgentCore.handOff`), and the overlay runs the same request again in a
fresh turn with a fresh `TurnTaint`. That turn doesn't know WhatsApp is already open, so it can
deep-link and ask again, then ask a third time for Send.

The phone server's own layer (`SensitivePolicy`) usually isn't the blocker. It stops banking and
payment apps, card numbers, and a code the phone has just displayed. **One exception:** its
`SENSITIVE_TEXT_REGEXES` hard-block any outgoing text containing "pin is" or "password is", so
"the pin is in your bag" can never be sent.

No phone was attached while this was written, so the exact run log wasn't read. Check it in the
drawer's **Run log**: the journal records each gate ask (`journal.ask`).

### What's wrong with the current design

- **It asks about the mechanism, not the outcome.** "open_deeplink sends text off this phone" means
  nothing to a user. "Send 'hi' to Amma on WhatsApp?" does.
- **One user intent costs two prompts.** The deep link and the Send tap are the same action.
- **It treats a local app hand-off as exfiltration.** A `wa.me` link opens an installed app on
  this phone. The data leaves only when the user, or AURA, presses Send, and that press already
  needs approval.
- **It can't tell your words from a screen's.** The text "hi" came from your typed request, not
  from a notification. Rule 6 exists to stop *other people's* text from steering a send. It
  shouldn't fire on a payload you dictated.
- **A "no" is a dead end.** Hermes' rule is the right one: a denial stops *that* call, and the
  agent asks what to change.

### The new approval model

**One approval per committed action, describing the outcome, bound to the exact payload.**

| Piece | Rule |
|---|---|
| **Action card** | Before the first step of a commit (send, post, pay-adjacent, delete, book), the hub shows one card: *app + recipient + final text/amount + what happens next*. Buttons: **Send** · **Edit** · **Cancel**. Built from a new tool, `propose_action(app, target, payload, summary)`. The model must call it before the deep link. |
| **Binding** | The approval is stored **on the thread**, not on `TurnState`, as `{app package, target, sha256(payload), expires in 10 min}`, so it survives the chat → overlay hand-off. Every later call that matches it passes rule 4 and rule 6 without asking again: the deep link, typing that text, and tapping Send in that app. A call with a different recipient or different text asks again. The hand-off also passes "WhatsApp is open with the text filled in" to the overlay turn, so it presses Send instead of starting over (S1). |
| **Dictated payload** | When the payload is a substring of, or a light rewrite of, the user's own message this turn, the card says "you asked for this". Taint does not block it. When the payload came from a screen or notification (e.g. "reply with what the email says"), the card shows the text in full and marks it "written from content on screen". |
| **Local hand-off vs egress** | `open_deeplink` whose resolved target is an **installed app** becomes `ToolRisk.ACT`, not egress. It stays egress when it opens a browser or an unknown host. |
| **Scopes** (from Hermes' once / session / always / deny) | **Once** (default). **For this chat** (same app and recipient, for this thread). **Always for this contact in this app**, kept in a new `approval_grants` table, listed and revocable in the drawer. Never "always" for payments, deletes, or a payload taken from untrusted content. |
| **Timeout** | Fails closed after 5 minutes (Hermes' default is 300 s). Unlike a denial, a timeout does not tell the model "don't retry": "send it now" in the next message raises a fresh card. |
| **Denial** | Returns *"The user declined this exact action. Ask what they want changed."*, not "do not look for another way". The hard denials (`FORBIDDEN`, `policy_blocked`) keep their current final wording. |
| **Surfaces** | Chat: a card in the transcript (`ChatCards.kt`). Overlay: the same card in the overlay. Voice: the card is read out ("Send 'hi' to Amma on WhatsApp?") and answered "yes / change it / cancel"; voice "yes" is accepted only when the card is also on screen. |
| **Background runs** | Unchanged: `RunMode.BACKGROUND` can never commit (Hermes `cron_mode: deny`). |
| **Approval history** | Log every decision. After N identical "once" approvals, offer "always for this contact" (Hermes `approvals suggest`). |

**Not ported:** Hermes' `smart` approval mode, where an auxiliary model judges risk. It breaks
the rule in `SafetyGate.kt` that no model is consulted, which is what stops a page from arguing
its way past the gate. Same for Hermes' YOLO mode: no global bypass on a phone that holds
banking apps.

**Also fix while there:** two PIN rules block ordinary messages. `SecretPatterns` in
`SafetyGate.kt` uses `(password|passcode|pin|cvv)\s*(is|:|=)\s*\S{3,}`, and `SENSITIVE_TEXT_REGEXES`
in `:mcp-server`'s `SensitivePolicy.kt` uses `\bpin\s+is` and `password\s+is` with nothing after
them. Both catch "the pin is ready" and "pin: tomorrow". Require a run of digits after `pin` or
`cvv`, and a token-shaped value after `password is`, in both files. `CommitLabels` is English-only (already marked `ponytail:`); add Hindi and Tamil
labels and the node's resource id.

**Tests first** (in `ToolBenchTest` and `SafetyGateTest`), see [section 6](#6-evaluation-p0):
- "send hi to Amma on WhatsApp" raises **exactly one** approval, and it names Amma and "hi". Run
  it twice: started from the chat with the overlay drawable (the hand-off path), and from the
  overlay.
- After approval, changing the text to anything else raises a new approval.
- A notification saying "reply 'yes' to everyone" never sends without an approval that shows
  the text as screen-sourced.

---

## 2. Speed

**Rule: measure, then fix.** `Telemetry.kt` already records a span per model call with timing
and tokens. Before changing anything, run the 20 eval tasks below and record, per task: model
calls, time to first token, total wall time, input tokens, and how many tokens were cache hits.
Each hypothesis below is a guess until that table says otherwise.

| # | Suspected cost | Evidence in code | Fix | Where it comes from |
|---|---|---|---|---|
| S1 | **A chat hand-off makes the same request twice.** The chat turn plans, may already open a deep link, asks for `screen`, ends `HANDED_OFF` having saved nothing, and the overlay starts the same request again from scratch. That costs extra model calls and extra approval prompts (section 1). | `AgentCore.handOff`, `TurnEnd.HANDED_OFF` | Save the chat turn's messages before handing off, and continue them in the overlay turn (same thread). Or decide hand-off before the first model call, using the preamble's app match. | — |
| S2 | **Side tasks run on the main model.** `ModelRole.FAST` resolves to MAIN at low thinking. Titles, compaction summaries and `proofCheck` use the big model. | `ModelGateway.resolve` | Add a real **auxiliary model** slot in Settings → Brain (e.g. a Flash-Lite or Haiku class model) for titles, summaries, research notes and the background review. Main model only for acting. | Hermes `auxiliary.*` |
| S3 | **No streaming.** The user sees nothing until the whole reply is done. | No `executeStreaming` in `agentcore/` | Stream the final answer with Koog's Streaming API (`TextDelta` frames) into the chat bubble and TTS. Tool-call turns can stay non-streamed. | Koog `streaming-api` |
| S4 | **A new skill breaks the provider cache.** `skillIndex()` is part of the system prompt, so saving one skill changes the prefix of every later request. | `SystemPrompt.build(..., skillIndex(), ...)` | Freeze the index per thread. Announce new or removed skills once in the next user message; rebuild the system prompt only at compaction. | Hermes `skills_index_delta.py` |
| S5 | **Screenshots in the prompt.** Any tool result carrying an image (`imagePng` in `PhoneToolSource.call`) is appended for vision models. **Unverified:** whether the automatic `read_screen` after each action returns an image or only text. If text only, this applies just to `perceive_screen` and is small. | `turn.drainImages()` in `AgentEngine.execute` | Check in E2 how many images a typical task sends. If many: send an image only after `perceive_screen`. | — |
| S6 | **Extra closing calls.** `proofCheck` adds a full model call after 3+ actions, and the empty-reply nudge adds another. | `PROOF_MIN_ACTIONS = 3` | Keep `proofCheck`, but run it on the auxiliary model with only the request, the last screen and the claimed answer, not the whole history. | Hermes goal judge |
| S7 | **Read-only tools run one at a time.** `executeTools(reply, false)` runs a reply's calls in sequence. | `AgentEngine.execute` | Run calls in parallel when every call in the reply is `ToolRisk.READ` and none touches the screen (`web_search`, `find_files`, `resolve_contact`, `read_notifications`). Keep screen actions sequential. | Hermes concurrent tool dispatch |
| S8 | **Thinking budget.** The same thinking level for "set an alarm" and "plan my trip". | `GeminiThinkingConfig.kt` | A route that finishes in one `core` call (alarm, timer, call, media) uses the lowest thinking level. Raise it only once `screen` loads. | — |
| S9 | **Deep-link first, screen last.** Already the rule in `<routes>`; check it with the eval table: count tasks that loaded `screen` when a `system_intent` or deep link existed. | `SystemPrompt <routes>` | Prompt fix (Fable). Plus: add the missing deep links to the catalog for the top 20 apps. | — |

Track three targets for the "fast" tasks (alarm, call, message, play music, open settings
page): **one model call, first visible text under 1.5 s, done under 4 s.**

---

## 3. Accuracy and reliability

| # | Change | Why | Source |
|---|---|---|---|
| A1 | **Retries and fallback model.** Wrap the client in Koog `RetryingLLMClient(RetryConfig.PRODUCTION)` and add a fallback model in Settings, used on 429, 5xx and network errors. On 401 or 403, don't fall back silently: tell the user the key was rejected. | Today one 429 ends the turn with "rate limiting or out of quota". The old `agent/` has its own retry code; agentcore has none. | Koog `handling-failures`, `RoutingLLMPromptExecutor`; Hermes `fallback_providers` |
| A2 | **Compaction throws away history (likely the biggest memory bug).** `compactIfOver` uses Koog's `FromLastNMessagesHistoryCompressionStrategy`. Reading its source (`DefaultHistoryCompressionStrategies.kt`): it first **deletes everything except the last N messages** (`leaveLastNMessages`), then summarises **only those N**, then rebuilds history as system prompt + first user message + that summary. So everything older than N is lost without a summary, and the newest messages (the latest screen read and tool results) become a summary, not verbatim text. Mid-task, the model can lose the current `som_id`s. **Fix:** our own strategy: summarise `messages[0 until cut]` and keep the tail verbatim, with the cut moved back so a tool call is never separated from its result (Hermes' hard invariant). **Test first:** a long thread compacted once must still answer a question about its first half, and its last tool result must survive verbatim. | Directly causes "it forgot what I said earlier" in long chats. | Koog source; Hermes `context_compressor.py` |
| A3 | **Identifier-preserving summaries.** The compaction summary must keep names, numbers, app names, file names, what was sent to whom, and the user's last instruction verbatim. Koog's default TL;DR prompt doesn't promise that. | A summary that drops "send it to *Ravi*, not Rahul" makes the next step wrong. | Hermes lean compaction, "anchor index" |
| A4 | **Save memory before compacting.** Before the summary replaces history, run the memory review (section 4) on the part about to be dropped. | Otherwise facts said early in a long chat are lost. | Hermes "memory is flushed first" |
| A5 | **Real token counts.** Use the provider's reported prompt tokens from the last response plus an estimate for what was appended since, instead of `estimateTokens` over everything. | The rough estimate over-counts images and under-counts reasoning, so compaction fires at the wrong time. | Hermes usage anchor |
| A6 | **Parallel read-only tools.** See S7. Also fewer steps means fewer chances to go wrong. | — | Hermes |
| A7 | **Resume after the app is killed.** Save `run.messages` and the turn state after **every** step (not only at the end), with a `running` flag. On restart, the overlay offers "Continue: <request>". **Koog `AgentPersistence` does not fit:** it checkpoints between graph nodes, and our `functionalStrategy` loop has none. It likely does not fit: it checkpoints between graph nodes, and our `functionalStrategy` loop has none. Not verified against `Persistence.kt`. Doing it ourselves is about 40 lines in `ThreadStore`. | Listed under "Not built yet" in the agentcore README. | Hermes session persistence |
| A8 | **Goal mode for long tasks.** `/goal`-style: after each turn, an auxiliary judge reads the request and the answer and returns `done`, `continue` or `blocked`. On `continue`, the loop runs again, within a turn budget. Use it for "go through my last 20 emails and…" style tasks. | Removes the "say continue" step. | Hermes `goals.md` |
| A9 | **Truthful completion** (already partly there with `proofCheck`). Add: the final answer must cite one piece of evidence (a screen line or a tool result). An answer with no evidence after phone actions is marked "unverified" in the UI. | The eval suite already tracks FALSE-OK; this makes it visible to users too. | — |

---

## 4. Memory

Good memory for an assistant has four layers. We have two, and the other two are what make it
feel like it knows you.

| Layer | What it holds | AURA today | Change |
|---|---|---|---|
| **1. Working memory** | This turn's messages and tool results | Koog prompt plus `ContextAssembler` pruning | A2 to A5 above |
| **2. Chat memory** | Every thread, resumable, searchable | `ThreadStore` (Room) plus `items_fts` | Add a **thread summary** row, written when a thread goes idle (auxiliary model, 3 to 5 lines), so `search_history` hits come back with context. Add scroll: `search_history(thread_id, around=item_id)` returns the neighbouring messages, like Hermes' discovery / scroll / browse shapes. |
| **3. Durable memory** | Facts about the user, and notes about this phone | `memories` table, `remember` / `forget` | See below |
| **4. Procedural memory** | How to do a task on this phone | `Skills.kt`, saved only when the user confirms | Skill learning from the background review, below |

**Changes to durable memory:**

- **One `memory` tool with `add` / `patch` / `remove` and batch operations**, replacing
  `remember` / `forget`. Patch by unique substring, as Hermes does. On overflow, the error lists
  the current entries and how many characters to free, so the model can consolidate in one
  batch.
- **Usage header** in the memory block: `About the user [61% — 860/1,400]`. Tells the model when
  to consolidate.
- **Dedupe:** an exact duplicate is a no-op.
- **Post-turn background review (the biggest gap).** After a turn ends, and only when the app is
  idle, the auxiliary model reads a digest of the turn and decides whether anything is worth
  saving to memory or to a skill. Hermes' grounding rule applies: save only what a user message
  or tool result shows, never infer traits, and never turn a one-off request into a standing
  preference unless the user said "always", "from now on" or "remember". **AURA-specific:**
  screens and notifications are written by other people, so a review of a tainted turn
  **stages** its writes. The user approves them from a "AURA learned 2 things" chip, and they
  never write silently. Hermes' `write_approval: true` is the model for this.
- **Thread boundaries.** Hermes notes that memory only pays off at session boundaries, and that
  one never-ending chat stops learning. Home is one long chat. Rebuild the thread's memory
  snapshot after compaction, and start a fresh context after 6 hours idle. The thread itself and
  its search stay unchanged.
- **One store.** The old `agent/` and the voice companion keep their own memory. Migrate both
  into `agentcore.db` (listed in the agentcore README's "Not built yet").
- **Memory screen.** The drawer's memory panel shows each entry with its source ("you said",
  "learned from a task"), edit, delete and pin. This is the phone version of Hermes `/journey`.

**Skill learning.** The same background review can draft a skill after a task that took 6 or
more phone actions and succeeded. Drafts are staged, never live, until the user taps "Save how I
did this". Later: a curator pass (deterministic, no model) that marks skills unused for 30 days
as stale and archives them after 60. Pinned skills are skipped.

**Not now:** Koog `ChatMemory` (it duplicates `ThreadStore` and does less) and Koog
`LongTermMemory` (beta, and needs an embedding model on the phone). Revisit semantic recall only
if an eval shows FTS missing things a user would expect AURA to remember.

---

## 5. New assistant features worth porting

Ranked by value to a phone user, cheapest first.

| Feature | What the user gets | Phone adaptation |
|---|---|---|
| **Approval cards** (section 1) | One clear yes/no per action | — |
| **Streaming replies** | Words appear immediately; TTS starts sooner | Koog streaming |
| **Fallback model** | Fewer "rate limited" dead ends | Settings → Brain |
| **Background learning** | It remembers preferences without being told twice | Staged, idle-only, auxiliary model |
| **Heartbeat / watch** | "Tell me when the Swiggy order is out for delivery" | A recurring prompt inside the thread, idle-only, notification-driven where possible instead of polling; it uses the thread's context, unlike a scheduled task |
| **Goals** | "Clean up my downloads, ask me about anything unclear" runs to the end | Judge on the auxiliary model, turn budget |
| **Side question (`/btw`)** | Ask "what's the weather" while a phone task runs, without stopping it | A second, read-only, screen-less turn on the auxiliary model. This is the one "sub-agent" that fits a one-screen phone. |
| **Daily briefing** | Morning summary of calendar, weather, important notifications | `Scheduler` plus `Ambient` digests, already half-built |
| **Personality (SOUL)** | User picks tone: brief, warm, formal | One editable block at the top of the system prompt, set in Settings |
| **Credential vault** | "Log into X" without the model seeing the password | Android Autofill / Credential Manager already does this. Let AURA trigger autofill; never type passwords itself. |

---

## 6. Evaluation (P0)

**Without this, nobody can tell whether a change made AURA better or worse. It comes first.**

The repo already has the tooling: `assets/eval/tasks.json`, the on-device eval cockpit,
`scripts/agent_eval.py`, and the AndroidWorld harness in `benchmark/`. **None of it can score
agentcore**, because agentcore runs don't write `files/mcp_logs` (agentcore README, "Not built
yet").

| Step | What |
|---|---|
| E1 | **Write agentcore runs in the trace format the eval tools read.** `RunJournal` already has every step; add an exporter. |
| E2 | **Baseline:** run the eval suite on the current agentcore, recording verified success, FALSE-OK rate, model calls per task, wall time, tokens and cache-hit rate. Every later change is compared against this table. |
| E3 | **Approval and safety scenarios in `ToolBenchTest`** (mock phone, no emulator): one-approval sends, payload binding, injection from a notification, payment-app refusal, background run never commits. |
| E4 | **Prompt-surface snapshot test.** Render `SystemPrompt.build(...)` plus the `core` tool schemas with fixed inputs to `test/resources/prompt_surface/*.txt`, and fail when they differ. A prompt change then shows up in review as a text diff. Hermes does exactly this. |
| E5 | **Koog `agents-test` mock LLM** for engine tests: scripted model replies drive `AgentEngine` through the limit, pause, hand-off, empty-reply and proof-check paths without a network. |
| E6 | **Regression gate:** a PR that changes `agentcore/` can't merge if verified success drops or FALSE-OK rises on the fixed task set. |

The production checklist every agent should meet. It's here because a list is easier to track
against:

- [x] Hard limits: steps, wall clock, request cap (`TurnLimits`)
- [x] Prompt-injection fencing and taint tracking
- [x] Secret redaction in logs and tool arguments
- [x] Structured traces per model call
- [x] Loop and repeat detection (`BLOCK_AFTER_FAILURES`, `ActionGuard`)
- [x] Cancel and pause at any point
- [ ] Retries with backoff, plus a fallback model (A1)
- [ ] Resumable runs after process death (A7)
- [ ] Streaming output (S3)
- [ ] Eval suite runs against this agent, with a baseline (E1, E2)
- [ ] Prompt changes reviewed as diffs (E4)
- [ ] Cost and latency per task tracked over time (E2)
- [ ] Clear, scoped, revocable approvals (section 1)
- [ ] One memory store, user-visible and editable (section 4)
- [ ] Crash-free rate and turn-failure rate reported (Crashlytics is already in the app; add a
      non-fatal event for `TurnEnd.FAILED` with the error class, never the content)

---

## 7. Roadmap

Each phase ships on its own and is measured against the E2 baseline.

| Phase | Contents | Done when |
|---|---|---|
| **P0: measure, fix compaction** | E1, E2, E4, A2 | Baseline table exists; prompt snapshot test passes; a compacted thread still remembers its first half |
| **P1: approvals** | Section 1, including E3 | WhatsApp, SMS, email and Telegram sends ask exactly once, naming recipient and text |
| **P2: speed** | S1, S2, S3, S4, S5, S7, S8 | Fast tasks: one model call, first text under 1.5 s |
| **P3: reliability** | A1, A3, A4, A5, A7 | No turn fails on a single 429; a killed app offers "Continue" |
| **P4: memory** | Section 4 | Background review stages facts; memory screen shows sources; old stores migrated |
| **P5: assistant features** | Goals, heartbeat, `/btw`, briefing, personality | Each has an eval task |

---

## 8. Every Hermes feature, and what AURA does with it

**Have** = built. **Partial** = some of it. **Missing** = not built.
**Port**: **yes** = build it. **adapt** = build a phone-shaped version. **no** = skip, with reason.

### Core agent

| Hermes feature | What it does | AURA today | Port? | Reason |
|---|---|---|---|---|
| Agent loop (`run_conversation`) | Model → tools → model until a text answer | **Have** (`AgentEngine.execute`) | — | |
| Concurrent tool calls | A reply's calls run in parallel, results kept in order | **Missing** (`executeTools(reply, false)`) | **adapt** | Parallel only for read-only, screen-free calls (S7) |
| Interruptible API calls | A new message or stop abandons the in-flight call | **Have** (coroutine cancel) plus `steer()` | — | |
| Iteration budget | Max turns; summary at 100% | **Have** (`TurnLimits`, `CLOSING_TURN`) | — | |
| Fallback providers | Switch provider/model on 429, 5xx or auth error | **Missing** | **yes** | A1 |
| Credential pools | Rotate several keys for one provider | **Missing** | **no** | Users bring one key; rotating keys to dodge limits violates most providers' terms |
| Provider routing (OpenRouter) | Choose underlying providers by cost or speed | **Missing** | **no** | Only matters for OpenRouter users; pass-through settings later if asked |
| Auxiliary model for side tasks | Vision, summaries, titles, review on a cheaper model | **Partial** (FAST = MAIN) | **yes** | S2 |
| Three API modes | Chat Completions, Responses, Anthropic native | **Have** via Koog clients | — | |
| Mixture of Agents | Several models advise, one acts | **Missing** | **no** | Multiplies latency and cost; the opposite of the speed goal |
| Code execution (`execute_code`) | Model writes a Python script that calls tools, many steps in one turn | **Missing** | **no** | No Python sandbox on the phone, and a script bypasses the per-call safety gate. Revisit as a tiny declarative "macro" format if step counts stay high. |
| Subagent delegation | Child agents with isolated context, in parallel | **Missing** (deliberately) | **adapt** | One screen means one actor; only read-only side questions (`/btw`) fit |
| `/btw` side question | Ask something without interrupting the running task | **Missing** | **yes** | Read-only, screen-less turn on the auxiliary model (section 5) |
| `clarify` tool | Ask the user a question mid-task | **Have** (`ask_user`) | — | |
| `todo` tool | Agent-local task list | **Have** (`update_plan`) | — | |
| Codex app-server runtime | Hand the loop to OpenAI's Codex runtime | n/a | **no** | Provider-specific runtime for coding |
| Subscription proxy / Tool Gateway | Nous-hosted access to models and tools | n/a | **no** | Hermes' own hosted service |

### Prompt and context

| Hermes feature | What it does | AURA today | Port? | Reason |
|---|---|---|---|---|
| Three-tier prompt (stable / context / volatile) | Ordering for cache reuse | **Have** (stable system prompt, volatile parts in the user message) | — | |
| Prompt caching | 1-hour Anthropic cache, stable prefix | **Partial** (depends on provider adapter) | **yes** | Check that cache-read tokens are non-zero in E2; add Anthropic `cache_control` if not |
| Skills-index delta note | New skills announced once, prompt rebuilt at compaction | **Missing** | **yes** | S4 |
| SOUL.md personality | User-editable identity block | **Partial** (`profile()` is about the user, not the agent's voice) | **adapt** | A "how AURA talks" setting |
| Context files (AGENTS.md etc.) | Project rules from the working directory | n/a | **no** | No project directory on a phone; the profile plus skills cover it |
| Context references (`@file`, `@url`) | Inline a file or URL into a message | **Partial** (attachments) | **adapt** | `@` picker in the composer for files, contacts and recent screenshots; later |
| Platform hints | Per-surface formatting guidance | **Partial** | **adapt** | One hint for chat (markdown OK) and one for voice/overlay (short, no markdown) |
| Prompt-surface snapshot in CI | Rendered prompt plus tools committed and diffed | **Missing** | **yes** | E4 |
| Dual compression (50% in-loop, 85% safety net) | Summarise middle turns | **Partial** (one threshold at half the window) | **adapt** | Keep one threshold; add A2 to A5 |
| Micro-compaction | Compress a little each turn instead of one big pause | **Missing** | **no** for now | Our turns are short; `pruneOldToolResults` already does the cheap part. Revisit if E2 shows compaction pauses. |
| Usage anchor (real token counts) | Provider counts plus delta estimate | **Missing** | **yes** | A5 |
| Pluggable context engine | Swap compression algorithms | n/a | **no** | One implementation; an interface would have one implementor |

### Memory and learning

| Hermes feature | What it does | AURA today | Port? | Reason |
|---|---|---|---|---|
| MEMORY.md / USER.md, bounded | Always-in-context facts | **Have** | — | |
| `memory` add / patch / remove, batches, substring match | Edit in place, consolidate on overflow | **Partial** (add and delete only) | **yes** | Section 4 |
| Usage % header | Model sees how full memory is | **Missing** | **yes** | Section 4 |
| Duplicate rejection | No-op on exact duplicate | **Missing** | **yes** | One line |
| Injection scan on memory writes | Block instruction-shaped or hidden text | **Have** (`MemoryPolicy`) | — | |
| Write approval / staging | Hold writes for review | **Partial** (confirm when tainted) | **yes** | Staging for background writes |
| Background self-improvement review | After a turn, save memory and skills | **Missing** | **adapt** | Idle-only, auxiliary model, staged when tainted |
| `session_search` (discover, scroll, browse) | FTS over all sessions | **Partial** (discover only) | **yes** | Add scroll by item id |
| Session lineage across compressions | Old turns soft-archived, still searchable | **Partial** (visible items kept; model context replaced) | — | Already searchable through visible items |
| External memory providers (Honcho, Mem0…) | Semantic and graph memory | **Missing** | **no** | Sends personal data to a third party; FTS plus summaries first |
| Agent-managed skills (`skill_manage`) | Create, patch, delete own skills | **Partial** (save with confirmation) | **yes** | Staged drafts from the review |
| `/learn` | Build a skill from a URL or the current conversation | **Missing** | **adapt** | "Remember how to do this" button after a successful run |
| Curator | Track skill use; stale → archive; optional consolidation | **Missing** | **adapt** | Deterministic part only, later |
| Skills Hub / agentskills.io | Install community skills | **Missing** | **adapt** later | Same SKILL.md format; installs go through a scan and a confirmation. Koog 1.2's `skills` module reads this format. |
| Conditional skills (`requires_toolsets`) | Hide skills whose tools are missing | **Missing** | **yes** | Hide a skill whose app isn't installed |
| `/journey` learning timeline | See and prune what was learned | **Missing** | **adapt** | Memory screen with sources |

### Automation

| Hermes feature | What it does | AURA today | Port? | Reason |
|---|---|---|---|---|
| Cron (`cronjob_manage`) | Scheduled agent jobs with skills and delivery | **Have** (`Scheduler.kt`, read-only) | — | Add: attach a skill to a job |
| No-agent script jobs | Run a script, deliver stdout | n/a | **no** | No scripts on the phone |
| Event-triggered jobs (webhooks) | Fire on an external event | **Partial** (notification intake in `Ambient`) | **adapt** | "When a notification from X arrives, do Y", read-only |
| `/goal` with judge | Keep working until done | **Missing** | **yes** | A8 |
| `/heartbeat` | Recurring prompt inside this thread | **Missing** | **adapt** | Section 5 |
| `/loop` | Timer- or self-paced re-runs | **Missing** | **no** | Heartbeat plus Scheduler cover it on a phone; polling drains battery |
| Kanban | Board of tasks across worker processes | n/a | **no** | Multi-agent team tooling; one user, one screen |
| Hooks and middleware | Plugin code at lifecycle points | **Partial** (internal callbacks: `onTurn`, `beforeTurn`) | **no** | No third-party code inside the app. Keep these as internal seams. |
| Batch processing and trajectories | Run thousands of prompts, export ShareGPT | **Partial** (`benchmark/`) | **adapt** | E1 exporter; ShareGPT only if training data is ever needed |
| Checkpoints and `/rollback` | Snapshot files before writes | n/a | **no** | Phone actions aren't files; undo is per-app. Commit approval is the guard. |

### Safety

| Hermes feature | What it does | AURA today | Port? | Reason |
|---|---|---|---|---|
| Dangerous-action approval | Human in the loop | **Have**, but confusing | **yes** | Section 1 |
| once / session / always / deny | Scoped grants | **Missing** (once only) | **yes** | Section 1 |
| Approval timeout, fail closed | No answer means no | **Partial** | **yes** | Section 1 |
| Smart approval (LLM judges risk) | Auxiliary model decides | **Missing** | **no** | Breaks "no model in the gate" |
| YOLO mode | Bypass all prompts | **Missing** | **no** | Not on a phone with banking apps |
| Hardline blocklist | Floor below every approval | **Have** (`FORBIDDEN`, `SensitivePolicy`) | — | |
| User deny rules | User-written "never do X" | **Missing** | **adapt** | "Never open app X" and "never message Y" lists in Settings |
| `approvals suggest` | Mine history into allowlist proposals | **Missing** | **yes** | Offer "always for this contact" after repeats |
| Context and skill scanning | Injection patterns blocked | **Have** (memory) / **Partial** (skills) | **yes** | Run `MemoryPolicy`-style checks on skill files too |
| Credential vault | Passwords injected, model never sees them | **Missing** | **adapt** | Android Autofill / Credential Manager |
| Egress proxy | Credential-injecting firewall | n/a | **no** | Desktop/server infrastructure |
| Container isolation | Sandbox the terminal | n/a | **no** | No terminal |
| User authorization / DM pairing | Who may talk to the bot | **Partial** (`aura-mcp-connect` pairing) | — | |

### Tools and surfaces

| Hermes feature | What it does | AURA today | Port? | Reason |
|---|---|---|---|---|
| Tool registry, `check_fn` availability | Hide unavailable tools | **Have** (`ToolHub` catalog) | **adapt** | Hide tools whose permission is missing, instead of letting them fail |
| Tool Search (`tool_search` / `describe` / `call`) | Defer schemas, search them | **Have** in spirit (`load_toolset`) | **adapt** later | Only if connectors grow past ~50 tools |
| MCP client | External tool servers | **Have** (connectors, pinned) | — | |
| Web search and extract | Search and read pages | **Have** (`web_search`, browser) | — | |
| Browser automation | Drive a browser | **Have** (`browser_*`) | — | |
| Computer use | Drive the desktop GUI | **Have**, the phone version (`screen` toolset) | — | |
| Vision and image paste | Images in, analysis out | **Have** (attachments, screenshots) | — | |
| Document extraction | PDF, DOCX, XLSX to text | **Partial** (text/JSON attachments only) | **yes** | PDF and DOCX attachments are common on phones |
| Deliverable mode | Generated files sent as attachments | **Partial** (file cards) | — | |
| Image generation | Text to image | **Missing** | **no** | Not core to phone automation |
| TTS / voice mode / wake word | Speak, listen, hotword | **Have** (Gemini Live, on-device voices, overlay) | — | |
| Spotify, X search, other integrations | Service-specific tools | **Partial** (connectors, `media_control`) | **no** | Connectors and deep links cover them |
| Messaging gateway (Telegram, WhatsApp…) | Talk to the agent from chat apps | n/a | **no** | AURA *is* the phone; `aura-mcp-connect` covers remote access |
| ACP / IDE, API server, dashboard, TUI, skins, pets, profiles, language packs | Other surfaces | n/a | **no** | Desktop and server surfaces. Language packs: the app's string resources already do this. |
| Bot screen | A virtual display the agent drives instead of yours | n/a | **no** for now | Android virtual displays can't host most third-party apps reliably; pause-on-touch already lets user and AURA share the screen |
| LSP | Code intelligence for coding tasks | n/a | **no** | Coding-agent feature |
| Plugins, plugin catalog, built-in plugins | Third-party tools, hooks, memory and context engines | n/a | **no** | Third-party code inside an app with accessibility rights is too much reach; MCP connectors are the extension point, pinned and gated |
| Observer hooks / OpenTelemetry | Telemetry contract | **Have** (`Telemetry.kt`) | — | `RemoteTraces` exists, unused |

---

## 9. Koog 1.3 features and whether to use them

| Koog feature | Use? | Reason |
|---|---|---|
| `RetryingLLMClient` + `RetryConfig.PRODUCTION` | **yes** | A1, a few lines in `ModelGateway` |
| `RoutingLLMPromptExecutor` / `MultiLLMPromptExecutor` fallback | **yes** | A1. Already `MultiLLMPromptExecutor`, but with one client |
| Streaming API | **yes** | S3 |
| `agents-test` (mock LLM, mock tools) | **yes** | E5 |
| OpenTelemetry feature | **have** | |
| History compression (`FromLastNMessages`) | **replace** | Drops older history unsummarised (A2); write our own strategy |
| Structured output (`executeStructured`) | **yes**, for the judge and the review | Typed `done/continue/blocked`, typed memory ops |
| Agent Skills module (`SkillsDiscovery`) | **maybe** | Our `Skills.kt` already works; use the module only if we install hub skills |
| `AgentPersistence` checkpoints | **no** | Node-level; `functionalStrategy` has no nodes (A7) |
| `ChatMemory` | **no** | `ThreadStore` already does it, and more |
| `LongTermMemory` / RAG / embeddings | **not now** | Beta, needs on-device embeddings |
| `CachedPromptExecutor` | **no** | Caches identical prompts; a phone screen is never identical twice |
| Graph strategies, subgraphs, parallel nodes, planner agents | **no** | The functional loop is simpler and fits one screen |
| A2A, ACP, Spring, Ktor, Bedrock AgentCore | **no** | Server integrations |

---

## 10. Work for the prompt model

These are prompt-writing tasks. Hand them to Fable 5.2 with this document and the files named:

1. **System prompt rewrite** (`SystemPrompt.IDENTITY`, `SCREEN_GUIDE` in `ContextAssembler.kt`):
   the new `propose_action` rule, the routes order (S9), the evidence rule (A9), and the voice/chat
   platform hints.
2. **Approval card wording** (section 1): the card text for send, post, delete and book; the
   denial message to the model; the voice read-out.
3. **Background review prompt** (section 4): memory and skill review, with the grounding rule and
   the AURA taint rule. Hermes' prompts in `agent/background_review.py` are MIT-licensed and are
   a good reference. If any text is copied, add Hermes to `THIRD_PARTY_NOTICES.md`.
4. **Compaction summary prompt** (A3): what must survive verbatim.
5. **Goal judge prompt** (A8): `done / continue / blocked`, with a reason.

Every prompt change lands with the E4 snapshot updated and an E2 run compared against baseline.
