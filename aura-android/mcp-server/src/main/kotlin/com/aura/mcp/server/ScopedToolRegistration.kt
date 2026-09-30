package com.aura.mcp.server

import com.aura.mcp.bridge.LearningsGateway
import com.aura.mcp.bridge.McpAuditLogger
import com.aura.mcp.bridge.McpScope
import com.aura.mcp.bridge.NoOpLearningsGateway
import com.aura.mcp.cache.ScreenGeneration
import com.aura.mcp.bridge.NoOpAuditLogger
import com.aura.mcp.bridge.NoOpSessionLogSink
import com.aura.mcp.bridge.NoOpToolPhaseSink
import com.aura.mcp.bridge.SessionLogSink
import com.aura.mcp.bridge.ToolPhaseSink
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Cross-server live tool-phase sink (drives the host's status-bar chip).
 *
 * This one sink is DELIBERATELY shared across every server in the process: the
 * external WebRTC server AND the in-process agent server both want their tool
 * calls to animate the same chip. [McpServerBuilder.build] updates it only when
 * a caller supplies a non-null sink, so the in-process build (which passes null)
 * preserves whatever the host installed. See [ToolPhaseSinkPreservationTest].
 */
internal var phaseSink: ToolPhaseSink = NoOpToolPhaseSink

/**
 * GP1 — the per-server sink bundle. Everything EXCEPT the chip sink is
 * server-scoped: audit and forensic-session trails are distinct per server,
 * and the settle/observe hook binds that server's device bridge.
 *
 * Previously these were process-global `var`s clobbered by the last
 * [McpServerBuilder.build], so starting an agent run (which builds a second,
 * in-process server with no audit/session sinks) silently switched OFF the
 * external WebRTC server's real audit + forensic trails until it reconnected.
 * Now each server owns its bundle; a later build creates a fresh entry and can
 * never reach the already-registered handlers of an earlier server.
 */
internal data class ServerSinks(
    val audit: McpAuditLogger = NoOpAuditLogger,
    val phase: ToolPhaseSink = NoOpToolPhaseSink,
    val session: SessionLogSink = NoOpSessionLogSink,
    val observer: PostActionObserver = NoOpPostActionObserver,
    // GP5+GP6 — foreground-package gate; NOOP for hosts without a UI tree.
    val gate: ForegroundGate = ForegroundGate.NOOP,
    // Spec 2026-07-31 — control lock; NOOP where there is no human to yield to.
    val lock: ControlLockGate = ControlLockGate.NOOP,
    // Spec 2026-07-31 — arms that lock. NOOP for the in-process agent server, which
    // reports its own run boundaries instead of being inferred from call traffic.
    val driver: DriverActivitySink = DriverActivitySink.NOOP,
    /**
     * Spec 2026-07-31 — is the user currently using the visible browser window?
     *
     * A lambda rather than the engine itself: only the engine that owns the window knows,
     * and this layer must not learn what an engine is. Defaults to "no handoff", which is
     * the right answer for every host without a browser.
     */
    val browserHandedOff: () -> Boolean = { false },
    // Spec 2026-07-17 — two-way shared memory; NoOp keeps pre-seam behavior.
    val learnings: LearningsGateway = NoOpLearningsGateway,
    /** Spec 2026-09-23 — every gate and post step, for the host's run log. */
    val trail: ToolTrailSink = ToolTrailSink.NOOP,
)

/**
 * Keyed by the [Server] instance. WeakHashMap so a per-run in-process server's
 * entry is collected with the server — no leak across many agent runs. Only
 * touched during build (write) and tool registration (read), both inside the
 * synchronous build flow; synchronized for defensiveness.
 */
private val serverSinks: MutableMap<Server, ServerSinks> =
    java.util.Collections.synchronizedMap(java.util.WeakHashMap())

/** GP1 — install this server's sinks; call in [McpServerBuilder.build] before registering tools. */
internal fun Server.installSinks(sinks: ServerSinks) {
    serverSinks[this] = sinks
}

/**
 * GP1 — this server's sinks. [scopedTool] and `registerEndSessionTool` call
 * this at REGISTRATION time and capture the result into their handler closures,
 * so dispatch never reads mutable shared state.
 */
internal fun Server.sinks(): ServerSinks = serverSinks[this] ?: ServerSinks()

/**
 * T4 — ground truth of every tool name that passed through [scopedTool] (the single
 * registration chokepoint). Exists so the name-list snapshot test can assert the
 * hand-maintained lists (scope map, observed-tools set, client guard lists) cover the
 * actually-registered set instead of drifting silently. Process-global like the sinks
 * above (one logical server per process).
 */
internal object RegisteredToolNames {
    val names: MutableSet<String> = linkedSetOf()

    /**
     * What each tool costs the model on every request: its description and the text inside its
     * input schema, keyed by name.
     *
     * Recorded here rather than parsed out of the source, because a description assembled at
     * registration (interpolated action lists, generated narratives) is invisible to a regex and
     * would silently escape the budget. `scripts/tool_desc_budget.py` does read the source — it
     * is the fast loop while editing; `ToolDescriptionBudgetTest` is the one that gates.
     */
    val descriptions: MutableMap<String, Pair<String, String>> = linkedMapOf()
}

/**
 * Tools whose results can carry a verification code the device was just sent.
 *
 * Notification and message readers, plus the two screen reads — an OTP sitting in a visible SMS
 * preview reaches the model through `read_screen` just as readily as through
 * `read_notifications`, and a policy that only watched the shade would be trivially stepped
 * around by looking at the screen instead.
 */
private val OTP_SOURCE_TOOLS = setOf(
    "read_notifications",
    "read_screen",
    "perceive_screen",
)

/**
 * Drop-in replacement for [Server.addTool] that wraps every handler with:
 *  1. Per-tool **scope check** against [McpToolScopes] using the caller's
 *     [com.aura.mcp.bridge.TokenPrincipal] (read from the coroutine context
 *     by [currentMcpPrincipalOrNull]).
 *  2. **Audit logging** of every call — name, principal id, success flag,
 *     duration, and short error string if the handler returned `isError`.
 *
 * Why a wrapper instead of a routing-level interceptor: the MCP SDK owns
 * SSE body parsing and JSON-RPC dispatch, so the tool name only becomes
 * known inside the SDK's pipeline.
 */
internal fun Server.scopedTool(
    name: String,
    description: String,
    inputSchema: ToolSchema = ToolSchema(),
    handler: suspend (CallToolRequest) -> CallToolResult,
) {
    RegisteredToolNames.names += name
    RegisteredToolNames.descriptions[name] = description to inputSchema.properties.toString()
    // GP1 — capture THIS server's sinks now (registration time); the handler
    // closure below reads only these locals, never mutable shared state.
    val sinks = sinks()
    val required = McpToolScopes.requiredScopeFor(name)
    addTool(
        name = name,
        description = description,
        inputSchema = inputSchema,
    ) { request ->
        // Before every gate, including the ones about to refuse this call: a client that
        // keeps calling into a paused phone is still driving it, and if refusals did not
        // count the driving window would lapse mid-pause and un-arm the lock underneath
        // the user.
        sinks.driver.onToolDispatched()

        val principal = currentMcpPrincipalOrNull()
        val tokenId = principal?.tokenId
        val started = System.currentTimeMillis()
        val trail = ToolTrail(name, sinks.trail)

        if (principal != null && required !in principal.scopes) {
            trail.step("gate", "Scope", "refuse", "needs ${required.name.lowercase()}")
            val result = scopeDeniedResult(name, required, principal.scopes)
            sinks.phase.onPhase(name, required, ToolPhaseSink.Phase.SCOPE_DENIED)
            sinks.audit.log(
                toolName = name,
                tokenId = tokenId,
                success = false,
                scopeDenied = true,
                durationMs = System.currentTimeMillis() - started,
                errorSummary = "scope_denied (required=${required.name.lowercase()})",
            )
            return@addTool result
        }

        // Policy gate — hard-block sensitive actions (banking/auth apps,
        // banking deep-link hosts, sensitive text entry). Runs after the scope
        // check and BEFORE dispatch, so every tool routes through one chokepoint
        // and none can bypass it. Fail-closed: local deterministic eval.
        when (val decision = SensitivePolicy.evaluate(name, request.arguments)) {
            is SensitivePolicy.Decision.Block -> {
                trail.step("gate", "SensitivePolicy", "refuse", decision.category.id)
                val result = policyBlockedResult(name, decision)
                sinks.phase.onPhase(name, required, ToolPhaseSink.Phase.SCOPE_DENIED)
                sinks.audit.log(
                    toolName = name,
                    tokenId = tokenId,
                    success = false,
                    scopeDenied = false,
                    durationMs = System.currentTimeMillis() - started,
                    errorSummary = "policy_blocked:${decision.category.id}",
                )
                return@addTool result
            }
            SensitivePolicy.Decision.Allow -> {
                trail.step("gate", "Scope", "pass", required.name.lowercase())
                trail.step("gate", "SensitivePolicy", "pass")
            }
        }

        // Spec 2026-07-31 — control lock: the human always wins. Refuse everything,
        // READ included, while the user has the wheel.
        //
        // Placed here on purpose:
        //  • AFTER scope and SensitivePolicy, because those are permanent "this will
        //    never work" verdicts and a client is better served hearing them. Pause is
        //    temporary, so it is the less useful thing to report when both apply.
        //  • BEFORE the foreground gate, which snapshots the UI tree for gated tools.
        //    Paying for a tree read on a call we are about to refuse because the user
        //    picked up their phone would be exactly backwards.
        sinks.lock.check()?.let { block ->
            trail.step("gate", "ControlLock", "refuse", "the user has the phone")
            val result = pausedByUserResult(name, block)
            // Deliberately NO phase sink here. The spec is explicit that while paused the
            // status pill HIDES — the agent is not doing anything, so a "doing" chip would
            // be a lie, and the Pause pill owns the screen instead. Auditing still happens:
            // a refusal is a real event worth a record even when it is not worth a pixel.
            sinks.audit.log(
                toolName = name,
                tokenId = tokenId,
                success = false,
                scopeDenied = false,
                durationMs = System.currentTimeMillis() - started,
                errorSummary = "paused_by_user",
            )
            return@addTool result
        }

        trail.step("gate", "ControlLock", "pass")

        // Spec 2026-07-31 — the BROWSER handoff. Narrower than the lock above and
        // deliberately so: the user has taken the browser, not the phone.
        //
        // Only WRITE is refused. That asymmetry is required rather than convenient — the
        // handoff ends when the login form is gone, which can only be observed by READING
        // the page, so a gate that blocked reads would make every handoff permanent. See
        // [BrowserHandoff] for the full argument, including why the human lock above is
        // right to have no exemptions at all while this one must.
        if (name.startsWith("browser_")) {
            val verdict = BrowserHandoff.evaluate(sinks.browserHandedOff(), required)
            trail.step("gate", "BrowserHandoff", if (verdict is BrowserHandoff.Verdict.Block) "refuse" else "pass")
            if (verdict is BrowserHandoff.Verdict.Block) {
                val result = CallToolResult(
                    content = listOf(TextContent(verdict.message)),
                    isError = true,
                )
                sinks.audit.log(
                    toolName = name,
                    tokenId = tokenId,
                    success = false,
                    scopeDenied = false,
                    durationMs = System.currentTimeMillis() - started,
                    errorSummary = "browser_handed_off",
                )
                return@addTool result
            }
        }

        // GP5+GP6 — foreground-package gate: deny in-app gestures and screen
        // perception while a banking/payment/authenticator app is foreground.
        // Entry blocking alone is bypassable (icon-tap from the launcher, GP6)
        // and perception leaks what accessibility masking hides (GP5/P4).
        sinks.gate.check(name)?.let { block ->
            trail.step("gate", "ForegroundGate", "refuse", block.category.id)
            val result = foregroundBlockedResult(name, block)
            sinks.phase.onPhase(name, required, ToolPhaseSink.Phase.SCOPE_DENIED)
            sinks.audit.log(
                toolName = name,
                tokenId = tokenId,
                success = false,
                scopeDenied = false,
                durationMs = System.currentTimeMillis() - started,
                errorSummary = "foreground_blocked:${block.category.id}",
            )
            return@addTool result
        }

        // Fire STARTED only after auth/scope/policy passed — the chip should
        // never flip to "Acting" for a call we're about to reject.
        sinks.phase.onPhase(name, required, ToolPhaseSink.Phase.STARTED)
        sinks.session.onToolStart(
            toolName = name,
            argsJson = request.arguments?.toString(),
            tokenId = tokenId,
            agentLabel = principal?.label,
        )

        trail.step("gate", "ForegroundGate", "pass")
        val dispatched = System.currentTimeMillis()
        val result = runCatching { kotlinx.coroutines.withContext(trail) { handler(request) } }
            .also { r ->
                trail.step(
                    "dispatch", name,
                    when {
                        r.isFailure -> "threw"
                        r.getOrNull()?.isError == true -> "error"
                        else -> "ran"
                    },
                    r.exceptionOrNull()?.message,
                    System.currentTimeMillis() - dispatched,
                )
            }
            // P1: any dispatched WRITE-scoped tool may have mutated the screen —
            // bump the generation so cached som_ids stop resolving until the next
            // perceive. Runs on handler failure too (a timed-out gesture may still
            // have landed); never runs for scope-denied/policy-blocked calls,
            // which return before dispatch.
            .also {
                if (required == McpScope.WRITE) {
                    ScreenGeneration.bump()
                    trail.step("server-post", "ScreenGeneration", "info", "bumped: earlier som_ids now stale")
                }
            }
            // A look, recorded at the same chokepoint and in the same order as the bump above,
            // so "did the agent see the screen as it is now" is answerable without either side
            // keeping its own history. Only on success: a perception that threw saw nothing.
            // See [CompletionEvidence] for what this gates and why it is narrow.
            .also { r ->
                if (name in CompletionEvidence.LOOK_TOOLS && r.isSuccess && r.getOrNull()?.isError != true) {
                    CompletionEvidence.noteLook()
                    trail.step("server-post", "CompletionEvidence", "info", "look recorded")
                }
            }
            // Remember any verification code the device just showed us, so the outgoing-text
            // policy can refuse to send it back out. Here rather than inside each tool: this is
            // the one place every result provably passes, and a reader added later (a new way
            // to read messages) inherits the protection instead of quietly escaping it.
            // See [OtpSighting] for why sighted codes and not code-shaped numbers.
            .also { r ->
                if (name in OTP_SOURCE_TOOLS) {
                    r.getOrNull()?.content
                        ?.filterIsInstance<TextContent>()
                        ?.forEach { OtpSighting.noteSeen(it.text) }
                }
            }
            .getOrElse { t ->
                val err = (t.message ?: t::class.simpleName ?: "unknown").take(200)
                sinks.phase.onPhase(name, required, ToolPhaseSink.Phase.ERRORED)
                sinks.audit.log(
                    toolName = name,
                    tokenId = tokenId,
                    success = false,
                    scopeDenied = false,
                    durationMs = System.currentTimeMillis() - started,
                    errorSummary = err,
                )
                // Shared memory — a handler throw is a dead-end signal too.
                LearningsDispatch.stream(
                    sinks.learnings, name, request.arguments,
                    CallToolResult(content = emptyList(), isError = true),
                    failed = true, clientLabel = principal?.label,
                )
                throw t // let MCP SDK turn it into an error response
            }

        // E1+E2 — for screen-mutating WRITE tools that dispatched successfully:
        // wait for event quiescence (settle), then bundle the compact post-state
        // into the result so the model verifies + plans in one round trip.
        // Runs AFTER the generation bump: the observation is a summary, not a
        // perceive — cached som_ids stay stale until a real perceive_screen.
        val observedResult = if (required == McpScope.WRITE && result.isError != true &&
            PostActionObservation.observes(name, request.arguments)
        ) {
            val settleStarted = System.currentTimeMillis()
            sinks.observer.observe(name)
                ?.also { obs ->
                    trail.step(
                        "server-post", "ScreenSettle", "info",
                        listOfNotNull(
                            obs["screen_changed"]?.let { "changed=$it" },
                            obs["settled"]?.let { "settled=$it" },
                            obs["foreground_app"]?.let { "app=$it" },
                        ).joinToString(" "),
                        System.currentTimeMillis() - settleStarted,
                    )
                }
                ?.let { obs -> PostActionObservation.appendTo(result, obs) }
                ?: result
        } else {
            result
        }

        // Spec 2026-07-17 — shared memory READ lane: entering an app is the moment
        // its learned know-how is relevant. Fail-soft; no-op for non-hint tools.
        val finalResult = LearningsDispatch.withLearnedHints(
            sinks.learnings, name, request.arguments, observedResult,
        )

        val isError = finalResult.isError == true
        val durationMs = System.currentTimeMillis() - started
        sinks.phase.onPhase(
            name,
            required,
            if (isError) ToolPhaseSink.Phase.ERRORED else ToolPhaseSink.Phase.COMPLETED,
        )
        sinks.session.onToolEnd(
            toolName = name,
            success = !isError,
            outputSummary = extractOutputSummary(finalResult, isError),
            durationMs = durationMs,
        )
        sinks.audit.log(
            toolName = name,
            tokenId = tokenId,
            success = !isError,
            scopeDenied = false,
            durationMs = durationMs,
            errorSummary = if (isError) extractErrorSummary(finalResult) else null,
        )
        // Spec 2026-07-17 — shared memory WRITE lane: stream the server's own
        // dispatch evidence (post-observation result included) to the gateway.
        LearningsDispatch.stream(
            sinks.learnings, name, request.arguments, finalResult,
            failed = isError, clientLabel = principal?.label,
        )
        finalResult
    }
}

/**
 * Pull the first text content out of a tool result, truncated. Used by
 * the session sink to record a preview of every tool's output without
 * pulling the full payload (some tools return base64 images that would
 * balloon the log).
 */
private fun extractOutputSummary(result: CallToolResult, isError: Boolean): String {
    val firstText = result.content.firstOrNull { it is TextContent } as? TextContent
    val raw = firstText?.text ?: if (isError) "<error>" else "<no text content>"
    return raw.take(OUTPUT_SUMMARY_MAX_CHARS)
}

private const val OUTPUT_SUMMARY_MAX_CHARS = 2_000

private fun extractErrorSummary(result: CallToolResult): String? {
    val firstText = result.content.firstOrNull { it is TextContent } as? TextContent
    return firstText?.text?.take(200)
}

private fun policyBlockedResult(
    toolName: String,
    block: SensitivePolicy.Decision.Block,
): CallToolResult = CallToolResult(
    content = listOf(
        TextContent(
            buildJsonObject {
                put("success", false)
                put("error", "policy_blocked")
                put("tool", toolName)
                put("category", block.category.id)
                put("message", block.message)
                put(
                    "hint",
                    "This is a hard safety block enforced on-device. Do not retry " +
                        "or attempt a workaround — tell the user to perform this action themselves.",
                )
            }.toString(),
        ),
    ),
    isError = true,
)

/**
 * Spec 2026-07-31 — refusal shape for "the human took the wheel".
 *
 * `paused_by_user` is a distinct error code on purpose. `scope_denied`,
 * `policy_blocked` and `foreground_blocked` all mean *this will never work*; this one
 * means *this works fine in a minute*. A client that cannot tell them apart either gives
 * up on a recoverable pause or hammers away at a hard block — and the hint exists because
 * the default reaction to an error is an immediate retry, which here is the agent
 * fighting its user for their own phone at the exact moment they reached for it.
 */
internal fun pausedByUserResult(
    toolName: String,
    block: ControlLock.Verdict.Block,
): CallToolResult = CallToolResult(
    content = listOf(
        TextContent(
            buildJsonObject {
                put("success", false)
                put("error", "paused_by_user")
                put("tool", toolName)
                put("paused_by_user", true)
                put("message", block.message)
                put(
                    "hint",
                    "This is not a failure and not a permissions problem — the user is " +
                        "simply using their own phone. Do NOT retry in a loop and do NOT " +
                        "look for a workaround. Wait for them to finish, or tell them what " +
                        "you were doing and ask them to resume when they're ready.",
                )
            }.toString(),
        ),
    ),
    isError = true,
)

private fun foregroundBlockedResult(
    toolName: String,
    block: ForegroundGuard.Verdict.Block,
): CallToolResult = CallToolResult(
    content = listOf(
        TextContent(
            buildJsonObject {
                put("success", false)
                put("error", "foreground_blocked")
                put("tool", toolName)
                put("category", block.category.id)
                put("message", block.message)
                put(
                    "hint",
                    "This is a hard safety block enforced on-device. Do not retry " +
                        "or attempt a workaround — press_back or press_home to leave " +
                        "the app, then tell the user to complete this step themselves.",
                )
            }.toString(),
        ),
    ),
    isError = true,
)

private fun scopeDeniedResult(
    toolName: String,
    required: McpScope,
    held: Set<McpScope>,
): CallToolResult = CallToolResult(
    content = listOf(
        TextContent(
            buildJsonObject {
                put("success", false)
                put("error", "scope_denied")
                put("tool", toolName)
                put("required_scope", required.name.lowercase())
                putJsonArray("held_scopes") {
                    held.forEach { add(it.name.lowercase()) }
                }
                put(
                    "hint",
                    "This client paired with read-only scope. Re-pair from " +
                        "AURA Settings → MCP Pairing and enable 'Allow device control' " +
                        "to issue a write-capable token.",
                )
            }.toString(),
        ),
    ),
    isError = true,
)
