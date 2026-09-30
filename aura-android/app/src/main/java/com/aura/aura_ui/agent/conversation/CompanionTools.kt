package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.agent.ledger.ActiveRunRegistry
import com.aura.aura_ui.agent.mcpbridge.client.McpPayloadGuard
import com.aura.aura_ui.agent.memory.MemoryService
import com.aura.aura_ui.agent.memory.MemoryType
import com.aura.aura_ui.services.ControlLockStore
import com.aura.mcp.server.SensitivePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A function call the Live model emitted: tool name + raw JSON args + the call id to echo back. */
data class CompanionToolCall(val name: String, val args: JsonObject, val id: String = "")

/** A model-readable tool result. `isError = true` lets the model self-correct instead of crashing. */
data class CompanionToolResult(val text: String, val isError: Boolean = false)

/**
 * The universal `ask_aura` handler — everything Live cannot do in a single instant device call.
 *
 * ### Why one tool
 *
 * This used to be ten: memory reads and writes, reminders, web search, notifications, system
 * intents, task status and `drive_phone`. Choosing between them was left to the Live model, which
 * on the native-audio path runs with thinking disabled — a speech model doing ten-way routing.
 * The defensive prose that accumulated in those tool descriptions ("never reuse this phrasing for
 * anything else") is the fossil record of it getting the boundaries wrong.
 *
 * Now Live expresses intent and [BrainLane] — a real reasoning model — decides. Nothing was lost:
 * every bridge those tools reached is already mounted on the agent's in-process MCP server, so a
 * request that needs them escalates and gets them.
 *
 * `drive_phone` still routes through `runFromSavedSettings`, so every gesture passes the same
 * hook chain and SensitivePolicy. The conversation plane gains no privilege from this change.
 */
class CompanionTools(
    private val memory: MemoryService,
    private val phone: PhoneTaskRunner,
    private val brain: BrainLane,
    private val onTaskProgress: (String) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * The user signed off — tear down the voice session and dismiss the overlay. Deliberately
     * fired AFTER the tool result is built so the model's farewell is already on its way out;
     * the controller schedules the actual teardown so the last words are heard, not cut off.
     * A phone task still running is left alone (it owns the notification pill from here).
     */
    private val onEndConversation: () -> Unit = {},
    /** Reads the running task's own ledger so "how's it going?" is answered from fact. */
    private val taskStatus: TaskStatusProvider = TaskStatusProvider { null },
    /** The last few conversational turns, so the lane can resolve "it" and "that one". */
    private val recentTranscript: () -> String? = { null },
    /** An interrupted run worth offering to pick up, or null. */
    private val pendingResume: suspend () -> String? = { null },
    /** Reaches the RUNNING agent so a spoken correction can change its course mid-task. */
    private val runRegistry: ActiveRunRegistry = ActiveRunRegistry.shared,
    /**
     * Voice pause/resume, as lambdas rather than the store itself.
     *
     * `ControlLockStore` is `internal`, so naming it in this public constructor would leak an
     * internal type — and the seam is better this way regardless: tests assert that a spoken
     * "hold on" reached the lock without having to build one.
     */
    private val pauseRun: () -> Unit = { ControlLockStore.shared.pause() },
    private val resumeRun: () -> Unit = { ControlLockStore.shared.resume() },
) : CompanionToolHandler {

    /** The in-flight phone job, if any — the pill Cancel button's handle into a Live run. */
    @Volatile
    private var currentTask: Job? = null

    /** True while a phone task is actually executing. */
    private fun isTaskRunning(): Boolean = currentTask?.isActive == true

    /**
     * Cancel an in-flight phone run (pill Cancel, or a spoken "stop"). Returns true when there
     * was a task to cancel. Only the TASK dies — the Live conversation survives, the model
     * receives an honest result, and the run's ledger stays resumable.
     */
    fun cancelCurrentTask(): Boolean = currentTask?.let { it.cancel(); true } ?: false

    override suspend fun handle(call: CompanionToolCall, escalate: EscalationSink): CompanionToolResult =
        runCatching {
            when (call.name) {
                LiveToolDeclarations.ASK_AURA -> askAura(call, escalate)
                LiveToolDeclarations.END_CONVERSATION -> {
                    onEndConversation()
                    CompanionToolResult("Conversation ended.")
                }
                else -> errorResult("Unknown tool: ${call.name}")
            }
        }.getOrElse { errorResult("Tool ${call.name} failed: ${it.message}") }

    // ── the one tool ──────────────────────────────────────────────────────────

    private suspend fun askAura(call: CompanionToolCall, escalate: EscalationSink): CompanionToolResult {
        val request = str(call.args, "request") ?: return errorResult("ask_aura needs a 'request'.")

        // FRONT-DOOR POLICY SCREEN (2026-08-12).
        //
        // The action plane already refuses a sensitive GESTURE, so nothing unsafe was ever
        // executed. But everything upstream still ran: the reasoning model read the request,
        // planned around it, and spent tokens before the refusal landed one layer down. Screening
        // here means a banking or credential request stops at the front door.
        //
        // Deliberately the SAME SensitivePolicy the action plane uses — one blocklist, one place
        // to update. A second copy here would drift within a release, and the two lanes would
        // start disagreeing about what is sensitive.
        //
        // NOT an error result: this is a decision AURA should say out loud, not a malfunction the
        // model should try to route around. `isError = true` invites a retry with different
        // phrasing, which is precisely the wrong response to a policy refusal.
        // screenSpokenRequest, not screen(): the request is prose, so the package rules need
        // word-boundary matching and spoken app names ("open Google Pay") rather than package
        // ids. Same object, same blocklists — see its KDoc.
        (SensitivePolicy.screenSpokenRequest(request) as? SensitivePolicy.Decision.Block)?.let { blocked ->
            return CompanionToolResult(blocked.message)
        }

        // Status of the run in flight, if any. This is what replaces the old task_status tool:
        // context the lane always reads, rather than a tool it has to remember to call.
        val running = if (isTaskRunning()) taskStatus.currentStatus() else null
        val resumable = if (running == null) runCatching { pendingResume() }.getOrNull() else null

        val reply = brain.ask(request, recentTranscript(), running, resumable)
            ?: return errorResult(
                "I couldn't reach your AI provider just now. Check the key in Settings → Brain, " +
                    "or try again in a moment.",
            )

        applySideEffects(reply)

        return when (val action = reply.action) {
            BrainLaneAction.None -> CompanionToolResult(reply.spoken.ifBlank { DEFAULT_ACK })
            is BrainLaneAction.Phone -> startTask(reply.spoken, escalate) {
                phone.run(action.task) { p -> onTaskProgress(p) }
            }
            BrainLaneAction.Continue -> startTask(reply.spoken, escalate) {
                phone.resumeLatest { p -> onTaskProgress(p) } ?: NO_RESUMABLE_TASK
            }
            is BrainLaneAction.Steer -> steer(action, reply.spoken)
            BrainLaneAction.Abort -> abort(reply.spoken)
            BrainLaneAction.Pause -> holdOrRelease(pause = true, spoken = reply.spoken)
            BrainLaneAction.Resume -> holdOrRelease(pause = false, spoken = reply.spoken)
        }
    }

    // ── escalation to the action plane ────────────────────────────────────────

    /**
     * Announce that this call is going long, then run it.
     *
     * The ack is what stops the model inventing an outcome: with `NON_BLOCKING` declared, an
     * unanswered call sitting in its context for 30-90 s leaves it two options, and users saw
     * both — inventing "done, I've ordered it", or quietly never mentioning it again. No amount
     * of prompting fixes that, because the truth never reached the model.
     */
    private suspend fun startTask(
        spoken: String,
        escalate: EscalationSink,
        block: suspend () -> String,
    ): CompanionToolResult {
        if (isTaskRunning()) return errorResult(ONE_TASK_AT_A_TIME)
        if (escalate.escalate(spoken.ifBlank { "a phone task" }) == Escalation.REFUSED) {
            return errorResult(ONE_TASK_AT_A_TIME)
        }
        val outcome = runCancellable(block)
        if (outcome == NO_RESUMABLE_TASK) return errorResult("There's no unfinished task to pick up.")
        runCatching { memory.logAction(spoken.ifBlank { "phone task" }, outcome) }
        return CompanionToolResult(McpPayloadGuard.capDescription(outcome))
    }

    /** Run the phone task as a tracked, externally-cancellable child job. */
    private suspend fun runCancellable(block: suspend () -> String): String =
        coroutineScope {
            val deferred = async { block() }
            currentTask = deferred
            try {
                deferred.await()
            } catch (ce: CancellationException) {
                // Distinguish "user cancelled the task" (we are still active) from
                // "the whole session is being torn down" (rethrow, never swallow).
                if (currentCoroutineContext().isActive) {
                    "Task cancelled by the user before completion."
                } else {
                    throw ce
                }
            } finally {
                currentTask = null
            }
        }

    // ── steering a task that is already running ───────────────────────────────

    /**
     * Push the user's spoken correction into the running agent's own working memory.
     *
     * It lands on the run's NEXT turn, not instantly — the ledger is re-rendered before each LLM
     * request. That is why "stop" is [abort] (immediate) rather than a directive: a redirect can
     * wait a beat, a stop cannot.
     */
    private suspend fun steer(action: BrainLaneAction.Steer, spoken: String): CompanionToolResult {
        val controller = runRegistry.current
            ?: return errorResult("Nothing is running at the moment, so there's nothing to change.")
        action.newGoal?.let { controller.retarget(it) }
        controller.setDirective(action.directive, clock())
        return CompanionToolResult(spoken.ifBlank { "Okay — changing course." })
    }

    private fun abort(spoken: String): CompanionToolResult =
        if (cancelCurrentTask()) {
            CompanionToolResult(spoken.ifBlank { "Stopped." })
        } else {
            errorResult("Nothing is running, so there's nothing to stop.")
        }

    /**
     * Voice pause/resume rides the EXISTING control lock rather than inventing a second one.
     * That lock already orders human > local agent > MCP client and checkpoints the ledger when
     * it engages, so a spoken "hold on" inherits all of it.
     */
    private fun holdOrRelease(pause: Boolean, spoken: String): CompanionToolResult {
        if (pause && !isTaskRunning()) {
            return errorResult("Nothing is running, so there's nothing to hold.")
        }
        if (pause) pauseRun() else resumeRun()
        return CompanionToolResult(spoken.ifBlank { if (pause) "Holding." else "Carrying on." })
    }

    // ── side effects ──────────────────────────────────────────────────────────

    /** Memory writes are best-effort: a store failure must not cost the user their answer. */
    private suspend fun applySideEffects(reply: BrainLaneReply) {
        reply.memories.forEach { m ->
            runCatching { memory.save(parseType(m.type), m.text) }
        }
        reply.reminders.forEach { r ->
            runCatching { memory.remind(r.text, r.whenEpochMs) }
        }
    }

    private fun errorResult(msg: String) = CompanionToolResult(msg, isError = true)

    private fun str(args: JsonObject, key: String): String? =
        args[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    /**
     * The lane may only write identity-class memories. Episodes and action logs are written by
     * the system from real events, so letting a model file into them would let it invent history.
     * Anything unrecognised becomes a durable fact about the user.
     */
    private fun parseType(raw: String?): MemoryType =
        raw?.let { runCatching { MemoryType.valueOf(it.trim().uppercase()) }.getOrNull() }
            ?.takeIf { it in MODEL_WRITABLE }
            ?: MemoryType.USER

    private companion object {
        /**
         * Memory types the lane may write. Shared with the agent lane (see MODEL_WRITABLE_TYPES in
         * MemoryService.kt) so the two planes cannot drift into different answers.
         */
        val MODEL_WRITABLE = com.aura.aura_ui.agent.memory.MODEL_WRITABLE_TYPES

        /** Sentinel from the resume branch — resumeLatest found nothing to continue. */
        const val NO_RESUMABLE_TASK = " no-resumable-task"

        const val DEFAULT_ACK = "Okay."

        /**
         * Mid-task duplex makes this reachable: with the mic live during a run the user can ask
         * for something else, and the lane will happily propose a second task. Two agent runs on
         * one screen undo each other's navigation, so the refusal hands the model the words to
         * sort it out with the user rather than silently dropping one.
         */
        const val ONE_TASK_AT_A_TIME =
            "A phone task is already running. Do not start another one. Tell the user what is " +
                "still in progress and ask whether to let it finish first or stop it — two tasks " +
                "would fight over the screen."
    }
}
