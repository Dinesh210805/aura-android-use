package com.aura.mcp.server

/**
 * Spec 2026-07-31 — dispatch-time control-lock check. Pure decision logic lives in
 * [ControlLock]; this class only supplies the bit, and reads it fresh every call.
 *
 * Fresh on every call is load-bearing. A cached verdict would mean the pause lands one
 * tool call late, which is one more gesture fired into a phone whose owner is already
 * holding it — the precise moment the whole feature exists to prevent.
 *
 * Unlike [ForegroundGate] this is not `suspend`: the answer is an in-memory flag, not a
 * UI-tree snapshot. That is also why the check sits BEFORE the foreground gate in
 * [scopedTool] — paying for a tree read on a call about to be refused would be backwards.
 *
 * **Fails open**, matching the fail-open posture of `SensitivePolicy`, OPA and Prompt
 * Guard elsewhere in this codebase. A pause store that cannot answer must not be able to
 * brick every tool call on the device: losing pause is bad, losing the product is worse.
 */
internal class ControlLockGate(
    private val pausedByHuman: () -> Boolean,
) {
    /** Non-null = refuse this call; the human has the wheel. */
    fun check(): ControlLock.Verdict.Block? {
        val paused = runCatching { pausedByHuman() }.getOrDefault(false)
        return ControlLock.evaluate(paused) as? ControlLock.Verdict.Block
    }

    companion object {
        /** Unit tests and non-device hosts: there is no human here to yield to. */
        val NOOP = ControlLockGate(pausedByHuman = { false })
    }
}
