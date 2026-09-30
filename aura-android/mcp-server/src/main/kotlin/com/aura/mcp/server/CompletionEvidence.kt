package com.aura.mcp.server

import com.aura.mcp.cache.ScreenGeneration
import java.util.concurrent.atomic.AtomicLong

/**
 * Whether the agent has actually LOOKED at the screen since it last changed it.
 *
 * ### The failure this exists for
 *
 * Eval suite 2026-08-26, task 3 — "Send a message hi to Amma in WhatsApp". The agent called
 * `read_screen`, saw `hi` sitting **in the composer**, called `press_enter`, and then, without
 * looking again, marked its own plan step *"Tap Send button and verify message is sent"* as done
 * and called `end_session(outcome = "success")`. The message never sent. The human scored it FAIL.
 *
 * `end_session`'s description already said, in capitals, *"VERIFY BEFORE YOU FINISH: only call
 * this with a success reason once the goal state is confirmed."* The model had that text in its
 * context and wrote a plan step quoting it. Prose did not enforce it, and adding more prose is
 * not a fix — it makes the prompt heavier, and a heavier prompt makes a small model **worse** at
 * finding the rule it needs (21.3% long-context retrieval; see `TODO.md`'s standing facts).
 *
 * So the tool contract enforces it instead.
 *
 * ### How it knows
 *
 * [ScreenGeneration] is already a monotonic count of screen-mutating tool calls, bumped at the
 * one dispatch chokepoint for every WRITE-scoped tool. This needs nothing new: record the
 * generation at each look, and compare.
 *
 * ```
 *   read_screen        gen 5 → lastLook = 5      evidence fresh (5 >= 5)
 *   press_enter        gen 6                     evidence STALE  (5 < 6)
 *   end_session(ok)                              refused — go look
 *   read_screen        gen 6 → lastLook = 6      evidence fresh (6 >= 6)
 *   end_session(ok)                              allowed
 * ```
 *
 * ### Why only some goals are gated
 *
 * Gating every success claim would cost one extra perception turn on **every** mutating task,
 * which is the same budget this codebase is currently spending weeks reclaiming. The gate is
 * therefore narrow by design: it covers the goals where a false success is expensive and cannot
 * be undone by trying again — a message or mail that did or did not leave, a purchase, a post.
 *
 * For `open_app`, `search`, `play_media` and `navigate`, the `post_action_observation` block that
 * already rides every gesture result (foreground_app, top_labels, element_count) is genuinely
 * sufficient, and making those pay an extra turn would buy nothing.
 *
 * ### The hole, stated rather than hidden
 *
 * `goal_type` is declared by the model, so a run that omits it is not gated. That is tolerable
 * here — this guards an honest agent against its own over-confidence, which is the observed
 * failure, not against one trying to escape. A gate that cannot be dodged has to key on the
 * tools that were actually called, which belongs with the tool-name policy work in
 * `SensitivePolicy`, not here.
 */
object CompletionEvidence {

    /**
     * Goals where "I think it worked" is not good enough. Matches the `goal_type` vocabulary in
     * `end_session`'s schema; unknown values are not gated, so adding a goal type cannot
     * accidentally start blocking runs.
     */
    val GATED_GOAL_TYPES: Set<String> = setOf("send_message", "send_email", "purchase", "post")

    /**
     * Tools that count as having looked at the screen.
     *
     * `wait_for` is deliberately absent. It settles and reports that something changed, which is
     * exactly the weak, structural evidence task 3 mistook for confirmation — admitting it would
     * let "press_enter then wait_for" satisfy a gate built to catch precisely that. `get_device_status`
     * is absent for the same reason: it describes the device, not the screen.
     */
    val LOOK_TOOLS: Set<String> = setOf("read_screen", "perceive_screen", "get_screenshot", "verify_action")

    /** -1 rather than 0: "never looked" must not read as "looked at generation 0". */
    private val lastLookGeneration = AtomicLong(-1)

    /** Record that the agent saw the screen as it is now. Called from the dispatch chokepoint. */
    fun noteLook() {
        lastLookGeneration.set(ScreenGeneration.current)
    }

    /** True when nothing has changed the screen since the last look. */
    fun isFresh(): Boolean = lastLookGeneration.get() >= ScreenGeneration.current

    /**
     * Clear at a session boundary. Without this, a look taken during one run could satisfy the
     * gate for the next one whenever no gesture happened in between — the two runs would be
     * different tasks on the same unchanged screen, and the second would inherit evidence it
     * never gathered.
     */
    fun reset() {
        lastLookGeneration.set(-1)
    }

    /**
     * The verdict for one `end_session` call, or null when the gate does not apply.
     *
     * Only ever refuses a **success** claim. An honest failure must always be reportable: a run
     * that could not finish has to be able to say so, and blocking that would trade a false
     * success for a stuck agent, which is worse.
     */
    fun refusalFor(outcome: String?, goalType: String?): String? {
        if (outcome?.lowercase() != "success") return null
        val goal = goalType?.lowercase()?.trim() ?: return null
        if (goal !in GATED_GOAL_TYPES) return null
        if (isFresh()) return null
        return "Not so fast — you changed the screen after you last looked at it, so nothing " +
            "you hold confirms this $goal actually happened. Call read_screen now and check " +
            "the result yourself: for a message, the text must appear in the thread with a " +
            "sent indicator, not sitting in the composer. Then call end_session again. If it " +
            "did NOT land, call end_session with outcome=\"failure\" and say what you saw — " +
            "that is always allowed, and an honest failure beats a false success."
    }
}
