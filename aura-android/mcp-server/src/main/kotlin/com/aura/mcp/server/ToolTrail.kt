package com.aura.mcp.server

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * Where a tool call's journey through the server is reported: every gate it passed or was refused
 * by, the dispatch, and the post steps (screen-generation bump, settle, evidence). The host's run
 * log turns these into the call's pipeline trail.
 *
 * NOOP by default, so hosts that keep no such log — the external MCP server, tests — pay nothing.
 */
fun interface ToolTrailSink {
    fun step(tool: String, stage: String, name: String, verdict: String, detail: String?, ms: Long?)

    companion object {
        val NOOP = ToolTrailSink { _, _, _, _, _, _ -> }
    }
}

/**
 * The trail of the call in progress, carried in the handler's coroutine context so a tool deep in
 * the stack (a som_id resolution, say) can report a step without a sink being threaded to it — and
 * without a process-wide global, which two servers in one process would fight over.
 */
internal class ToolTrail(
    private val tool: String,
    private val sink: ToolTrailSink,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolTrail>

    /** Never throws: a logging fault must not fail the call it describes. */
    fun step(stage: String, name: String, verdict: String, detail: String? = null, ms: Long? = null) {
        runCatching { sink.step(tool, stage, name, verdict, detail, ms) }
    }
}

/** The current call's trail, or null outside a scoped tool call. */
internal suspend fun currentTrail(): ToolTrail? = currentCoroutineContext()[ToolTrail]
