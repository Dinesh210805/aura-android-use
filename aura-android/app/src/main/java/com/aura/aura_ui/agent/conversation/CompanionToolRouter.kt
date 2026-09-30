package com.aura.aura_ui.agent.conversation

/**
 * Routes a Live function call to the fast device lane ([CompanionDirectTools]) when it owns the
 * tool, else to [primary] (CompanionTools: the universal `ask_aura`). One place decides the split,
 * so LiveSession stays agnostic and each handler keeps a single responsibility.
 *
 * The direct lane never escalates — every tool it owns is a sub-100 ms device call — so the sink
 * is passed only to [primary].
 */
class CompanionToolRouter(
    private val primary: CompanionToolHandler,
    private val direct: CompanionDirectTools,
) : CompanionToolHandler {
    override suspend fun handle(call: CompanionToolCall, escalate: EscalationSink): CompanionToolResult =
        if (direct.handles(call.name)) direct.handle(call) else primary.handle(call, escalate)
}
