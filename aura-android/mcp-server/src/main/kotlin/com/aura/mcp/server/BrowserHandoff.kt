package com.aura.mcp.server

import com.aura.mcp.bridge.McpScope

/**
 * Spec 2026-07-31 — *human takes the wheel*, the browser half.
 *
 * ### Why this is NOT the human control lock
 *
 * Both mean "the human is driving", so the obvious move is to reuse [ControlLock]. That
 * would deadlock the feature, and the reason is worth stating because it is not obvious
 * until you try it.
 *
 * `ControlLock.evaluate` deliberately takes **no tool name**: an exemption list is a list
 * someone eventually gets wrong, so "read-only tools are blocked too" is structurally true
 * rather than maintained. That is the right trade for the device case, where the accepted
 * cost is that `end_session` is refused while paused.
 *
 * It is the wrong trade here, because **auto-resume has to read the page to know it is
 * over**. The spec's first resume condition is "the login form is gone", which requires a
 * `browser_read` — and if the handoff refused reads, nothing could ever observe the
 * condition that ends it. The handoff would be permanent, with the escape-hatch pill as
 * the only way out of every single login.
 *
 * So the browser handoff blocks by **scope**, not by name: [McpScope.WRITE] is refused,
 * [McpScope.READ] passes. That is still structural — the classification already exists in
 * [McpToolScopes] and a new tool inherits the rule automatically — while leaving open
 * precisely the observation path that ends the handoff. It is also narrower than the human
 * lock in a way that matches what it protects: the human has taken **the browser**, not the
 * phone, and AURA watching a page it is not touching is not a betrayal.
 *
 * When the human takes the actual *phone*, [ControlLock] still fires on top of this and
 * blocks everything. The two compose; neither replaces the other.
 *
 * Public, unlike its sibling [ControlLock], because the *engines* need
 * [shouldAutoResume] — an engine is the only thing that can see whether the login form is
 * still on the page, and engines live outside this module.
 */
object BrowserHandoff {

    sealed interface Verdict {
        data object Allow : Verdict
        data class Block(val message: String) : Verdict
    }

    fun evaluate(handedOff: Boolean, scope: McpScope): Verdict = when {
        !handedOff -> Verdict.Allow
        scope == McpScope.READ -> Verdict.Allow
        else -> Verdict.Block(HANDED_OFF_MESSAGE)
    }

    const val HANDED_OFF_MESSAGE: String =
        "The user is using the browser window right now — you asked them to. Don't touch " +
            "the page. You may still read it (browser_read / browser_screenshot) to see " +
            "when they are finished; control returns automatically once they stop, or when " +
            "they tap Resume."

    /**
     * Should the agent take the browser back?
     *
     * All three conditions, never one alone. Each rules out a specific way of getting it
     * wrong, and the failure mode of resuming early is the worst one this design has: the
     * agent starts clicking a page while the user is still typing their password into it.
     *
     * @param loginFormGone the page no longer shows what the handoff was requested for.
     *   Null when unknown — an engine that could not read the page must not be treated as
     *   having seen it disappear.
     * @param msSinceLastTouch from the window's own touch feed. Accessibility events cannot
     *   supply this: during a handoff the user is touching AURA's own window, so
     *   `PauseTrigger` filters every one of those touches as self-caused.
     * @param windowFrontmost the user may have stepped away to read an OTP from an SMS.
     *   Resuming while they are in Messages means they come back to a page the agent has
     *   already navigated away from.
     */
    fun shouldAutoResume(
        loginFormGone: Boolean?,
        msSinceLastTouch: Long,
        windowFrontmost: Boolean,
    ): Boolean =
        loginFormGone == true &&
            msSinceLastTouch >= QUIET_MS &&
            windowFrontmost

    /**
     * How long the user must be still before AURA takes the browser back.
     *
     * Short, because this fires only when the form is *already* gone and the window is
     * still in front — by then the user has finished, and a long wait reads as the agent
     * having hung. It is not a safety margin; the other two conditions are the safety.
     */
    const val QUIET_MS = 2_000L
}
