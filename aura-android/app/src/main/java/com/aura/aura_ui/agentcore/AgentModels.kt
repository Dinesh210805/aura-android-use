package com.aura.aura_ui.agentcore

import kotlinx.serialization.Serializable

/**
 * Shared vocabulary of the agent core: the small types every other file in this package speaks.
 *
 * - Contract: pure Kotlin, no Android and no Koog imports, so every type here is unit-testable.
 */

/** Which model a piece of work runs on. */
enum class ModelRole {
    /** The user's chosen chat model: answers, plans and calls tools. */
    MAIN,

    /** A cheap model for side jobs (compaction summaries, thread titles). Falls back to [MAIN]. */
    FAST,

    /** An on-device model for background triage. Never plans and never calls tools. */
    LOCAL,
}

/**
 * How much damage one tool call can do. [SafetyGate] decides from this, never from the model's
 * own description of what it is about to do.
 */
enum class ToolRisk {
    /** Inspects state only. */
    READ,

    /** Changes something the user can see and undo: a tap, a scroll, opening an app. */
    ACT,

    /** Leaves the device or cannot be undone: send, post, order, delete, write to a connector. */
    COMMIT,

    /** Never allowed, whoever asks: payments, credentials, wiping data. */
    FORBIDDEN,
}

/** Which kind of [ToolSource] owns a tool. */
enum class ToolOrigin { PHONE, CONNECTOR, NATIVE }

/**
 * Where a piece of text came from. Only [trusted] origins may carry instructions; everything else
 * enters the prompt inside a [Fence] and marks the turn as tainted.
 */
enum class Origin(val trusted: Boolean) {
    USER(true),
    PROFILE(true),
    SCREEN(false),
    NOTIFICATION(false),
    WEB(false),
    CONNECTOR(false),
    MEMORY(false),

    /** Past conversations found by `search_history`: they can quote anything a screen or file once held. */
    HISTORY(false),

    /** Files, contacts and the clipboard: private, and written by whoever sent them. */
    FILE(false),
}

/** Whether a person is watching the turn. Background turns may only read. */
enum class RunMode { FOREGROUND, BACKGROUND }

/** A file the user attached to a message. [uri] is a content URI the app holds a read grant for. */
@Serializable
data class Attachment(
    val uri: String,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/** What one row of a thread's visible transcript is. */
enum class ItemKind {
    USER, ASSISTANT, TOOL, PROMPT, NOTICE,

    /** A [ChatCard]; the row's text is the card as JSON ([ChatCard.encode]). */
    CARD,
}

/** Why a turn stopped. */
enum class TurnEnd {
    /** The model replied with text. */
    ANSWERED,

    /** The user or the app cancelled it. */
    CANCELLED,

    /** A step or time ceiling was reached; the reply is the model's closing summary. */
    LIMIT,

    /** The provider, the network or the harness failed. */
    FAILED,

    /**
     * The turn needs to drive the screen and was started where the user cannot watch it do so.
     * Nothing was done or saved; the caller runs the same request on the overlay.
     */
    HANDED_OFF,
}

/**
 * Ceilings for one turn.
 *
 * - Why steps and time, not tokens: a cumulative token sum re-counts the same prompt every step,
 *   so it ends long tasks that are still making progress. Loops are caught by the phone server's
 *   `ActionGuard`, which can tell repetition from progress.
 */
data class TurnLimits(
    val maxSteps: Int = 80,
    val maxWallClockMs: Long = 20 * 60_000L,
    /** Background turns are short by construction: nobody is there to stop them. */
    val backgroundMaxSteps: Int = 8,
)

/** What the engine tells the UI while a turn runs. One turn emits exactly one [TurnEnded], last. */
sealed interface AgentEvent {
    data class TurnStarted(val threadId: String, val modelLabel: String) : AgentEvent

    /** A complete assistant message. [final] is false for text the model wrote before a tool call. */
    data class AssistantText(val text: String, val final: Boolean) : AgentEvent

    data class ToolStarted(val callId: String, val tool: String, val argsPreview: String) : AgentEvent

    data class ToolFinished(
        val callId: String,
        val tool: String,
        val ok: Boolean,
        val resultPreview: String,
        val durationMs: Long,
    ) : AgentEvent

    /** The turn is waiting for the user. Answer through [UserBroker.answer] with one of [options] or free text. */
    data class UserPromptRequested(val promptId: String, val question: String, val options: List<String>) : AgentEvent

    /** Something to show the user as a card: files that were found, contacts, a result. */
    data class Card(val card: ChatCard) : AgentEvent

    /** The model's plan for this task: [steps] in order, the first [done] of them finished. */
    data class PlanUpdated(val steps: List<String>, val done: Int) : AgentEvent

    data class TurnEnded(val end: TurnEnd, val message: String?) : AgentEvent
}
