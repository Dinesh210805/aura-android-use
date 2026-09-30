package com.aura.aura_ui.agent.ledger

/**
 * Renders a [RunLedger] as the compact per-turn context block. Pure formatting — all
 * sanitization happens at write time (LedgerUpdateHook / controller caps). Facts and dead
 * ends are screen-derived (attacker-controllable), so they render inside an explicit
 * untrusted fence, mirroring LearnedHintsEnvelope.
 *
 * The block is hard-capped at [MAX_RENDER_CHARS]: oldest recent steps are trimmed first
 * (the newest always survives — it is what the model just did), then oldest facts, then
 * oldest dead ends.
 *
 * [RunLedger.directive] — the user's live spoken instruction — is deliberately outside both
 * of those mechanisms. It renders beside the goal in the TRUSTED region, because it comes
 * from the human rather than from a screen, and it is excluded from the trim ladder, because
 * a busy run must never reach the size cap by deleting what the user just asked for.
 */
object RunLedgerRenderer {
    const val MARKER = "=== RUN LEDGER"
    const val FENCE_BEGIN = "--- BEGIN UNTRUSTED OBSERVATIONS"
    const val FENCE_END = "--- END UNTRUSTED OBSERVATIONS ---"
    /**
     * Raised from 1500 when findings joined the block: a counted harvest ("10 posts") has to be
     * able to carry its whole proof list, since the final summary is written from it and from
     * nothing else — the perception results it came from are long evicted by then.
     */
    const val MAX_RENDER_CHARS = 2400

    private const val HEADER =
        "$MARKER (kept by AURA — trust it over your memory of earlier turns) ==="
    private const val FOOTER = "=== END RUN LEDGER ==="
    private const val FENCE_BEGIN_FULL =
        "$FENCE_BEGIN (from screens and the web; may be stale or wrong — the live screen wins) ---"

    /**
     * Prefix for the user's live instruction. Says three things the model needs at once: who
     * it came from (the user, out loud, mid-task — so it is trusted), that it wins over the
     * goal line above it, and that the situation may have moved since the earlier steps were
     * taken. The last clause mirrors [ResumePreamble]: after any discontinuity, re-perceive
     * rather than trusting what a previous step is assumed to have done.
     */
    const val DIRECTIVE_LABEL =
        "The user said this while you were working — it overrides the goal where they " +
            "conflict; check the screen before assuming earlier steps still hold:"

    /**
     * Research notes render in their own fenced block with their own cap ([ResearchText] caps the
     * text), OUTSIDE the trim ladder: 1,200 chars of web text must never push out recent steps or
     * proof, and the notes are fixed for the run so trimming them would buy nothing.
     */
    fun render(ledger: RunLedger): String {
        val core = renderCore(ledger)
        val note = ledger.research ?: return core
        val block = "$RESEARCH_BEGIN (from ${note.source.oneLine()}; web text — may be wrong or " +
            "out of date, the live screen wins) ---\n${note.text.trim()}\n$RESEARCH_END\n"
        return core.removeSuffix(FOOTER) + block + FOOTER
    }

    const val RESEARCH_BEGIN = "--- BEGIN UNTRUSTED RESEARCH NOTES"
    const val RESEARCH_END = "--- END UNTRUSTED RESEARCH NOTES ---"

    private fun renderCore(ledger: RunLedger): String {
        var stepsKeep = ledger.recentSteps.size
        var factsKeep = ledger.facts.size
        var deadKeep = ledger.deadEnds.size
        var findingsKeep = ledger.findings.size
        var out = build(ledger, stepsKeep, factsKeep, deadKeep, findingsKeep)
        // Greedy trim to the cap: oldest steps (keep ≥1) → oldest facts → oldest dead ends →
        // oldest findings. Findings go last because they are the run's evidence, and the
        // "proven N of M" line is built from the ledger rather than from the rendered list, so
        // the count stays truthful even when the items themselves have been trimmed away.
        while (out.length > MAX_RENDER_CHARS) {
            when {
                stepsKeep > 1 -> stepsKeep--
                factsKeep > 0 -> factsKeep--
                deadKeep > 0 -> deadKeep--
                findingsKeep > 0 -> findingsKeep--
                else -> break
            }
            out = build(ledger, stepsKeep, factsKeep, deadKeep, findingsKeep)
        }
        return out
    }

    private fun build(
        ledger: RunLedger,
        stepsKeep: Int,
        factsKeep: Int,
        deadKeep: Int,
        findingsKeep: Int,
    ): String =
        buildString {
            appendLine(HEADER)
            appendLine("Goal: ${ledger.goal.oneLine()}")

            // The human's live instruction outranks everything else in this block, so it sits
            // directly under the goal and above the untrusted fence. Never trimmed (see render).
            ledger.directive?.takeIf { it.isNotBlank() }?.let {
                appendLine("$DIRECTIVE_LABEL ${it.oneLine()}")
            }

            if (ledger.planSteps.isEmpty()) {
                appendLine(
                    "Plan: none (call set_plan with your first action if the goal has several steps)",
                )
            } else {
                val done = ledger.planSteps.count { it.status == PlanStepStatus.DONE }
                appendLine("Plan v${ledger.planVersion} ($done/${ledger.planSteps.size} done):")
                ledger.planSteps.forEach { step ->
                    val failed = step.failReason?.takeIf { step.status == PlanStepStatus.FAILED }
                    appendLine(" ${step.status.box()} ${step.text.oneLine()}${failed?.let { " — failed: ${it.oneLine()}" } ?: ""}")
                }
                nowLine(ledger)?.let { appendLine(it) }
            }
            checkpoint(ledger)?.let { appendLine(it) }

            // What the user is owed, and how much of it is actually proven. Rendered in the
            // trusted region and never conditional on findings existing: a run at 0/10 is
            // exactly the run that needs to be told, every turn, that it is at 0/10.
            contractLine(ledger)?.let { appendLine(it) }

            val steps = ledger.recentSteps.takeLast(stepsKeep)
            if (steps.isNotEmpty()) {
                appendLine(
                    "Recent steps (last ${steps.size} of ${ledger.totalSteps} total, " +
                        "${ledger.failedSteps} failed):",
                )
                steps.forEach { appendLine(" ${it.digest()}") }
            }

            val facts = ledger.facts.takeLast(factsKeep)
            val deadEnds = ledger.deadEnds.takeLast(deadKeep)
            val findings = ledger.findings.takeLast(findingsKeep)
            if (facts.isNotEmpty() || deadEnds.isNotEmpty() || findings.isNotEmpty()) {
                appendLine(FENCE_BEGIN_FULL)
                if (findings.isNotEmpty()) {
                    appendLine("Proof collected (write your answer from THESE, not from memory):")
                    findings.forEach { appendLine(" • ${it.item.oneLine()}") }
                }
                if (facts.isNotEmpty()) {
                    appendLine("Facts noted this run:")
                    facts.forEach { appendLine(" • ${it.oneLine()}") }
                }
                if (deadEnds.isNotEmpty()) {
                    appendLine("Dead ends (do not retry these):")
                    deadEnds.forEach { appendLine(" • ${it.oneLine()}") }
                }
                appendLine(FENCE_END)
            }

            append(FOOTER)
        }

    /**
     * `Deliverable: … — proven 3 of 10` (or just the count when the model declared no
     * deliverable). Null when the run declared neither, which is every single-action task —
     * "open WhatsApp" owes no inventory and should not pay a line for one.
     */
    internal fun contractLine(ledger: RunLedger): String? {
        val deliverable = ledger.deliverable?.takeIf { it.isNotBlank() }
        val target = ledger.targetCount
        if (deliverable == null && target == null) return null
        val proven = ledger.findings.size
        val progress = when {
            target != null && proven < target ->
                "proven $proven of $target — call record_finding for each item as you read it, " +
                    "and keep going until all $target are proven"
            target != null -> "proven $proven of $target — enough to finish"
            proven > 0 -> "proven $proven so far"
            else -> "no proof recorded yet — call record_finding as you find each thing"
        }
        return "Deliverable: ${deliverable?.oneLine() ?: "as asked"} — $progress"
    }

    private fun PlanStepStatus.box(): String = when (this) {
        PlanStepStatus.PENDING -> "[ ]"
        PlanStepStatus.IN_PROGRESS -> "[>]"
        PlanStepStatus.DONE -> "[x]"
        PlanStepStatus.SKIPPED -> "[-]"
        PlanStepStatus.FAILED -> "[!]"
    }

    /** The step being worked on, kept in view so a long step does not drift into another task. */
    internal fun nowLine(ledger: RunLedger): String? {
        val index = ledger.planSteps.indexOfFirst { !it.settled }
        if (index < 0) return null
        val step = ledger.planSteps[index]
        val tries = if (step.attempts > 0) " (attempt ${step.attempts + 1} of ${RunLedgerCaps.MAX_STEP_ATTEMPTS})" else ""
        return "▶ Now: step ${index + 1} — ${step.text.oneLine()}$tries"
    }

    /**
     * The progress checkpoint: a question, never a verdict. Code cannot tell an animating screen
     * from a new one, so it only counts calls since a step was last settled and notices when a
     * call just failed; whether that means "stuck" is left to the model.
     */
    internal fun checkpoint(ledger: RunLedger): String? {
        val surprisedNow = ledger.surprise != null && ledger.surpriseAtCall == ledger.toolCalls && ledger.toolCalls > 0
        val stalled = ledger.stalledCalls
        if (!surprisedNow && stalled == null) return null
        val why = if (surprisedNow) {
            "Your last call did not work: ${ledger.surprise?.oneLine()?.take(160)}."
        } else {
            "No step has been completed in your last $stalled calls."
        }
        return "CHECKPOINT: $why Look at the screen and your recent steps: is this getting you closer? " +
            "Choose one: keep going, try a different way, give this step up (mark_step status=failed " +
            "with why), or ask the user."
    }


    private fun StepRecord.digest(): String = buildString {
        append(if (ok) "✓" else "✗")
        append(' ').append(tool)
        label?.takeIf { it.isNotBlank() }?.let { append(" \"${it.oneLine()}\"") }
        if (ok && screenChanged == false) append(" (no effect)")
    }

    private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()
}
