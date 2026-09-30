package com.aura.aura_ui.agent.llm

/**
 * Time budgets for the agent's LLM calls.
 *
 * ### Why this exists (device capture 2026-08-05 20:42)
 *
 * ```
 * 20:42:54.873  ▶ Running goal: open amazon.com in a web browser
 * 20:42:55.147  → POST .../chat/completions (body 63771 chars)
 *               (silence — minutes; the run never ended)
 * ```
 *
 * The agent's HTTP client carried interceptors and **no timeouts at all**. A phone that
 * changes network mid-request leaves a half-open socket: the server will never answer on
 * it, and with no read budget the client waits for that answer forever. The run parked,
 * and the UI — having nothing to report — sat on "working on it" indefinitely.
 *
 * `RetryingLLMClient` could not rescue it. It retries *errors* (429 / 5xx / "overloaded"),
 * and a hang produces none. A safety net that only catches failures cannot catch a stall.
 *
 * So the point of these numbers is **not speed**. It is to convert a stall into a failure,
 * because only a failure can reach the retry ladder or an honest message to the user.
 *
 * ### Choosing the numbers
 *
 * The two error directions are wildly asymmetric, so the budgets are deliberately loose:
 *
 *  - Too tight → aborts a turn that was about to succeed. A vision turn with thinking
 *    enabled legitimately runs tens of seconds; the slowest healthy call in the captured
 *    traces was 4.6 s, and a retry ladder waiting out a rate-limit window can add ~60 s
 *    on top *inside the same call*. [REQUEST_MS] must clear that comfortably.
 *  - Too loose → the user waits longer before being told the truth. Bounded and honest
 *    beats fast and wrong.
 *
 * [CONNECT_MS] is the exception and is short: failing to reach the host at all is a
 * different, faster-to-detect condition, and no healthy connection takes 15 s to open.
 */
data class AgentHttpTimeouts(
    /** Whole call, including redirects, retries inside the client, and body transfer. */
    val requestMs: Long = REQUEST_MS,
    /** TCP + TLS establishment only. */
    val connectMs: Long = CONNECT_MS,
    /** Gap between bytes once the connection is live — what a half-open socket trips. */
    val socketMs: Long = SOCKET_MS,
) {
    companion object {
        const val REQUEST_MS = 120_000L
        const val CONNECT_MS = 15_000L
        const val SOCKET_MS = 60_000L
    }
}
