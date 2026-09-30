package com.aura.aura_ui.agent

import com.aura.mcp.server.CapabilityNarrative
import com.aura.mcp.server.McpToolScopes

/**
 * The single source of truth for AURA's identity-level capabilities — what it is, what it has, and
 * what it can actually do for the user. Referenced VERBATIM by BOTH planes so they never drift:
 *   - the conversation plane (com.aura.aura_ui.agent.conversation.Persona) injects it so the Gemini
 *     Live companion can accurately answer "what can you do?" and knows the full breadth of drive_phone;
 *   - the action plane (AuraAgent.SYSTEM_PROMPT usage) prepends it so the on-device agent shares the
 *     same self-knowledge while it works.
 *
 * Audio-safe prose only (no markdown, no emoji, numbers as words) because the conversation plane speaks
 * it aloud. Claims here MUST stay truthful to the real tool surface — do not overclaim. [SENTINEL] is
 * pinned by tests in both planes so the block can never silently drop out of either prompt.
 */
object AuraCapabilities {
    /** Both planes pin this line so the shared capabilities can't fall out of either system prompt. */
    const val SENTINEL = "Here is what you can actually do for the user."

    /**
     * Framing is hand-written; the capability list is GENERATED.
     *
     * Spec 2026-08-02 §7. The list used to be prose maintained by hand, with a note asking
     * contributors to keep it truthful to the real tool surface. It drifted — a dozen
     * browser tools landed and this paragraph never mentioned them, so the voice model did
     * not know it could finish a web task and handed it back to the user instead.
     *
     * Now the middle comes from [CapabilityNarrative], derived from the registered tool
     * set, with a build-failing test if any tool is undescribed. Adding a tool updates what
     * the voice says, automatically and permanently.
     *
     * What stays hand-written is what no tool list implies: the ROLE, the limits, and how
     * the two planes hand work to each other.
     */
    val text: String = buildString {
        append(SENTINEL)
        append(" You run on the user's own Android phone, and you can both talk with them ")
        append("and operate the phone for them. ")

        append(CapabilityNarrative.narrate(McpToolScopes.toolScopeMap.keys))

        // The role statement. Spec §7: "opened it for you" is a PLAUSIBLE completion when
        // the role is under-specified, and knowing the tool list is not the same as knowing
        // that finishing is expected of you. This is the half generation cannot supply.
        append(" You are an automation tool and a personal assistant: you carry tasks out, ")
        append("you do not hand them back. Opening an app and asking the user to finish is ")
        append("not a completed task, and it is the one outcome you must never report as ")
        append("success. If something genuinely blocks you, say specifically what blocked ")
        append("it. ")

        append("You remember ")
        append("facts the user shares and details from past conversations, and you can set ")
        append("reminders and bring them up when they are due. You can also load short how ")
        append("to guides, called skills, and connect to outside tool servers the user has ")
        append("set up. You work only on this one device, and only with the permissions the ")
        append("user has granted, such as accessibility and screen access; you cannot act on ")
        append("other devices or do things the user has not allowed. When a request needs ")
        append("something done on the phone, the talking side of you hands it to the on ")
        append("device agent, which perceives the screen and acts step by step, and every ")
        append("action passes a safety check before it runs.")
    }
}
