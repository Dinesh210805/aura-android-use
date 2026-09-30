package com.aura.aura_ui.mcp.log

import android.content.Context
import android.util.Base64
import android.util.Log
import com.aura.aura_ui.agent.llm.AgentLlmTap
import com.aura.aura_ui.agent.llm.AgentPerceptionTap
import com.aura.aura_ui.agent.llm.AgentTraceTap
import com.aura.mcp.bridge.CaptureResult
import com.aura.mcp.bridge.ScreenshotBridge
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Forensic logger for the **on-device Koog agent** ([com.aura.aura_ui.agent.AuraAgent]).
 *
 * Writes into the same `mcp_logs/<sessionId>/` store as [McpSessionLogger] — so a
 * single "Logs" screen shows both external-MCP and on-device-agent runs — but tags
 * each session `source = "agent"` and records the user [command] plus every LLM
 * round-trip ([LlmCall]) for a LangSmith-style trace.
 *
 * One run = one session. The agent calls [startRun] before `agent.run(goal)` and
 * [endRun] in its `finally`. Tool starts/ends and LLM calls stream in between.
 *
 * Failures are swallowed (logged only): a logging glitch must never crash an agent
 * run. All disk work happens on an internal IO scope so the agent loop never blocks.
 */
class AgentRunLogger(
    context: Context,
    private val screenshotBridge: ScreenshotBridge,
) {

    private val appContext: Context = context.applicationContext

    /**
     * Server steps that belong to no open agent call — in practice the pre-task research's own
     * web_search, which runs beside the agent. Held until the research record lands.
     */
    private val orphanSteps = mutableListOf<PipelineStep>()

    /**
     * Raw bodies are written only where someone can read them: debug builds and developer mode.
     * They hold every screen's text and each full prompt, tens of MB a run — on an ordinary
     * phone that is storage and private data kept for a view the user cannot open.
     */
    private var rawEnabled = false

    private val rootDir: File = File(context.applicationContext.filesDir, "mcp_logs").apply { mkdirs() }

    /** Own writer, used unless this run is adopted into a conversation's session. */
    private val ownWriter = SessionLogWriter(rootDir, TAG)

    /**
     * The writer this run is actually recording through. Reassigned at [startRun] when the run
     * belongs to a Live conversation, so speech and taps land in one file.
     */
    private var writer: SessionLogWriter = ownWriter

    /** True when the session predates this run — the conversation owns its lifetime, not us. */
    private var adopted = false

    private var pending: ToolInvocation? = null
    // Track the most recent tool invocation and LLM call indices to apply platform events.
    private var lastToolIndex: Int = -1

    /**
     * Tools that don't change the screen — no point capturing a screenshot.
     *
     * The ledger/proof/memory/skill tools are bookkeeping: they never touch the display, so a
     * shot of whatever happened to be showing is a full-frame image of noise. The Spotify run
     * spent 4 of its 9 screenshots on `set_plan`/`mark_step`/`record_finding` alone.
     */
    private val skipScreenshotFor = setOf(
        "end_session", "lookup_app", "get_device_status",
        "set_plan", "mark_step", "record_finding",
        "recall_memory", "save_memory", "forget_memory",
        "list_skills", "use_skill", "set_reminder",
    )

    /** Open a new agent session for [goal]. Safe to call once per run. */
    fun startRun(goal: String, agentLabel: String?) {
        rawEnabled = com.aura.aura_ui.presentation.screens.trace.TraceVisibility.isToggleAvailable(appContext)
        orphanSteps.clear()
        // Install the LLM tap synchronously so the very first chat call is captured.
        AgentLlmTap.resetPrefixProbe()
        AgentLlmTap.sink = { c ->
            onLlmCall(
                provider = c.provider,
                model = c.model,
                prompt = c.prompt,
                response = c.response,
                systemPrompt = c.systemPrompt,
                reasoning = c.reasoning,
                finishReason = c.finishReason,
                promptTokens = c.promptTokens,
                completionTokens = c.completionTokens,
                totalTokens = c.totalTokens,
                cachedTokens = c.cachedTokens,
                reasoningTokens = c.reasoningTokens,
                durationMs = c.durationMs,
                prefixProbe = c.prefixProbe,
                requestBytes = c.requestBytes,
                toolsBytes = c.toolsBytes,
                toolCount = c.toolCount,
                requestBody = c.requestBody,
                responseBody = c.responseBody,
                purpose = c.purpose,
            )
        }
        // Capture the SoM-annotated image the vision strategy re-injects, attaching it to the
        // most recent perception step. This is what the model actually saw (numbered blue/red
        // boxes) — richer than the raw screenshot captured at tool-start.
        AgentPerceptionTap.sink = { bytes -> onSomImage(bytes) }
        // Capture platform-stack activity: run assembly, hook decisions, tool origins, skills, memory.
        AgentTraceTap.sink = { event -> onTraceTapEvent(event) }
        // A run the Live plane asked for joins that conversation's session; anything else opens
        // its own. Claimed once, so a later unrelated run cannot inherit the conversation.
        val conversation = LiveSessionScope.claim()
        adopted = conversation != null
        writer = conversation ?: ownWriter
        // Logged because the two outcomes are indistinguishable afterwards: a run that failed to
        // adopt looks exactly like one the user started from the app's own UI.
        Log.i(TAG, if (adopted) "trace → conversation session (merged)" else "trace → own session")

        if (adopted) {
            // The session already exists and keeps its "live" source: this is a conversation that
            // grew a phone task, not an agent run that grew a conversation. Stamp the goal so the
            // trace names what was asked, and record the handoff as a turn in the story.
            writer.edit { session, dir ->
                File(dir, "screenshots").mkdirs()
                if (session.command.isNullOrBlank()) session.command = goal.take(COMMAND_MAX_CHARS)
                screenSize()?.let { (w, h) -> session.screenWidth = w; session.screenHeight = h }
                session.utterances.add(
                    Utterance(
                        atMillis = System.currentTimeMillis(),
                        speaker = "system",
                        text = "handed to the action plane: ${goal.take(COMMAND_MAX_CHARS)}",
                        kind = "task",
                    ),
                )
            }
            return
        }

        writer.open { sessionId, now ->
            File(File(rootDir, sessionId), "screenshots").mkdirs()
            SessionLog(
                sessionId = sessionId,
                startedAtMillis = now,
                agentLabel = agentLabel,
                tokenId = null,
                source = SOURCE_AGENT,
                command = goal.take(COMMAND_MAX_CHARS),
                screenWidth = screenSize()?.first,
                screenHeight = screenSize()?.second,
            )
        }
    }

    /**
     * Record a tool call beginning. Parses gesture coords for screenshot annotation.
     * [origin] names a caller other than the model (AURA's own read after an action).
     */
    fun onToolStart(toolName: String, argsJson: String?, origin: String? = null) {
        writer.edit { s, dir ->
                val inv = ToolInvocation(
                    index = s.invocations.size,
                    timestampMillis = System.currentTimeMillis(),
                    toolName = toolName,
                    argsJson = argsJson?.take(ARGS_MAX_CHARS),
                    argsFullLength = argsJson?.length?.takeIf { it > ARGS_MAX_CHARS },
                    // The turn that asked for this tool is the newest one logged: the completion
                    // carrying the tool_call is recorded before the tool is dispatched.
                    // Skipping the research's side call, which can land in between.
                    llmCallIndex = s.llmCalls.indexOfLast { it.purpose == null }.takeIf { it >= 0 },
                )
                if (argsJson != null) inv.rawArgsFile = writeRaw(dir, "tool-${inv.index}.args.json", argsJson)
                annotateGesture(inv, toolName, argsJson)
                s.invocations.add(inv)
                pending = inv
                lastToolIndex = inv.index
                // Phase 2: Infer and report tool origin (local vs remote MCP).
                // Proxy tools (list_mcp_tools, use_mcp_tool) are special — they always come from remote.
                val origin = origin ?: when {
                    toolName in setOf("list_mcp_tools", "use_mcp_tool") -> "mcp:remote"
                    // Other tools are assumed local (in-process) unless tagged otherwise elsewhere.
                    else -> "local"
                }
                AgentTraceTap.reportToolOrigin(toolName, origin)
                if (toolName !in skipScreenshotFor) captureScreenshotInto(inv, dir)
        }
    }

    /** Record the matching tool call result. */
    fun onToolEnd(toolName: String, success: Boolean, outputSummary: String, durationMs: Long) {
        writer.edit { _, dir ->
                val inv = pending ?: return@edit
                if (inv.toolName == toolName) {
                    inv.rawResultFile = writeRaw(dir, "tool-${inv.index}.result.txt", outputSummary)
                    inv.success = success
                    inv.outputSummary = outputSummary.take(OUTPUT_MAX_CHARS)
                    inv.outputFullLength = outputSummary.length.takeIf { it > OUTPUT_MAX_CHARS }
                    inv.durationMs = durationMs
                    // som-tap marker fix: som_id gestures carry NO coords in their args —
                    // the server resolves som_id → (x, y) and echoes them in the RESULT.
                    // Backfill from there so the trace still burns the tap crosshair.
                    if (success && inv.gestureType != null && inv.tapX == null) {
                        annotateGestureFromResult(inv, outputSummary)
                    }
                    // Text-SoM for grounding calls. Drawn from the FULL output, not the
                    // capped `inv.outputSummary` — a truncated payload parses to nothing
                    // and the picture would silently vanish on exactly the busy screens
                    // where it is most wanted. Never allowed to fail the trace.
                    if (success && toolName in CANVAS_TOOLS) {
                        inv.screenCanvas = runCatching { ScreenCanvas.render(outputSummary) }.getOrNull()
                    }
                    // `read_screen` (and anything else built the same way) already returns
                    // the exact character-grid text the agent read — store that string
                    // verbatim rather than re-derive a picture from it. Same reason as
                    // above for reading the FULL output: a capped grid is a garbled one.
                    if (success && outputSummary.looksLikeScreenGridPayload()) {
                        inv.gridPayload = outputSummary
                    }
                    pending = null
                }
        }
    }

    /** Record one LLM/VLM round-trip (prompt in, response out). Keys are never passed here. */
    fun onLlmCall(
        provider: String,
        model: String,
        prompt: String,
        response: String,
        systemPrompt: String? = null,
        reasoning: String? = null,
        finishReason: String? = null,
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?,
        cachedTokens: Int? = null,
        reasoningTokens: Int? = null,
        durationMs: Long,
        prefixProbe: String? = null,
        requestBytes: Int = 0,
        toolsBytes: Int = 0,
        toolCount: Int = 0,
        requestBody: String = "",
        responseBody: String = "",
        purpose: String? = null,
    ) {
        writer.edit { s, dir ->
                // Trace-v2: capture the shared system block ONCE (first call that carries it)
                // instead of duplicating it onto every LlmCall.prompt.
                // The research side call has its own one-line system instruction; it must not
                // become the run's system prompt.
                if (s.systemPrompt == null && !systemPrompt.isNullOrBlank() && purpose == null) {
                    s.systemPrompt = systemPrompt.take(SYSTEM_PROMPT_MAX_CHARS)
                    s.systemPromptFile = writeRaw(dir, "system-prompt.txt", systemPrompt)
                }
                val index = s.llmCalls.size
                s.llmCalls.add(
                    LlmCall(
                        index = index,
                        rawRequestFile = requestBody.takeIf { it.isNotEmpty() }
                            ?.let { writeRaw(dir, "llm-$index.request.json", it) },
                        rawResponseFile = responseBody.takeIf { it.isNotEmpty() }
                            ?.let { writeRaw(dir, "llm-$index.response.json", it) },
                        purpose = purpose,
                        timestampMillis = System.currentTimeMillis(),
                        provider = provider,
                        model = model,
                        prompt = prompt.take(PROMPT_MAX_CHARS),
                        response = response.take(RESPONSE_MAX_CHARS),
                        reasoning = reasoning?.take(REASONING_MAX_CHARS),
                        finishReason = finishReason,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        totalTokens = totalTokens,
                        cachedTokens = cachedTokens,
                        reasoningTokens = reasoningTokens,
                        durationMs = durationMs,
                        prefixProbe = prefixProbe,
                        requestBytes = requestBytes,
                        toolsBytes = toolsBytes,
                        toolCount = toolCount,
                    ),
                )
        }
    }

    /**
     * Attach the current screen's SoM-annotated PNG to the most recent perception step
     * (`som/<index>.png`), so the trace shows the numbered blue/red boxes the model saw.
     * Reported by the vision strategy via [AgentPerceptionTap] after it re-injects the image.
     */
    fun onSomImage(somPng: ByteArray) {
        writer.edit { s, dir ->
                val target = somTargetIn(s.invocations)
                if (target == null) {
                    // Never silent: a dropped image used to be indistinguishable from a run that
                    // produced none, which is how the gap below went unnoticed for so long.
                    Log.w(TAG, "SoM image reported with no invocation to file it under — dropped")
                    return@edit
                }
                runCatching {
                    val somDir = File(dir, "som").apply { mkdirs() }
                    File(somDir, "${target.index}.png").writeBytes(somPng)
                    target.somImage = true
                }.onFailure { Log.w(TAG, "Failed to write SoM image for invocation ${target.index}", it) }
        }
    }

    /**
     * Close the run. [reason] is "completed" | "failed" | "cancelled"; [verdict] is the harness's
     * judgement of whether the goal was actually met, which is a different question (spec
     * 2026-09-15) and null when the run never got one.
     */
    fun endRun(reason: String, verdict: RunVerdict? = null) {
        AgentLlmTap.sink = null
        AgentPerceptionTap.sink = null
        AgentTraceTap.sink = null
        if (adopted) {
            // The conversation is still going: closing its session here would end the log
            // mid-sentence and send every later turn into a brand-new one. Record the task's
            // outcome as a turn instead, and leave the session open for [LiveConversationLogger].
            writer.edit { s, _ ->
                // The verdict rides the still-open conversation session too: a spoken task is
                // exactly where "it said done but only did 3 of 10" needs to be reviewable.
                verdict?.let { s.outcome = it }
                s.utterances.add(
                    Utterance(
                        atMillis = System.currentTimeMillis(),
                        speaker = "system",
                        text = "action plane finished: $reason" +
                            (verdict?.let { proofSummaryOf(it) } ?: ""),
                        kind = "task",
                    ),
                )
            }
            adopted = false
            writer = ownWriter
            pending = null
            lastToolIndex = -1
            return
        }
        writer.close {
            it.endedAtMillis = System.currentTimeMillis()
            it.endReason = reason
            verdict?.let { v -> it.outcome = v }
        }
        pending = null
        lastToolIndex = -1
    }

    /**
     * Process a platform-stack event from [AgentTraceTap]: run assembly, hook decisions,
     * tool origins, skill loads, or memory writes.
     */
    private fun onTraceTapEvent(event: AgentTraceTap.Event) {
        writer.edit { s, _ ->
                when (event) {
                    is AgentTraceTap.Event.Assembly -> {
                        val e = event.event
                        s.assembly = RunAssembly(
                            localToolCount = e.localToolCount,
                            // Map the tap's transport DTO → the serializable log type.
                            remoteServers = e.remoteServers.map {
                                RemoteServerInfo(it.name, it.toolCount, it.instructions)
                            },
                            skills = e.skills,
                            injectedHints = e.injectedHints,
                        )
                    }
                    is AgentTraceTap.Event.Budget -> {
                        val e = event.event
                        s.budget = RunBudgetLog(
                            requests = e.requests,
                            maxRequests = e.maxRequests,
                            tokensSpent = e.tokensSpent,
                            tokensReported = e.tokensReported,
                            tokensEstimated = e.tokensEstimated,
                            tokensCached = e.tokensCached,
                            elapsedMs = e.elapsedMs,
                            maxWallClockMs = e.maxWallClockMs,
                            compactThresholdTokens = e.compactThresholdTokens,
                            contextWindow = e.contextWindow,
                            retryMaxAttempts = e.retryMaxAttempts,
                            retryWorstCaseSleepMs = e.retryWorstCaseSleepMs,
                            endedByLimit = e.endedByLimit,
                        )
                    }
                    is AgentTraceTap.Event.ToolOrigin -> {
                        val e = event.event
                        if (lastToolIndex >= 0 && lastToolIndex < s.invocations.size) {
                            s.invocations[lastToolIndex].origin = e.origin
                        }
                    }
                    is AgentTraceTap.Event.SkillLoaded -> {
                        val e = event.event
                        if (lastToolIndex >= 0 && lastToolIndex < s.invocations.size) {
                            s.invocations[lastToolIndex].skillLoaded = e.skillName
                        }
                    }
                    is AgentTraceTap.Event.MemoryWrite -> {
                        val e = event.event
                        s.memoryWrite = MemoryWrite(
                            goalKey = e.goalKey,
                            path = e.path,
                        )
                    }
                    is AgentTraceTap.Event.PlanUpdate -> {
                        val e = event.event
                        s.planProgress = PlanProgress(done = e.done, total = e.total, steps = e.steps)
                    }
                    is AgentTraceTap.Event.Research -> {
                        val t = event.trace
                        s.research = ResearchLog(
                            app = t.app, phrase = t.phrase, query = t.query, steps = t.steps,
                            source = t.source, note = t.note, outcome = t.outcome, durationMs = t.durationMs,
                            serverTrail = orphanSteps.toMutableList(),
                        )
                        orphanSteps.clear()
                    }
                    is AgentTraceTap.Event.Step -> fileStep(s, event)
                    is AgentTraceTap.Event.Narration -> s.narration.add(
                        NarrationBeat(
                            atMillis = System.currentTimeMillis(),
                            kind = event.kind,
                            text = event.text,
                            headline = event.headline,
                            // Only a beat about a call belongs to one; the harness's own (research
                            // landed) would otherwise pin an unrelated tap's picture under it.
                            invocationIndex = if (!event.aboutCall) null else (pending?.index ?: lastToolIndex).takeIf { it >= 0 },
                        ),
                    )
                }
        }
    }

    /**
     * File a pipeline step onto the call it belongs to: the OPEN call, when the names match.
     * A step for anything else is the research's web_search (see [orphanSteps]) — filing it by
     * name alone would pin it onto an unrelated, possibly finished, agent call.
     */
    private fun fileStep(s: SessionLog, e: AgentTraceTap.Event.Step) {
        val step = PipelineStep(e.stage, e.name, e.verdict, e.detail, e.ms)
        val inv = pending?.takeIf { it.toolName == e.toolName }
        if (inv == null) {
            s.research?.serverTrail?.add(step) ?: orphanSteps.add(step)
            return
        }
        inv.trail.add(step)
        if (e.stage == "target") {
            inv.targetSomId = e.name.toIntOrNull()
            inv.targetBounds = e.detail
        }
        // The old single-verdict field, kept filled for readers that predate the trail.
        if (e.stage == "pre-hook" && e.verdict in setOf("deny", "confirm", "rewrite") && inv.hookDecision == null) {
            inv.hookDecision = HookDecisionLog(e.verdict, e.name, e.detail.orEmpty())
        }
    }

    /** Write [text] byte-exact to `raw/<name>`; returns the session-relative path, or null. */
    private fun writeRaw(dir: File, name: String, text: String): String? = if (!rawEnabled) null else runCatching {
        File(dir, "raw").mkdirs()
        File(File(dir, "raw"), name).writeText(text)
        "raw/$name"
    }.onFailure { Log.w(TAG, "Failed to write raw/$name", it) }.getOrNull()

    private fun screenSize(): Pair<Int, Int>? =
        com.aura.aura_ui.accessibility.ScreenGeometry.realSizePx(appContext).takeIf { it.first > 0 && it.second > 0 }

    // ── internals ──────────────────────────────────────────────────────────

    /** Pull gesture coords out of [argsJson] so the renderer can burn a marker on the shot. */
    private fun annotateGesture(inv: ToolInvocation, toolName: String, argsJson: String?) {
        val gesture = when (toolName) {
            "tap" -> "tap"
            "double_tap" -> "double_tap"
            "long_press" -> "long_press"
            "swipe" -> "swipe"
            "scroll_up", "scroll_down", "scroll_left", "scroll_right", "scroll_to" -> "scroll"
            else -> null
        } ?: return
        inv.gestureType = gesture
        if (argsJson.isNullOrBlank()) return
        val obj = runCatching { JSONObject(argsJson) }.getOrNull() ?: return
        inv.tapX = obj.optIntOrNull("x") ?: obj.optIntOrNull("x1") ?: obj.optIntOrNull("startX")
        inv.tapY = obj.optIntOrNull("y") ?: obj.optIntOrNull("y1") ?: obj.optIntOrNull("startY")
        inv.tapX2 = obj.optIntOrNull("x2") ?: obj.optIntOrNull("endX")
        inv.tapY2 = obj.optIntOrNull("y2") ?: obj.optIntOrNull("endY")
    }

    /**
     * Backfill tap coords from the tool RESULT payload (first JSON block of the
     * output). Server-resolved som gestures echo `"x"`/`"y"` (and swipes their
     * endpoints) as strings — org.json's optInt coerces them.
     */
    private fun annotateGestureFromResult(inv: ToolInvocation, outputSummary: String) {
        val firstJson = outputSummary.lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: return
        val obj = runCatching { JSONObject(firstJson) }.getOrNull() ?: return
        inv.tapX = obj.optIntOrNull("x") ?: obj.optIntOrNull("x1")
        inv.tapY = obj.optIntOrNull("y") ?: obj.optIntOrNull("y1")
        inv.tapX2 = obj.optIntOrNull("x2")
        inv.tapY2 = obj.optIntOrNull("y2")
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private suspend fun captureScreenshotInto(inv: ToolInvocation, dir: File) {
        val result = runCatching { screenshotBridge.captureBase64Png() }.getOrNull() ?: return
        if (result !is CaptureResult.Success) return
        runCatching {
            val bytes = Base64.decode(result.base64Png, Base64.DEFAULT)
            // Named for what the bytes ARE: the capture path returns JPEG (see [imageExtensionFor]).
            File(File(dir, "screenshots"), "${inv.index}.${imageExtensionFor(bytes)}").writeBytes(bytes)
            inv.hasScreenshot = true
        }.onFailure { Log.w(TAG, "Failed to write screenshot for invocation ${inv.index}", it) }
    }

    companion object {
        private const val TAG = "AgentRunLogger"
        const val SOURCE_AGENT = "agent"

        /**
         * Grounding calls whose result gets *re-drawn* as a text-SoM ([ScreenCanvas]),
         * because their own output is positional JSON a human can't picture unaided.
         *
         * `read_screen` used to be in this set, back when its result was 180 positional
         * arrays. It no longer is: the tool now returns the character-grid picture
         * itself, so re-deriving a second, different picture from it was both wasted
         * work and — once the tool's output format changed — silently broken (nothing
         * here to parse as JSON). See [looksLikeScreenGridPayload] / `inv.gridPayload`
         * for how its result reaches the trace now: verbatim, not redrawn.
         */
        private val CANVAS_TOOLS = setOf("perceive_screen")
        // Raised from 1.5k/2k (2026-08-17): a perceive_screen result is tens of thousands of
        // characters, so the old caps cut the trace's input/output mid-JSON on nearly every
        // perception step — the block a developer opens the trace FOR. The whole session is
        // rewritten on each event, so these stay bounded rather than unlimited, and whatever
        // the cap does cut is now declared via ToolInvocation.argsFullLength/outputFullLength
        // instead of silently disappearing.
        private const val ARGS_MAX_CHARS = 8_000
        private const val OUTPUT_MAX_CHARS = 24_000
        private const val COMMAND_MAX_CHARS = 500
        private const val PROMPT_MAX_CHARS = 60_000
        private const val RESPONSE_MAX_CHARS = 20_000
        // Trace-v2: the system block is stored once per run (can be large); reasoning is
        // a short thought summary but capped defensively.
        private const val SYSTEM_PROMPT_MAX_CHARS = 20_000
        private const val REASONING_MAX_CHARS = 10_000
    }
}

/** Look tools, in the sense that their own result is the screen the model then reasons about. */
private val SOM_PREFERRED_TOOLS = setOf("perceive_screen", "get_screenshot", "read_screen")

/**
 * Which invocation a reported SoM image belongs to.
 *
 * A waiting look tool wins. But the image also arrives on turns where the model called no look
 * tool at all — the A6 [com.aura.aura_ui.agent.strategy.ScreenAfterAction] observation after a
 * gesture produces one — and anchoring only to look tools silently dropped it on every such
 * turn, which is most of them. Falling back to the newest unfiled invocation files the image
 * against the action whose screen it shows, which is the same thing A6 means by it.
 */
internal fun somTargetIn(invocations: List<ToolInvocation>): ToolInvocation? =
    invocations.lastOrNull { it.toolName in SOM_PREFERRED_TOOLS && !it.somImage }
        ?: invocations.lastOrNull { !it.somImage }

/**
 * Whether [this] is a `read_screen`-shaped character-grid payload rather than plain prose
 * or JSON — mirrors `ScreenPayload.looksLikePayload` on the mcp-server side, which the app
 * module can't reference directly (that object is `internal` to that module).
 */
private fun String.looksLikeScreenGridPayload(): Boolean =
    lineSequence().any { it.startsWith("SCREEN ") }
