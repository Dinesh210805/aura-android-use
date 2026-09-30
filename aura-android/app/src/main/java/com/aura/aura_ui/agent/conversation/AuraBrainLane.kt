package com.aura.aura_ui.agent.conversation

import android.content.Context
import android.util.Log
import com.aura.aura_ui.agent.memory.MemoryService
import okhttp3.OkHttpClient

/**
 * The reasoning half of the voice assistant: one call to the user's configured brain that decides
 * whether to answer, drive the phone, steer a running task, or stop one.
 *
 * Implemented as an interface so [CompanionTools] can be unit-tested with a fake and never needs
 * a provider key or a network.
 */
fun interface BrainLane {
    /**
     * @param request what the user just said
     * @param recentTranscript the last few turns, for context the request depends on
     * @param runningTask rendered status of the phone task in flight, or null when none
     * @param pendingResume an interrupted task worth offering to pick up, or null
     * @return the parsed decision, or null when the brain could not be reached at all
     */
    // No default values: a `fun interface` forbids them, and the terse SAM form is worth more
    // here than the convenience — it is what keeps fakes in the tests to a single line.
    suspend fun ask(
        request: String,
        recentTranscript: String?,
        runningTask: String?,
        pendingResume: String?,
    ): BrainLaneReply?
}

/**
 * Production [BrainLane]: a single plain `/chat/completions` turn against the user's configured
 * agent brain (via [BrainChat]), prompted by [BrainLanePrompt] and parsed by [BrainLaneParser].
 *
 * Deliberately NOT an agent run. `AuraAgent.runSpike` connects the in-process MCP server, takes a
 * screen wake-lock, arms the control lock and opens a run ledger — the right machinery for
 * driving a phone, absurd for answering "what's the capital of France". This lane is the light
 * path; it *escalates* to that machinery only when the answer genuinely needs the device.
 */
class AuraBrainLane(
    private val context: Context,
    private val memory: MemoryService,
    private val httpClient: OkHttpClient = BrainChat.defaultClient(LANE_READ_TIMEOUT_S),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : BrainLane {

    override suspend fun ask(
        request: String,
        recentTranscript: String?,
        runningTask: String?,
        pendingResume: String?,
    ): BrainLaneReply? {
        if (request.isBlank()) return null
        val brain = BrainChat.resolve(context) ?: run {
            Log.w(TAG, "no brain configured — cannot answer")
            return null
        }
        val now = clock()

        // Memory reads are best-effort: a store failure must degrade the answer, not lose the
        // turn. Sensitive entries are filtered out of the RECALL path exactly as the old
        // recall_memory tool did (M5) — a conversationally-manipulated model must never be able
        // to have them read aloud. buildSessionContext already excludes them at source.
        val memoryContext = runCatching { memory.buildSessionContext(now) }.getOrDefault("")
        val recalled = runCatching {
            memory.recall(request).filterNot { it.sensitive }.map { it.text }
        }.getOrDefault(emptyList())

        val prompt = BrainLanePrompt.build(
            request = request,
            recentTranscript = recentTranscript,
            memoryContext = memoryContext,
            recalled = recalled,
            runningTask = runningTask,
            pendingResume = pendingResume,
            // What the phone is actually doing. Read from the warm store rather than fetched, so
            // grounding the reply costs nothing in a live voice loop.
            phoneState = com.aura.aura_ui.agent.state.AuraStateStore.current().renderForBrainLane(),
            nowEpochMs = now,
        )

        val raw = BrainChat.complete(brain, prompt, temperature = TEMPERATURE, httpClient = httpClient)
            ?: return null
        return BrainLaneParser.parse(raw)
    }

    private companion object {
        const val TAG = "AuraBrainLane"

        /**
         * Shorter than the summarizer's, on purpose. This call sits between the user finishing a
         * sentence and AURA replying, and with the deferred ack the Live model has NO response to
         * this function call until the lane returns — so a stalled provider is heard as AURA
         * going mute mid-conversation. Past this point a spoken "I couldn't reach your provider"
         * is a better outcome than continuing to wait.
         */
        const val LANE_READ_TIMEOUT_S = 15L

        /**
         * Low, but not zero. This decides real actions on the user's phone, so determinism beats
         * flair — and the spoken line it produces is re-voiced by the Live model anyway, which is
         * where any warmth is supposed to come from.
         */
        const val TEMPERATURE = 0.3
    }
}
