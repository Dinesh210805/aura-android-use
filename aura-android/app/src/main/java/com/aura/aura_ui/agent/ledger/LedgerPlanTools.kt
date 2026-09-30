package com.aura.aura_ui.agent.ledger

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The step checker's answer. [ran] false means it could not judge (no reply, unparseable). */
data class StepVerdict(
    val verified: Boolean,
    val reason: String,
    val ran: Boolean = true,
    /** Claimed before the screen was looked at again: sent back without using an attempt. */
    val premature: Boolean = false,
)

/**
 * The load-bearing logic behind the `set_plan` / `mark_step` Koog tools (the glue in
 * LedgerKoogTools is thin, compile-verified adaptation — this class is the unit-tested part,
 * mirroring the SkillProxy / McpProxyTools pattern). Both tools are pure bookkeeping against
 * the run ledger: no device access, no capability, results are short confirmations echoing
 * checklist state so the model sees its plan progress in the tool result itself.
 */
class LedgerPlanTools(
    private val controller: RunLedgerController,
    /**
     * Judges a `done` claim against the screen (the step checker). Null skips the check — tests,
     * and hosts with no checker — and the claim is stored with its evidence as given.
     */
    private val checkStep: (suspend (ledger: RunLedger, index: Int, evidence: String) -> StepVerdict)? = null,
) {

    suspend fun setPlan(args: JsonObject): CallToolResult {
        val steps = (args["steps"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            ?.take(MAX_PLAN_STEPS)
            .orEmpty()
        if (steps.isEmpty()) {
            return err("'steps' must be a non-empty array of step descriptions.")
        }
        controller.setPlan(steps)
        // The goal contract, declared with the plan: what the user ends up with, and how many
        // things that is. Written here rather than derived from the goal text because only the
        // model has read the request — and it is what the completion gate counts against, so a
        // run that declares "10" has committed to proving 10.
        controller.setContract(
            deliverable = args["deliverable"]?.jsonPrimitive?.contentOrNull,
            targetCount = args["target_count"]?.jsonPrimitive?.intOrNull,
        )
        return ok("Plan set (${steps.size} steps). Now do step 1 and call mark_step as each completes.")
    }

    suspend fun markStep(args: JsonObject): CallToolResult {
        val plan = controller.snapshot().planSteps
        if (plan.isEmpty()) return err("No plan exists yet — call set_plan first.")

        val index = args["index"]?.jsonPrimitive?.intOrNull
            ?: return err("'index' (0-based step number) is required.")
        if (index !in plan.indices) {
            return err("'index' $index is out of range — valid: 0..${plan.lastIndex}.")
        }
        val statusRaw = args["status"]?.jsonPrimitive?.contentOrNull
            ?: return err("'status' is required: one of done, in_progress, skipped, failed.")
        val note = args["note"]?.jsonPrimitive?.contentOrNull
        val status = when (statusRaw.lowercase()) {
            "done" -> return markDone(index, args["evidence"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(), note)
            "failed" -> {
                controller.failStep(index, note?.takeIf { it.isNotBlank() } ?: "gave up")
                return ok("Step ${index + 1} marked failed. Move on to the next step. ${progress()}")
            }
            "in_progress" -> PlanStepStatus.IN_PROGRESS
            "skipped" -> PlanStepStatus.SKIPPED
            else -> return err("Unknown status '$statusRaw' — use done, in_progress, skipped, or failed.")
        }

        controller.markStep(index, status, note)
        return ok("Step ${index + 1} marked $statusRaw. ${progress()}")
    }

    /**
     * A `done` claim is only stored once the step checker has seen it on the screen. A rejection
     * keeps the step open and uses an attempt; the last attempt fails it so the run moves on.
     */
    private suspend fun markDone(index: Int, evidence: String, note: String?): CallToolResult {
        if (evidence.isEmpty()) {
            return err(
                "'evidence' is required with status=done: say what on the screen right now shows " +
                    "step ${index + 1} happened (a button, a label, a message).",
            )
        }
        val verdict = checkStep?.invoke(controller.snapshot(), index, evidence)
        if (verdict == null || verdict.verified) {
            controller.verifyStep(index, evidence)
            note?.let { controller.noteFact(it) }
            return ok("Step ${index + 1} done. ${progress()}")
        }
        if (verdict.premature) return err("Not yet: ${verdict.reason}.")
        controller.rejectStep(index, verdict.reason)
        val step = controller.snapshot().planSteps[index]
        return if (step.status == PlanStepStatus.FAILED) {
            err(
                "Step ${index + 1} is not confirmed (${verdict.reason}) and has used all " +
                    "${RunLedgerCaps.MAX_STEP_ATTEMPTS} attempts, so it is marked FAILED. Move on to the " +
                    "next step, and report this one as not done. ${progress()}",
            )
        } else {
            err(
                "Step ${index + 1} is not confirmed: ${verdict.reason}. Attempt ${step.attempts} of " +
                    "${RunLedgerCaps.MAX_STEP_ATTEMPTS}. Make it actually happen (or try a different way), " +
                    "then claim it again with evidence from the new screen.",
            )
        }
    }

    private fun progress(): String {
        val steps = controller.snapshot().planSteps
        return "Progress: ${steps.count { it.status == PlanStepStatus.DONE }}/${steps.size} done."
    }

    private fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = false)

    private fun err(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)

    companion object {
        const val MAX_PLAN_STEPS = 12
    }
}
