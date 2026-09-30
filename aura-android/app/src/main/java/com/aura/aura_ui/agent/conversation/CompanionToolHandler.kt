package com.aura.aura_ui.agent.conversation

/**
 * What happened when a handler announced that its call turned out to be long-running.
 *
 * Three outcomes, not two, because "no ack was sent" has two completely different meanings and
 * collapsing them silently breaks one of the paths:
 *
 *  - [ACKED] — the async contract is live: the model has been told the work started, and the real
 *    outcome will interrupt it later.
 *  - [BLOCKING] — this endpoint rejected asynchronous function calling, so the process has been
 *    demoted ([LiveTaskTracker.demoteToBlocking]). The work should still go ahead; its result is
 *    simply delivered the old way, as the single reply to this call.
 *  - [REFUSED] — something long-running is already in flight. The work must NOT start. Two agent
 *    runs on one screen undo each other's navigation.
 */
enum class Escalation { ACKED, BLOCKING, REFUSED }

/**
 * How a handler tells [LiveSession] "this call is going to take a while".
 *
 * Exists because the length of a call is no longer knowable from its name. One tool (`ask_aura`)
 * covers both "what's the capital of France" and "order me a coffee", and only the brain lane —
 * inside the handler, after it has thought about the request — can tell which. So the decision to
 * acknowledge early moved from dispatch time to the moment the handler discovers it is escalating.
 */
fun interface EscalationSink {
    /** @param taskLabel the task as the user phrased it, echoed back to the model in the ack. */
    suspend fun escalate(taskLabel: String): Escalation

    companion object {
        /** For handlers and tests that never escalate; behaves like an ordinary blocking tool. */
        val NONE = EscalationSink { Escalation.BLOCKING }
    }
}

/**
 * A dispatcher for Live function calls. Both [CompanionTools] (the universal `ask_aura`) and
 * [CompanionDirectTools] (the fast device lane) satisfy this; [CompanionToolRouter] composes them.
 * [LiveSession] depends only on this interface, so the WS loop is agnostic to how tools are split.
 */
interface CompanionToolHandler {
    /**
     * @param escalate call this BEFORE beginning work that will outlast a conversational turn,
     * and honour a [Escalation.REFUSED] by not starting. Fast tools ignore it.
     */
    suspend fun handle(
        call: CompanionToolCall,
        escalate: EscalationSink = EscalationSink.NONE,
    ): CompanionToolResult
}
