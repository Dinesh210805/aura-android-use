package com.aura.aura_ui.mcp.log

import kotlinx.serialization.Serializable

/**
 * Phase 10B — on-disk session log schema.
 *
 * Layout per session:
 *   <app-internal>/mcp_logs/<sessionId>/
 *     metadata.json                ← serialised SessionLog
 *     screenshots/<idx>.jpg        ← one raw screenshot per tool call (JPEG from the
 *                                    capture path; older sessions carry .png)
 *
 * The sessionId is `<startMillis>-<random-suffix>` so a `ls` already
 * sorts chronologically. Screenshots are NOT annotated — annotation is
 * the agent's interpretation; the raw image is the audit ground truth.
 */
@Serializable
data class SessionLog(
    val sessionId: String,
    val startedAtMillis: Long,
    var endedAtMillis: Long? = null,
    var endReason: String? = null,
    /**
     * The harness's verdict on an on-device run — success | partial | fail | unverified — beside
     * what the model itself claimed (spec 2026-09-15). Separate from [endReason], which stays a
     * mechanical "how the loop stopped" and which resumability keys on: a run can stop cleanly
     * and still not have done what was asked, and that difference is the whole point of this
     * field. Null for MCP sessions and for runs logged before it existed.
     */
    var outcome: RunVerdict? = null,
    /** Agent identity — the bearer token's label set during pairing (e.g. "Laptop / VS Code"). */
    val agentLabel: String?,
    val tokenId: String?,
    /**
     * Where this session came from:
     *  - "mcp"   = an external agent (Claude Code etc.) driving the device via the MCP server
     *  - "agent" = the on-device Koog agent ([AgentRunLogger])
     *  - "live"  = a spoken conversation with Gemini Live ([LiveConversationLogger])
     * Defaulted so older MCP logs (written before this field existed) still parse as "mcp".
     */
    val source: String = "mcp",
    /**
     * The user goal that started an on-device agent run. Null for MCP sessions, and stamped
     * onto a conversation's session when a spoken request escalates into a phone task.
     */
    var command: String? = null,
    /**
     * The shared system/instruction block, stored **once** per session instead of
     * being duplicated onto every [LlmCall]. Captured from the first LLM call's
     * system message. Null for older logs / MCP sessions. (Trace-v2.)
     */
    var systemPrompt: String? = null,
    /**
     * The run assembly manifest: tools, remote servers, skills, and injected hints.
     * Captured once at run start. Null for older logs / MCP sessions. (Phase 2.)
     */
    var assembly: RunAssembly? = null,
    /**
     * A verified path recorded to the learnings store at run completion.
     * Null when learnings are disabled or when the run did not complete successfully. (Phase 2.)
     */
    var memoryWrite: MemoryWrite? = null,
    /**
     * The run's meters and the limits they ran against, recorded once as the run ends.
     * Null for MCP sessions and for runs from builds before this existed.
     */
    var budget: RunBudgetLog? = null,
    /**
     * Latest snapshot of the run ledger's plan checklist (set_plan / mark_step).
     * Overwritten on every plan mutation so the Logs screen shows live progress.
     * Null for MCP sessions and runs that never set a plan. (Run Ledger, Build 1.)
     */
    var planProgress: PlanProgress? = null,
    val invocations: MutableList<ToolInvocation> = mutableListOf(),
    /** LLM/VLM calls made during the run (on-device agent only). */
    val llmCalls: MutableList<LlmCall> = mutableListOf(),
    /**
     * Everything said out loud, by either side, in timestamp order — the conversation
     * plane's equivalent of [invocations]. Empty for "mcp" and "agent" sessions.
     *
     * Populated for `source = "live"` sessions, written by [LiveConversationLogger]. The
     * renderer sorts them by [Utterance.atMillis] alongside [invocations] and [llmCalls],
     * so if a plane ever writes both into one session they interleave on one clock — no
     * plane does that today.
     */
    val utterances: MutableList<Utterance> = mutableListOf(),
    // ── log v2 (2026-09-23): untrimmed bodies live in files under raw/, referenced here ──
    /** `raw/system-prompt.txt` — the whole system prompt; [systemPrompt] above is its preview. */
    var systemPromptFile: String? = null,
    /** The pre-task research, every step of it. */
    var research: ResearchLog? = null,
    /** What the overlay told the user, in order — the Story view reads this. */
    val narration: MutableList<NarrationBeat> = mutableListOf(),
    /** Real screen size, so a som_id's box (screen pixels) can be mapped onto a smaller image. */
    var screenWidth: Int? = null,
    var screenHeight: Int? = null,
)

/** One step of a tool call's journey: a hook, a server gate, the dispatch, a post step. */
@Serializable
data class PipelineStep(
    /** `pre-hook` · `confirm` · `gate` · `dispatch` · `server-post` · `post-hook` · `failure-hook` · `target` */
    val stage: String,
    /** Which hook / gate / step, e.g. `ActionGuard`, `SensitivePolicy`, `ScreenSettle`. */
    val name: String,
    /** `proceed` · `pass` · `deny` · `refuse` · `confirm` · `rewrite` · `ran` · `threw` · `yes` · `no` · `info` */
    val verdict: String,
    val detail: String? = null,
    val ms: Long? = null,
)

@Serializable
data class ResearchLog(
    val app: String? = null,
    val phrase: String? = null,
    val query: String? = null,
    val steps: List<String> = emptyList(),
    val source: String? = null,
    val note: String? = null,
    val outcome: String,
    val durationMs: Long = 0,
    /** Server steps of the research's own web_search, kept apart from the agent's calls. */
    val serverTrail: MutableList<PipelineStep> = mutableListOf(),
)

/** One line the overlay showed. [kind] picks the icon; [headline] is the plan position at the time. */
@Serializable
data class NarrationBeat(
    val atMillis: Long,
    val kind: String,
    val text: String,
    val headline: String? = null,
    /** The tool call this beat narrates, when there is one — lets the Story view show its crop. */
    val invocationIndex: Int? = null,
)

/**
 * Whether the run did what was asked, and the proof behind that answer (spec 2026-09-15).
 *
 * [claimed] is the model's own `end_session` outcome (null when it never called it); [verdict] is
 * what the harness concluded after counting proof and asking the judge. The two differing is the
 * interesting case, and the reason the eval harness can stop treating every clean stop as a
 * success. [findings] carries each proven item with the screen text behind it, so a run can be
 * audited claim by claim.
 */
@Serializable
data class RunVerdict(
    val verdict: String,
    val claimed: String? = null,
    val why: String? = null,
    val proven: Int = 0,
    val target: Int? = null,
    val deliverable: String? = null,
    val findings: List<ProofItem> = emptyList(),
)

/** One finding: what was found, and the verbatim screen text the harness matched it against. */
@Serializable
data class ProofItem(
    val item: String,
    val quote: String,
    /** Read-results seen when this was recorded. Not an invocation index — see Finding. */
    val afterReads: Int = 0,
)

/**
 * The run ledger's plan checklist state: k of n steps done, plus each step as "status: text".
 */
@Serializable
data class PlanProgress(
    val done: Int,
    val total: Int,
    val steps: List<String> = emptyList(),
)

/**
 * What the run actually spent, against what it was allowed to spend.
 *
 * Recorded because none of it is observable otherwise: the model's context window is persisted in
 * `EncryptedSharedPreferences` (unreadable from adb) and the meters live only in memory for the
 * life of the run. This is the surface that lets a user, or a developer, check on a real device
 * that the limits are the ones the code claims.
 */
@Serializable
data class RunBudgetLog(
    val requests: Int,
    /** The user's opt-in per-run request cap, or null when they set none (the default). */
    val maxRequests: Int? = null,
    /** Counted and reported, but no longer a ceiling — see `RunBudget`. No cap rides beside it. */
    val tokensSpent: Int,
    /** True when [tokensSpent] is the provider's own accounting; false when it is our estimate. */
    val tokensReported: Boolean,
    /** The pre-flight estimate, kept so the gap against the reported figure stays visible. */
    val tokensEstimated: Int,
    /** Prompt tokens served from the provider's cache, which [tokensSpent] does not charge. */
    val tokensCached: Int = 0,
    val elapsedMs: Long,
    val maxWallClockMs: Long,
    val compactThresholdTokens: Int,
    /** The model's published input limit, or null when the provider published none. */
    val contextWindow: Int? = null,
    val retryMaxAttempts: Int = 0,
    val retryWorstCaseSleepMs: Long = 0,
    /** `TOKENS` / `REQUESTS` / `WALL_CLOCK` when a ceiling ended the run; null when none did. */
    val endedByLimit: String? = null,
)

/**
 * The run assembly: how many tools, which remote servers, which skills, and what hints were injected.
 * Recorded once per run at start. (Phase 2.)
 */
@Serializable
data class RunAssembly(
    val localToolCount: Int,
    val remoteServers: List<RemoteServerInfo> = emptyList(),
    val skills: List<String> = emptyList(),
    val injectedHints: String? = null,
)

/**
 * A remote MCP server attached to a run.
 */
@Serializable
data class RemoteServerInfo(
    val name: String,
    val toolCount: Int,
    val instructions: String? = null,
)

/**
 * A verified path recorded to the learnings store.
 */
@Serializable
data class MemoryWrite(
    val goalKey: String,
    val path: List<String>,
)

@Serializable
data class ToolInvocation(
    /** 0-based index within the session. Matches the `<idx>.<ext>` image filename. */
    val index: Int,
    val timestampMillis: Long,
    val toolName: String,
    /** JSON-encoded args (already stringified at the bridge boundary). */
    val argsJson: String?,
    var success: Boolean = true,
    var durationMs: Long = 0,
    /**
     * The tool's response as text. Capped by the logger (see `AgentRunLogger.OUTPUT_MAX_CHARS`)
     * because the whole session is rewritten to disk on every event — when the cap bites,
     * [outputFullLength] records the original size.
     */
    var outputSummary: String = "",
    /**
     * Original character count of [outputSummary] when it was truncated on the way in; null when
     * the stored string IS the whole response.
     *
     * Recorded so the trace can never quietly present a preview as the full payload — a user
     * copying an args/output block to file a bug needs to know if they are holding all of it.
     */
    var outputFullLength: Int? = null,
    /** Same, for [argsJson]. */
    var argsFullLength: Int? = null,
    /** True if a raw screenshot for this call exists (see `rawScreenshotFile`). */
    var hasScreenshot: Boolean = false,
    /**
     * True if a SoM-annotated image (blue=ui_tree / red=omniparser numbered boxes)
     * for this step exists at `som/<index>.png` — the image the model actually saw,
     * as opposed to the raw [hasScreenshot]. (Trace-v2.)
     */
    var somImage: Boolean = false,
    /**
     * Text-SoM of the screen this grounding call returned — see [ScreenCanvas].
     *
     * `perceive_screen` only now: its result is still a positional `e` array (plus an
     * image), which no human reading a trace can turn back into a picture unaided. Null
     * when there was nothing to draw.
     *
     * Derived, never sent to the model: this costs no tokens.
     */
    var screenCanvas: String? = null,
    /**
     * The verbatim `read_screen` grid payload — byte-for-byte what the agent received,
     * never re-derived. `read_screen` has taken no screenshot since it stopped shipping
     * positional JSON, so [screenCanvas]'s re-render doesn't apply to it any more; this
     * field exists so the trace shows the actual string instead of nothing, or a redrawn
     * approximation that can silently drift from what the model actually saw.
     *
     * Set from the FULL tool output, not the length-capped [outputSummary] — a truncated
     * grid parses to a garbled picture instead of a complete one.
     */
    var gridPayload: String? = null,
    /**
     * Normalized gesture kind for screenshot annotation at render time
     * ("tap","double_tap","long_press","swipe","scroll"), or null for non-gesture tools.
     */
    var gestureType: String? = null,
    /** Dispatched gesture coordinates (start point for tap/swipe), for the burned-in marker. */
    var tapX: Int? = null,
    var tapY: Int? = null,
    /** Swipe/scroll end point, when applicable. */
    var tapX2: Int? = null,
    var tapY2: Int? = null,
    /**
     * The origin of this tool: "local" (on-device) or "mcp:<server>" (remote).
     * Null if origin was not captured. (Phase 2.)
     */
    var origin: String? = null,
    /**
     * A pre-hook decision that affected this tool call: Deny/Confirm/Rewrite.
     * Null if no hook intervened or the hook did not prevent execution. (Phase 2.)
     */
    var hookDecision: HookDecisionLog? = null,
    /**
     * The name of a skill loaded via `use_skill` during this tool call.
     * Null if no skill was loaded. (Phase 2.)
     */
    var skillLoaded: String? = null,
    /**
     * Index into [SessionLog.llmCalls] of the turn that asked for this tool, or null when the
     * call was not requested by a logged LLM turn (a nested call, or a run whose first tool
     * precedes any completion).
     *
     * Without it the only way to pair a decision with its reasoning is to sort two lists by
     * timestamp and hope — which breaks exactly when a turn requests several tools at once.
     */
    var llmCallIndex: Int? = null,
    /**
     * Tools this tool called itself. `set_plan` runs its own web search, so an empty list here
     * and a `browser_open` in `memoryWrite.path` used to be the only hint that the run did
     * something the invocation list never mentioned.
     */
    val nestedCalls: MutableList<NestedCallLog> = mutableListOf(),
    /** `raw/tool-<i>.args.json` / `raw/tool-<i>.result.txt` — untrimmed; the fields above are previews. */
    var rawArgsFile: String? = null,
    var rawResultFile: String? = null,
    /** Every hook, gate and post step this call passed through, in order. */
    val trail: MutableList<PipelineStep> = mutableListOf(),
    /** The element a som_id gesture targeted: `left,top,right,bottom` in screen pixels. */
    var targetBounds: String? = null,
    var targetSomId: Int? = null,
)

/** One tool invoked from inside another tool's execution. */
@Serializable
data class NestedCallLog(
    val toolName: String,
    val succeeded: Boolean,
    val detail: String? = null,
)

/**
 * A pre-hook decision affecting a tool call.
 */
@Serializable
data class HookDecisionLog(
    val kind: String, // "deny" | "confirm" | "rewrite"
    val hook: String, // simple class name of the hook (e.g., "ActionGuard")
    val reason: String,
)

/**
 * One LLM (or VLM) round-trip during an on-device agent run — the LangSmith-style
 * span that makes the log a real trace: full prompt in, full response out, with
 * provider/model/tokens/timing. API keys and auth headers are NEVER captured.
 */
@Serializable
data class LlmCall(
    val index: Int,
    val timestampMillis: Long,
    val provider: String,
    val model: String,
    /**
     * The **per-turn input delta** — only the newest request message (the tool
     * result the model is reacting to, or the user goal on turn 0). The shared
     * system block lives once in [SessionLog.systemPrompt]; older history is
     * redundant with the tool timeline and is not stored. (Trace-v2: this field
     * no longer holds the full transcript.)
     */
    val prompt: String,
    val response: String,
    /** Thinking/reasoning text emitted by a thinking model (e.g. Gemini 3.x), when present. */
    val reasoning: String? = null,
    /** Why generation stopped — `stop`, `tool_calls`, `length`, … when reported. */
    val finishReason: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    /** Prompt tokens served from a server-side cache, when the provider reports it. */
    val cachedTokens: Int? = null,
    /** Completion tokens spent on hidden reasoning (thinking models), when reported. */
    val reasoningTokens: Int? = null,
    var durationMs: Long = 0,
    /** A2 diagnostic: first message that differs from the previous request (cache boundary). */
    val prefixProbe: String? = null,
    /**
     * Input-cost accounting. [prompt] is the per-turn delta only, so text alone explained under
     * 30% of a call's `promptTokens`; [toolsBytes] is the schema block resent on every request,
     * which was the bulk of the rest.
     */
    val requestBytes: Int = 0,
    val toolsBytes: Int = 0,
    val toolCount: Int = 0,
    /** `raw/llm-<i>.request.json` / `.response.json` — the exact HTTP bodies. */
    val rawRequestFile: String? = null,
    val rawResponseFile: String? = null,
    /** Null for an agent turn; `research-phrase` for the research's side call. */
    val purpose: String? = null,
)

/**
 * One thing that was said — or one thing that should have been said and was not.
 *
 * The conversation plane previously kept only [com.aura.aura_ui.agent.conversation.SessionTranscript]:
 * in memory, no timestamps, cleared on every new conversation, and — the part that made
 * diagnosis impossible — it dropped empty text on the floor (`if (t.isEmpty()) return`).
 * An empty transcription is not nothing; it is the transcriber failing, which is exactly the
 * kind of gap worth a record.
 *
 * So absences are first-class here. A dropped turn, a transcription that came back blank, a
 * reconnect mid-sentence and a barge-in all get an entry, because "AURA went quiet" and "AURA
 * said nothing and nobody recorded why" look identical afterwards otherwise.
 */
@Serializable
data class Utterance(
    val atMillis: Long,
    /** `user`, `aura`, or `system` (session lifecycle and gap notes). */
    val speaker: String,
    val text: String,
    /**
     * What kind of record this is:
     *  - `speech`      — spoken and transcribed normally
     *  - `text`        — typed, or delivered as text rather than audio
     *  - `interrupted` — barge-in: the speaker was cut off mid-utterance
     *  - `gap`         — something is MISSING: blank transcription, dropped audio,
     *                    a turn that arrived with no text. [text] says what was expected.
     *  - `lifecycle`   — session start/end, reconnect, model switch
     *  - `task`        — the conversation plane handing work to, or hearing back from,
     *                    the action plane
     */
    val kind: String,
    /**
     * False when this record is known to be incomplete — a partial transcription flushed
     * because the turn ended, or audio that arrived after the socket dropped. Distinguishes
     * "this is what was said" from "this is as much as we captured".
     */
    val complete: Boolean = true,
)
