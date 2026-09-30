package com.aura.mcp.server

import com.aura.mcp.bridge.TokenPrincipal
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Coroutine context element that carries the authenticated [TokenPrincipal]
 * of the currently-dispatching MCP request.
 *
 * Set by [com.aura.mcp.McpServerController]'s auth interceptor immediately
 * after the bearer-auth block validates the token, and read by per-tool
 * scope guards via [currentMcpPrincipalOrNull]. Because MCP tool handlers
 * are `suspend` lambdas, the element propagates from the request coroutine
 * to the handler invocation automatically — no thread-locals, no manual
 * passing of `call` references through the SDK boundary.
 */
internal class McpPrincipalElement(
    val principal: TokenPrincipal,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<McpPrincipalElement>
}

/**
 * Convenience accessor for use inside `suspend` tool handlers.
 * Returns `null` if no principal is bound — which should only happen if a
 * tool handler is invoked outside an authenticated request (e.g. unit
 * tests), since the production routing pipeline always installs one.
 */
internal suspend fun currentMcpPrincipalOrNull(): TokenPrincipal? =
    coroutineContext[McpPrincipalElement]?.principal
