package com.aura.aura_ui.agent.conversation

/**
 * Stops one misheard sentence from changing several device settings at once.
 *
 * ### The observed failure
 *
 * The Live model occasionally emits a BATCH of function calls — flashlight on, DND on, Bluetooth
 * on — from a single mishearing. `LiveSession` ran every call in the batch unconditionally, so the
 * user got three changes they never asked for from one misheard word. Rare, but the damage is
 * real and the user has to undo it by hand.
 *
 * ### Why "first one wins" rather than "refuse the batch"
 *
 * Refusing everything would also block a genuine "turn on the flashlight and mute the phone",
 * making a correct request need a confirmation round-trip. Allowing the first keeps the common
 * case instant while capping the blast radius of a mishearing at exactly one change — and the
 * refusal text hands the model the words to check the rest with the user, so nothing is silently
 * dropped.
 *
 * Reads are unrestricted (a misheard `device_context` costs nothing), and `ask_aura` is exempt:
 * it carries its own one-task-at-a-time guard and its own reasoning about what was meant.
 *
 * Pure, so it unit-tests without a socket — the same shape as [InterruptGate] and
 * [LiveTaskTracker].
 */
object ToolBatchGate {

    /** How the batch was split. [refused] preserves the order the model emitted. */
    data class Decision(
        val allowed: List<CompanionToolCall>,
        val refused: List<CompanionToolCall>,
    )

    fun apply(calls: List<CompanionToolCall>): Decision {
        if (calls.size <= 1) return Decision(calls, emptyList())

        val allowed = mutableListOf<CompanionToolCall>()
        val refused = mutableListOf<CompanionToolCall>()
        var stateChangeUsed = false

        for (call in calls) {
            val changesState = call.name in LiveToolDeclarations.STATE_CHANGING
            when {
                !changesState -> allowed.add(call)
                !stateChangeUsed -> { stateChangeUsed = true; allowed.add(call) }
                else -> refused.add(call)
            }
        }
        return Decision(allowed, refused)
    }

    /**
     * What the refused calls report back.
     *
     * Written for the model to act on rather than merely absorb: it names what DID happen, so the
     * model does not claim the whole batch succeeded, and asks it to confirm the rest out loud —
     * which is exactly the check a mishearing needed in the first place.
     */
    fun refusalFor(refusedCall: CompanionToolCall, appliedCall: CompanionToolCall?): String =
        "Not done. Only one device change is allowed per turn, and this turn already " +
            (appliedCall?.let { "applied ${it.name}" } ?: "used its change") +
            ". This limit exists because several device changes arriving at once is usually a " +
            "mishearing. Tell the user what you did change, then ask whether they also wanted " +
            "${refusedCall.name} before trying it again."
}
