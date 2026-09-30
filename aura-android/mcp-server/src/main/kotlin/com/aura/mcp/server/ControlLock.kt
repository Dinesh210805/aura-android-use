package com.aura.mcp.server

/**
 * Spec 2026-07-31 (browser automation) — *Control ownership: one lock, three holders*.
 *
 * > **human > local agent > MCP client — the human always wins.**
 *
 * Pause, the browser handoff, and refusing remote MCP calls are the SAME lock changing
 * hands, not three features. Modelling them separately is what guarantees months of
 * desync bugs, so they all resolve here, at one chokepoint ([scopedTool]).
 *
 * The gap this closes: today a remote client has no idea the user picked up their phone,
 * and happily keeps firing `tap` / `type_text` / `browser_act` into a device whose owner
 * is mid-sentence in a message to someone else.
 *
 * ### Why there is no tool name in this signature
 *
 * Deliberate. An exemption list — "these tools are still fine while paused" — is a list
 * someone eventually gets wrong, and it would be the one hole in a guard whose entire
 * value is having none. Read-only tools are blocked too, exactly as the spec says:
 * screenshotting somebody's screen the instant they grab their phone is precisely when
 * not to. With no tool name here, that hole cannot be opened by accident.
 *
 * It does mean `end_session` is refused while paused, since it routes through
 * `scopedTool` like everything else. That is intended, not an oversight: the spec's
 * answer to a paused run is a **checkpoint** (Run Ledger), which is loop-level work, not
 * a carve-out in the lock.
 *
 * ### Why this is one boolean and not the three-holder enum
 *
 * v1 has exactly one contest — the human versus everyone else — and that is the contest
 * the spec calls the trust primitive. A three-valued `Holder` type whose third value no
 * branch can reach would be the `AuraStatus.Stuck` mistake in a new costume: a type
 * carrying meanings the code does not honour. The agent-versus-MCP-client half of the
 * ordering arrives with the wiring that makes the local agent actually claim the lock;
 * until then, saying "paused by the human" is the honest name for what this decides.
 */
internal object ControlLock {

    sealed interface Verdict {
        data object Allow : Verdict
        data class Block(val message: String) : Verdict
    }

    /**
     * The message is written for a model, not a log reader — and deliberately NOT an
     * invitation to retry.
     *
     * The previous wording ("Wait and try again in a moment, or ask them to resume") was
     * followed literally on device (traces 2026-08-05): five identical `get_ui_tree`
     * retries, then four `end_session` attempts with the wording changed each time, as
     * though the phrasing were what had been refused. Each was a full ~20k-token round
     * trip against a free-tier quota that two runs went on to exhaust.
     *
     * A refusal that suggests retrying gets retried. The local agent no longer even
     * reaches here while paused — `PausedLoopGate` stops the loop — but remote MCP
     * clients still see this string, and it must not send them into the same spiral.
     */
    private const val PAUSED_MESSAGE =
        "The user has taken their phone back, so AURA has stepped aside. Do NOT retry this " +
            "call and do not try another route — nothing will work until they hand control " +
            "back. Stop here; work resumes on its own when they are done."

    fun evaluate(pausedByHuman: Boolean): Verdict =
        if (pausedByHuman) Verdict.Block(PAUSED_MESSAGE) else Verdict.Allow
}
