package com.aura.mcp.server

/**
 * "A driver just used this server."
 *
 * Spec 2026-07-31 — *control ownership*, the arming half. The control lock refuses tool
 * calls while the human holds the wheel, but it should only be *armed* while somebody is
 * actually driving: a person touching their own idle phone is not an interruption, and
 * treating it as one is what left AURA reading "Paused" before it had been asked to do
 * anything (2026-08-03).
 *
 * The local agent has a run lifecycle, so it can announce start and finish directly. A
 * remote MCP client has no such thing — it is someone's PC firing tool calls at a phone,
 * with no session boundary this process can observe. All the phone can see is that calls
 * are arriving, which is exactly what this reports; the app side turns that into a rolling
 * "still driving" window.
 *
 * Fires on every tool call **before** any gate runs, including calls that are about to be
 * refused. A client that keeps calling into a paused phone is still very much driving —
 * arguably more so — and if refusals did not count, the driving window would lapse mid-
 * pause and un-arm the lock underneath the user.
 */
internal fun interface DriverActivitySink {
    fun onToolDispatched()

    companion object {
        /** For hosts with no lock to arm — unit tests, non-device servers. */
        val NOOP = DriverActivitySink {}
    }
}
