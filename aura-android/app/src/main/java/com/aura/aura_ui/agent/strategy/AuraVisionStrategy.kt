package com.aura.aura_ui.agent.strategy

import android.util.Base64
import android.util.Log
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.FromLastNMessagesHistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import com.aura.aura_ui.agent.ledger.LedgerInjection
import com.aura.aura_ui.agent.ledger.RunLedgerController
import com.aura.aura_ui.agent.ledger.RunLedgerRenderer
import com.aura.aura_ui.agent.llm.AgentPerceptionTap
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "AuraAgentSpike"

// Prompt-size estimation lives in [PromptTokenEstimate] (O2: images are counted too).

/**
 * Tools that return a Set-of-Marks (SoM) annotated screenshot. A result from one
 * of these is expected to carry an image; if it does not, that is a real failure
 * (screen-capture revoked, perception broke) and we log loudly rather than let it
 * masquerade as "vision didn't help".
 */
private val VISION_TOOLS = setOf("perceive_screen", "get_screenshot")

/**
 * Marker text on every re-injected screenshot user-message (aids debugging/logs).
 *
 * IMPORTANT — this drives how the model targets elements, and it is the ONE home for
 * "the image is replaced next turn, note the som_id now" (it arrives with the image it
 * describes; `Doctrine` used to restate it on every request, image or not).
 *
 * The gesture tools take `som_id` ONLY — see `DeviceTools.kt`'s `tap` schema, which has
 * no `x`/`y` properties; the server resolves the id to full-resolution coordinates.
 * A previous version of this comment claimed the opposite and is why `ActionGuard`'s
 * block message spent months steering the model back toward raw coordinates.
 */
private const val VISION_REINJECT_NOTE =
    "Current screen, annotated. Each box's number is its som_id — the same numbers as the " +
        "grid. Act by som_id (tap, double_tap, long_press, type_text); never read pixel " +
        "positions off this image. It is replaced next turn. Box colour: blue tap · green text " +
        "field · magenta scrolls · amber toggle · grey nothing declared · red on-device vision guess."

/** User-facing (spoken) message when a run is stopped because it hit its per-run budget. */
internal const val BUDGET_ABORT_MESSAGE =
    "I used up my budget for this task before finishing, so I stopped cleanly rather than " +
        "burning through more. Tell me if you want me to keep going and I'll continue from here."

/**
 * The one turn a budget-exhausted run is allowed. Sent with **no tools attached**, so the model
 * cannot spend it doing more work — its only move is to say what it already knows.
 *
 * Written to make the two honest outcomes equally easy to reach. A closing turn that felt obliged
 * to sound successful would turn every budget death into a false success, which is the exact
 * failure mode the eval's `false_successes` metric exists to catch — so "I did not finish" is
 * named first, and partial findings are asked for explicitly rather than left to inference.
 */
internal const val CLOSING_TURN_PROMPT =
    "This is your final turn and you have no tools. In one or two sentences, tell the user " +
        "what you found or did and what is left, using only what is above. Do not claim anything " +
        "you have not seen, and do not mention budgets or turns."

/** Spoken when the run ran out of TIME rather than tokens — a different truth, so a different line. */
internal const val TIME_ABORT_MESSAGE =
    "This task was taking too long, so I stopped cleanly rather than keep running. Tell me if " +
        "you want me to keep going and I'll continue from here."

/**
 * Thrown by the loop when the per-run budget is exhausted; caught in AuraAgent, which speaks
 * [message]. Which meter tripped is part of the honest answer: "I ran out of budget" and "I ran
 * out of time" are different things to have happened, and the user can act on the difference.
 */
internal class RunBudgetExceeded(limit: RunBudget.Limit? = null) : RuntimeException(
    when (limit) {
        RunBudget.Limit.WALL_CLOCK -> TIME_ABORT_MESSAGE
        else -> BUDGET_ABORT_MESSAGE
    },
)

/**
 * The on-device agent's reasoning loop **with vision re-injection**.
 *
 * This is the standard Koog single-run tool-calling loop (perceive→act→verify is
 * expressed as the model calling tools), with ONE deviation: the stock
 * `nodeLLMSendToolResults` step is replaced by [visionAwareSendToolResults].
 *
 * Why the replacement is necessary: [com.aura.aura_ui.agent.mcpbridge.McpTool]
 * deliberately strips image content from the *text* rendering of every tool
 * result (a base64 PNG is unreadable as text and would overflow the context).
 * The stock loop only ever sends that stripped text back to the model, so the
 * model would be blind to `perceive_screen`'s screenshot. This loop re-injects
 * the screenshot as a proper Koog `image()` attachment on the turn immediately
 * after the tool runs — the only place a vision-capable model actually SEES the
 * SoM-annotated screen. Proven in isolation by `AuraAgent.runVisionSpike` (#80);
 * this wires that proof into the live loop.
 *
 * Graph (six edges — identical topology to Koog's single-run strategy):
 * ```
 *   start ─▶ callLLM
 *   callLLM ─(onToolCalls)─▶ executeTool
 *   callLLM ─(onTextMessage)─▶ finish
 *   executeTool ─▶ sendToolResultsWithVision
 *   sendToolResultsWithVision ─(onToolCalls)─▶ executeTool
 *   sendToolResultsWithVision ─(onTextMessage)─▶ finish
 * ```
 * The thinking-only nudge ([EmptyTurnPolicy]) lives INSIDE the request step, not as extra graph
 * edges: a text turn that is pure reasoning (Gemini 3.x Flash-Lite "thinks out loud" with no tool
 * call) is retried in-place with an act-now nudge, up to [EmptyTurnPolicy.MAX_NUDGES], BEFORE it
 * reaches the routing edges. This keeps the topology (and its clean termination on a real text
 * answer) byte-for-byte the same — an earlier attempt that added a second `onTextMessage` edge
 * shadowed the finish edge and looped every task to the iteration cap.
 */
internal fun auraVisionToolLoopStrategy(
    config: AgentContextConfig,
    budget: RunBudget,
    ledger: RunLedgerController? = null,
    // P1: when the provider says this model cannot read images, the loop must stop SENDING them.
    // Declaring no-vision while still attaching screenshots would 400 every turn with a capability
    // list claiming it could not happen. Defaults to true so unknown keeps today's behaviour.
    visionEnabled: Boolean = true,
    // Defaulted to the shared lock so no call site changes; injectable for tests, which
    // must never touch the process-global store.
    pausedGate: PausedLoopGate = PausedLoopGate(
        isPaused = { com.aura.aura_ui.services.ControlLockStore.shared.isPausedByHuman },
    ),
    // A6 — runs read_screen through the run's hook chain. Null (tests, hosts without a server)
    // keeps the two-call loop: the model looks for itself.
    observeScreen: (suspend () -> CallToolResult)? = null,
): AIAgentGraphStrategy<String, String> =
    strategy<String, String>("aura-vision-tool-loop") {
        // Per-run nudge budgets (the strategy is built once per runSpike) — bounds how many
        // defective turns we retry across the whole run, separately per [TurnDefect].
        val nudges = NudgeBudgets()

        val nodeCallLLM by nodeLLMRequest("callLLM")
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResultsWithVision by
            visionAwareSendToolResults(config, budget, ledger, nudges, pausedGate, visionEnabled, observeScreen)
        // Turn 0's defect guard. Every later turn is requested inside
        // [nodeSendToolResultsWithVision], which nudges in place — but turn 0 comes from Koog's
        // own request node, and its text output used to route STRAIGHT to finish. A first turn
        // that only reasoned, or that wrote its tool call as text, therefore ended the run before
        // the phone was touched. Pass-through (zero LLM calls) when the turn is fine.
        val nodeRepairFirstTurn by repairDefectiveTurn(nudges)

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeCallLLM forwardTo nodeRepairFirstTurn onTextMessage { true })
        edge(nodeRepairFirstTurn forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeRepairFirstTurn forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResultsWithVision)
        edge(nodeSendToolResultsWithVision forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendToolResultsWithVision forwardTo nodeFinish onTextMessage { true })
    }

/**
 * Strategy node: re-request a turn that called no tool and said nothing usable.
 *
 * Exists only for turn 0 — see the call site. Identical policy to the in-place nudging every
 * later turn gets ([requestLLMNudged]); the difference is that turn 0's response already exists
 * by the time we see it, so this classifies what came back rather than wrapping the request.
 *
 * The input is the turn's **text**, not the message: this node sits behind an `onTextMessage`
 * edge, which both narrows the type and settles the classification's other input — a turn that
 * reached here made no tool call, by construction.
 *
 * A healthy turn is re-wrapped as a synthetic [Message.Assistant] and routed straight on to
 * finish, costing zero LLM calls. The wrapper drops the original's response metadata, which is
 * safe here and nowhere else: the only consumer is the `onTextMessage` edge that immediately
 * unwraps it back to this same string, and token accounting is done at the wire
 * ([com.aura.aura_ui.agent.llm.AgentLlmTap]), not from message metadata.
 */
private fun repairDefectiveTurn(nudges: NudgeBudgets) =
    node<String, Message.Assistant>("repairDefectiveTurn") { text ->
        val defect = TurnDefect.of(hasToolCall = false, assistantText = text)
            ?: return@node Message.Assistant(text, ResponseMetaInfo.Empty)
        Log.i(TAG, "🔁 First turn is ${defect.name} — repairing before it can end the run")
        llm.writeSession {
            nudgeWhileDefective(Message.Assistant(text, ResponseMetaInfo.Empty), nudges)
        }
    }

/**
 * Custom strategy node: append tool-result messages, re-inject the current screen
 * as a vision attachment, then request the next LLM turn.
 *
 * Message ordering is load-bearing: a tool-result message must immediately follow
 * its tool call, and an image cannot live inside a tool message — so the image is
 * a *subsequent* user message. Order produced here:
 * `assistant(tool_call) → tool(result) → user(image) → request`.
 *
 * Image hygiene: only the **current** screen is kept. Before appending the new
 * screenshot we drop every previously re-injected screenshot from history (they
 * are stale pixels — the agent's text memory carries forward what it learned).
 * This also keeps us under the model's image cap (Llama 4 Scout: ≤5 images).
 */
private fun visionAwareSendToolResults(
    config: AgentContextConfig,
    budget: RunBudget,
    ledger: RunLedgerController?,
    nudges: NudgeBudgets,
    pausedGate: PausedLoopGate,
    visionEnabled: Boolean,
    observeScreen: (suspend () -> CallToolResult)?,
) =
    node<ReceivedToolResults, Message.Assistant>("sendToolResultsWithVision") { toolResults ->
        // A successful end_session ENDS the run right here: return a synthetic
        // text turn (the tool's own `reason` = the final spoken answer), which
        // the unchanged `onTextMessage → finish` edge routes out. Without this,
        // every end_session cost ≥1 extra LLM call just to say "done" — and
        // small models, seeing the loop continue, invented more verification
        // work (logged WhatsApp run: 3 of 7 LLM calls came after end_session).
        // A guard-DENIED end_session is isError=true → null → loop continues.
        val finalAnswer = toolResults.toolResults.firstNotNullOfOrNull { result ->
            EndSessionShortCircuit.finalAnswerFrom(
                result.tool,
                result.result?.toKotlinxJsonElement() as? JsonObject,
            )
        }
        if (finalAnswer != null) {
            Log.i(TAG, "⏹ end_session succeeded — finishing the loop without another LLM turn")
            return@node Message.Assistant(finalAnswer, ResponseMetaInfo.Empty)
        }

        // A6 — a gesture just changed the screen: look for the model, so this one request both
        // verifies the action and plans the next (see [ScreenAfterAction]). A failed or refused
        // read (sensitive app, bridge down) sends nothing — the model can still look itself.
        val screenAfter = observeScreen
            ?.takeIf {
                ScreenAfterAction.shouldObserve(
                    toolResults.toolResults.map {
                        it.tool to (it.succeeded() || ScreenAfterAction.isStaleRefusal(it.text()))
                    },
                )
            }
            ?.let { observe ->
                try {
                    observe()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "A6: read after action failed — model will look itself: ${t.message}")
                    null
                }
            }
            ?.takeIf { it.isError != true }

        // The progress checkpoint's clock: every call this turn counts, filler included, and the
        // first failure (a refused tap, a rejected step) is the surprise the ledger render below
        // asks about. Counted before that render so it describes this turn, not the last one.
        ledger?.noteTurn(
            calls = toolResults.toolResults.size,
            failure = toolResults.toolResults.firstOrNull { !it.succeeded() }
                ?.let { "${it.tool}: ${it.text().ifBlank { "failed" }.take(160)}" },
        )

        llm.writeSession {
            // 1) Append the tool-result text messages — exactly what the stock
            //    nodeLLMSendToolResults does.
            appendPrompt {
                toolResults.toolResults.forEach { result -> toolResult(result.toMessagePart()) }
            }

            // 1a) A6 — the screen the gesture produced, right after its result. Appended BEFORE the
            //     eviction below so the eviction sees it as the newest look and collapses the rest.
            screenAfter?.content?.filterIsInstance<TextContent>()?.joinToString("\n") { it.text }
                ?.takeIf { it.isNotBlank() }
                ?.let { grid -> appendPrompt { user { text(ScreenAfterAction.block(grid)) } } }

            // 1b) Evict stale perception TEXT every turn: keep only the latest
            //     perceive_screen / read_screen / get_screenshot result; collapse older
            //     ones to a one-line placeholder. This is the text twin of the screenshot
            //     eviction in (3) below and is the core fix for the unbounded-context
            //     rate-limit spiral — an earlier screen's SoM-JSON is dead weight once the
            //     screen changed, but otherwise rides the prompt forever.
            rewritePrompt { p ->
                p.withMessages { messages -> evictStalePerceptionText(messages) }
            }

            // 1c) Run-ledger re-injection: drop last turn's rendered block, append a fresh one.
            //     The ledger is the durable plot (goal, plan progress, facts, dead ends) — because
            //     it is re-rendered every turn from harness state, the eviction/compaction in this
            //     node can never lose it. Kept BEFORE the screenshot so the image stays the last
            //     thing the model sees.
            ledger?.let { controller ->
                rewritePrompt { p ->
                    p.withMessages { messages ->
                        val stale = LedgerInjection.staleLedgerIndices(
                            messages.map { m -> (m as? Message.User)?.textContent() },
                        )
                        if (stale.isEmpty()) messages
                        else messages.filterIndexed { index, _ -> index !in stale }
                    }
                }
                appendPrompt { user { text(RunLedgerRenderer.render(controller.snapshot())) } }
            }

            // 2) Re-inject any screenshot the tools produced as a vision attachment.
            val screenshots = toolResults.toolResults.mapNotNull { result ->
                val bytes = result.screenshotBytesOrNull()
                if (bytes == null && result.tool in VISION_TOOLS) {
                    Log.e(
                        TAG,
                        "Vision node: '${result.tool}' returned no extractable image — " +
                            "vision re-injection FAILED (screen-capture revoked?)",
                    )
                }
                bytes
            } + listOfNotNull(screenAfter?.imageBytesOrNull())

            // O4: a text-only perception this turn (e.g. read_screen) supersedes the visual
            // state without bringing new pixels — drop the old screenshot rather than let
            // the model ground on a screen whose text twin already reads "replaced".
            val turnPerceptionTools = toolResults.toolResults
                .map { it.tool }.filter { it in PerceptionEvictionPolicy.PERCEPTION_TOOLS }
            if (PerceptionEvictionPolicy.shouldDropScreenshot(turnPerceptionTools, screenshots.isNotEmpty())) {
                rewritePrompt { p ->
                    p.withMessages { messages -> messages.filterNot(::isReinjectedScreenshot) }
                }
                Log.i(TAG, "🖼️ Vision node: dropped stale screenshot (text-only perception this turn)")
            }

            if (screenshots.isNotEmpty() && !visionEnabled) {
                // P1: the model cannot read images, so re-injecting one would be a guaranteed 400.
                // The perception tool's TEXT result still reaches the model — element list, som_ids
                // and coordinates — so the run degrades to text-grounded rather than failing.
                Log.i(TAG, "🚫 Vision node: model declares no image support — screenshot not re-injected")
                AgentPerceptionTap.report(screenshots.last())
            } else if (screenshots.isNotEmpty()) {
                // Trace-v2: hand the current screen's SoM-annotated PNG to the run logger so
                // the trace shows what the model actually saw (numbered blue/red boxes), not a
                // raw screenshot. Fail-open: no-op when no run is being logged.
                AgentPerceptionTap.report(screenshots.last())
                // Drop previously re-injected screenshots: keep only the current screen.
                rewritePrompt { p ->
                    p.withMessages { messages -> messages.filterNot(::isReinjectedScreenshot) }
                }
                appendPrompt {
                    user {
                        text(VISION_REINJECT_NOTE)
                        screenshots.forEach { bytes ->
                            image(
                                AttachmentSource.Image(
                                    AttachmentContent.Binary.Bytes(bytes),
                                    "png",
                                    "image/png",
                                    "screen.png",
                                ),
                            )
                        }
                    }
                }
                Log.i(TAG, "🖼️ Vision node: re-injected ${screenshots.size} screenshot(s) into the loop")
            }
        }

        // Estimate the prompt we are about to send. We use a text-length proxy plus a flat
        // per-image charge (O2) rather than a provider usage field so it behaves identically
        // on every provider and model — and prompt size is exactly the quantity that counts
        // against tokens-per-minute limits. Cheap and good enough for the two coarse safety
        // nets below.
        // O9: also note where the newest screenshot sits, so compaction never eats it.
        val (promptTokens, compactKeep) = llm.readSession {
            val textChars = prompt.messages.sumOf { it.textContent().length }
            val imageCount = prompt.messages.sumOf { m ->
                m.parts.count { it is MessagePart.Attachment }
            }
            val tokens = PromptTokenEstimate.estimate(textChars, imageCount)
            val lastImageIdx = prompt.messages.indexOfLast(::isReinjectedScreenshot)
            tokens to PerceptionEvictionPolicy.compactKeepCount(
                messageCount = prompt.messages.size,
                lastImageIndex = lastImageIdx,
                configuredKeep = config.compactKeepLastMessages,
            )
        }

        // Spec 2026-08-05 — a held control lock stops the loop rather than being retried
        // into. On device the refusal read as a transient error, so the model spent five
        // identical get_ui_tree calls and four reworded end_session calls on a phone that
        // was never going to answer. Placed BEFORE the budget charge so a paused turn is
        // free; if the wait times out the run ends through the same clean path as a spent
        // budget, which AuraAgent already speaks honestly.
        if (!pausedGate.awaitResume()) {
            Log.i(TAG, "⏹ Control lock still held after the wait bound — ending the run")
            throw RunBudgetExceeded()
        }

        // Budget: stop the run cleanly once it has spent its cumulative budget (caught in
        // AuraAgent → spoken honestly) rather than grinding through more rate-limited calls.
        // Only the ESTIMATE is charged here — requests and real token totals are counted at the
        // wire (AgentLlmTap → RunBudget.recordWireCall), because this node does not see every LLM
        // call: turn 0 goes through nodeCallLLM, and each nudge re-request loops inside one turn.
        budget.chargeEstimate(promptTokens)
        //
        // 2026-08-26 — a tripped ceiling buys ONE tool-free closing turn before the abort. See
        // [RunBudget.beginClosing] for why: on the 2026-08-26 suite both budget deaths were runs
        // that had already found the answer and were spending their last calls on bookkeeping.
        // 2026-09-22: WALL_CLOCK no longer excluded. It was, on the grounds that a run out of time
        // cannot ask for more — sound while it was the loosest of three ceilings, but it is now the
        // only always-on one, so excluding it would retire this mechanism. One tool-free request is
        // seconds against a 30-minute bound.
        budget.exceededLimit()?.let { limit ->
            if (budget.beginClosing()) {
                Log.i(TAG, "⏹ Budget ceiling ($limit) — spending one tool-free closing turn")
                val closing = llm.writeSession {
                    appendPrompt { user { text(CLOSING_TURN_PROMPT) } }
                    requestLLMWithoutTools()
                }
                if (!closing.isThinkingOnlyTurn() && closing.textContent().isNotBlank()) {
                    Log.i(TAG, "⏹ Closing turn answered from context — finishing with it")
                    return@node closing
                }
                Log.i(TAG, "⏹ Closing turn produced nothing usable — honest abort instead")
            }
            throw RunBudgetExceeded(limit)
        }

        // Autocompact fallback (rare once stale-perception eviction is in): if the prompt is
        // still heavy, summarize older turns into a TLDR while keeping the most recent messages
        // verbatim — so the current screen grounding is never lost to compaction.
        if (promptTokens > config.compactThresholdTokens) {
            Log.i(TAG, "🗜 Context ~$promptTokens tok > ${config.compactThresholdTokens} — compacting older history (keep=$compactKeep)")
            llm.writeSession {
                replaceHistoryWithTLDR(
                    FromLastNMessagesHistoryCompressionStrategy(compactKeep),
                )
            }
        }

        // Request the next turn — but if the model only "thinks out loud" (a <thought>/<think>
        // block with no tool call, blank once reasoning is stripped), re-prompt it to ACT rather
        // than let that turn route to finish with an empty answer. Bounded by [nudges] so a
        // stubborn model can't loop. This stays INSIDE the request (topology unchanged) so a real
        // text answer still routes straight to finish.
        llm.writeSession { requestLLMNudged(nudges) }
    }

/**
 * Request the next LLM turn, re-asking while the model returns a turn that did nothing — either
 * pure reasoning, or a tool call written as text ([TurnDefect]) — and the per-run [nudges] budget
 * allows. Returns the first turn that either calls a tool or gives a real answer, or the last
 * defective turn once the budget is spent (which then finishes honestly, with [ToolCallDebris]
 * salvaging whatever sentence was buried in the debris).
 */
private suspend fun ai.koog.agents.core.agent.session.AIAgentLLMWriteSession.requestLLMNudged(
    nudges: NudgeBudgets,
): Message.Assistant = nudgeWhileDefective(requestLLM(), nudges)

/**
 * The shared nudge loop, entered with a turn already in hand.
 *
 * Split out from [requestLLMNudged] so turn 0 — whose response comes from Koog's own request node
 * — runs the identical policy instead of a second copy of it.
 */
private suspend fun ai.koog.agents.core.agent.session.AIAgentLLMWriteSession.nudgeWhileDefective(
    first: Message.Assistant,
    nudges: NudgeBudgets,
): Message.Assistant {
    var response = first
    while (true) {
        val defect = response.defect() ?: return response
        if (!nudges.tryConsume(defect)) return response
        Log.i(
            TAG,
            "🔁 ${defect.name} turn — nudging model to act " +
                "(${nudges.used(defect)}/${EmptyTurnPolicy.MAX_NUDGES})",
        )
        appendPrompt { user { text(defect.nudge) } }
        response = requestLLM()
    }
}

/** Why this turn must not be allowed to end the run, or null when it is a legitimate turn. */
private fun Message.Assistant.defect(): TurnDefect? = TurnDefect.of(
    hasToolCall = parts.any { it is MessagePart.Tool.Call },
    assistantText = textContent(),
)

/** A turn is "thinking-only" iff it has no tool call and nothing survives stripping reasoning. */
private fun Message.Assistant.isThinkingOnlyTurn(): Boolean {
    if (parts.any { it is MessagePart.Tool.Call }) return false
    return EmptyTurnPolicy.isThinkingOnly(textContent())
}

/** A user message is a re-injected screenshot iff it carries an image attachment part. */
private fun isReinjectedScreenshot(message: Message): Boolean =
    message is Message.User && message.parts.any { it is MessagePart.Attachment }

/**
 * Apply [PerceptionEvictionPolicy] to a Koog message history: collapse every perception
 * tool-result's `output` to the placeholder EXCEPT the most recent one. Tool results live as
 * [MessagePart.Tool.Result] parts inside [Message.User] messages, so we rebuild only the
 * affected user messages (everything else passes through untouched).
 */
private fun evictStalePerceptionText(messages: List<Message>): List<Message> {
    val perceptionToolPerMessage = messages.map { m ->
        // A6's harness-read screen is a look like any other: the newest look wins either way.
        if (m is Message.User && ScreenAfterAction.isBlock(m.textContent())) {
            ScreenAfterAction.MARKER
        } else {
            m.parts.filterIsInstance<MessagePart.Tool.Result>()
                .firstOrNull { it.tool in PerceptionEvictionPolicy.PERCEPTION_TOOLS }
                ?.tool
        }
    }
    val stale = PerceptionEvictionPolicy.staleIndices(perceptionToolPerMessage)
    if (stale.isEmpty()) return messages
    return messages.mapIndexedNotNull { index, message ->
        if (index !in stale || message !is Message.User) {
            message
        } else if (ScreenAfterAction.isBlock(message.textContent())) {
            // Its own message, not a tool result — nothing pairs with it, so it can simply go.
            null
        } else {
            message.copy(
                parts = message.parts.map { part ->
                    if (part is MessagePart.Tool.Result &&
                        part.tool in PerceptionEvictionPolicy.PERCEPTION_TOOLS
                    ) {
                        part.copy(output = PerceptionEvictionPolicy.PLACEHOLDER)
                    } else {
                        part
                    }
                },
            )
        }
    }
}

/**
 * Pull the first image's base64 PNG out of the **full** tool result and decode it.
 *
 * The image survives on [ReceivedToolResult.result] (the JSON from
 * `McpTool.encodeResult`, which keeps everything) — NOT on `output`, which is the
 * text rendering that `McpTool.encodeResultToString` strips images from. Returns
 * null when the result has no image content (e.g. `get_device_status`).
 */
private fun ReceivedToolResult.screenshotBytesOrNull(): ByteArray? {
    val root = result?.toKotlinxJsonElement() as? JsonObject ?: return null
    val content = root["content"] as? JsonArray ?: return null
    val base64 = content.asSequence()
        .mapNotNull { it as? JsonObject }
        .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "image" }
        ?.get("data")?.jsonPrimitive?.contentOrNull
        ?: return null
    return runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
}

/** The MCP result's text parts, joined — what the server said, minus images. */
private fun ReceivedToolResult.text(): String {
    val content = (result?.toKotlinxJsonElement() as? JsonObject)?.get("content") as? JsonArray ?: return ""
    return content.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
}

/** Koog-level success AND the MCP result's own verdict — a guard denial arrives as `isError`. */
private fun ReceivedToolResult.succeeded(): Boolean {
    val json = result?.toKotlinxJsonElement() as? JsonObject ?: return false
    return json["isError"]?.jsonPrimitive?.booleanOrNull != true
}

/** The annotated PNG `read_screen` attaches when its image mode is on; null otherwise. */
private fun CallToolResult.imageBytesOrNull(): ByteArray? =
    content.filterIsInstance<ImageContent>().firstOrNull()?.data
        ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
