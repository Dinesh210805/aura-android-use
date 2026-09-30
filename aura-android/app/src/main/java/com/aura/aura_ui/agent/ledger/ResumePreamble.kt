package com.aura.aura_ui.agent.ledger

/**
 * Turn-1 input for a resumed run (Build 3).
 *
 * The per-turn ledger injection in the vision strategy only fires AFTER a tool result, so on the
 * very first model turn of a resume the ledger block isn't in context yet. Without it the model
 * can't see the carried plan and may call `set_plan` fresh — discarding everything the resume was
 * supposed to continue. So [forResume] seeds the rendered ledger into the first input directly.
 *
 * Ordering is deliberate: the message leads with [TEXT], NOT the ledger marker — the per-turn
 * injector drops user messages that `startsWith(RunLedgerRenderer.MARKER)`, so leading with the
 * marker would make it delete this whole message (goal + preamble) on turn 2.
 */
object ResumePreamble {
    const val TEXT =
        "You are resuming an interrupted task. The RUN LEDGER below has the plan, finished " +
            "steps, facts and dead ends from before. The screen may have changed since: call " +
            "read_screen first, then continue from the first unfinished step. Keep the plan " +
            "unless it needs to change."

    fun forResume(seed: RunLedger): String = TEXT + "\n\n" + RunLedgerRenderer.render(seed)
}
