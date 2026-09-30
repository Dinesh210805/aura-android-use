package com.aura.aura_ui.agent.mcpbridge.hooks

/**
 * The guard pipeline as a user-facing catalog — the single source the **Hooks
 * transparency screen** renders, co-located with the chain it documents.
 *
 * The real pipeline spans two modules: app-side [PreToolHook]/[PostToolHook]s
 * assembled in `buildMcpToolRegistry` (ArgSanity → ActionGuard → ConfirmDestructive
 * / ActionGuard-observe → LearningsWrite → LedgerUpdate) and server-side guards in
 * the `mcp-server` module (scope check, `SensitivePolicy`, foreground gate, screen
 * settle) that run inside `scopedTool`. Because those live across a module boundary
 * and aren't all hook *objects*, this catalog is the one maintained list — add a
 * guard to the chain, add its entry here, and it shows in the app with no UI edit.
 */
enum class HookPhase { BEFORE, AFTER }

data class HookInfo(
    val phase: HookPhase,
    /** Stable key the UI maps to an icon; also handy for tests. */
    val key: String,
    val name: String,
    val description: String,
)

object HookCatalog {

    val before: List<HookInfo> = listOf(
        HookInfo(
            phase = HookPhase.BEFORE,
            key = "arg_sanity",
            name = "Argument Check",
            description = "Rejects malformed or plainly-invalid tool calls before they " +
                "cost anything — an obviously wrong call never reaches the device.",
        ),
        HookInfo(
            phase = HookPhase.BEFORE,
            key = "action_guard",
            name = "Action Guard",
            description = "The agent must have seen the current screen before acting on it; " +
                "detects loops; requires verification before finishing.",
        ),
        HookInfo(
            phase = HookPhase.BEFORE,
            key = "scope_guard",
            name = "Scope Guard",
            description = "Every tool is classified READ or WRITE; calls outside the " +
                "session's scope are rejected. Unknown tools fail closed.",
        ),
        HookInfo(
            phase = HookPhase.BEFORE,
            key = "sensitive_policy",
            name = "Sensitive Policy",
            description = "Banking and payment apps are hard-blocked. Typed text, replies " +
                "and launch targets are screened before dispatch.",
        ),
        HookInfo(
            phase = HookPhase.BEFORE,
            key = "foreground_guard",
            name = "Foreground Guard",
            description = "Blocks in-app gestures and screen perception while a banking, " +
                "payment or authenticator app is in the foreground.",
        ),
        HookInfo(
            phase = HookPhase.BEFORE,
            key = "completion_gate",
            name = "Completion Gate",
            description = "Refuses \"done\" until the proof matches the task: for a job with a " +
                "count, every item must be recorded with the screen text behind it, and the " +
                "final answer is checked against that evidence.",
        ),
        HookInfo(
            phase = HookPhase.BEFORE,
            key = "confirm_destructive",
            name = "Confirm Destructive",
            description = "Pauses and asks you first before an action that can't be easily " +
                "undone, like sending or deleting.",
        ),
    )

    val after: List<HookInfo> = listOf(
        HookInfo(
            phase = HookPhase.AFTER,
            key = "screen_settle",
            name = "Screen Settle",
            description = "After every screen-mutating action, waits for the UI to calm " +
                "down and attaches a fresh observation to the result.",
        ),
        HookInfo(
            phase = HookPhase.AFTER,
            key = "action_guard_observe",
            name = "Action Guard (observe)",
            description = "Credits that observation back to the agent's grounding, so it " +
                "doesn't need an extra look after every action.",
        ),
        HookInfo(
            phase = HookPhase.AFTER,
            key = "observed_text",
            name = "Proof Recorder",
            description = "Keeps what AURA actually read this run, so any claim it makes can be " +
                "checked against the real screen text instead of taken on trust.",
        ),
        HookInfo(
            phase = HookPhase.AFTER,
            key = "learnings_writer",
            name = "Learnings Writer",
            description = "Saves successful paths as reusable app knowledge — after a PII " +
                "firewall so personal data never enters memory.",
        ),
        HookInfo(
            phase = HookPhase.AFTER,
            key = "run_ledger",
            name = "Run Ledger",
            description = "Records each step as it happens, so a task interrupted midway " +
                "can be safely resumed later.",
        ),
    )
}
