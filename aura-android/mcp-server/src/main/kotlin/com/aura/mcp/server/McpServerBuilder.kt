package com.aura.mcp.server

import com.aura.mcp.bridge.ContactsBridge
import com.aura.mcp.bridge.DeepLinkBridge
import com.aura.mcp.bridge.FilesBridge
import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.bridge.LearningsGateway
import com.aura.mcp.bridge.McpAuditLogger
import com.aura.mcp.bridge.NoOpLearningsGateway
import com.aura.mcp.bridge.MediaBridge
import com.aura.mcp.bridge.NoOpSessionLogSink
import com.aura.mcp.bridge.NotificationBridge
import com.aura.mcp.bridge.PerceptionBridge
import com.aura.mcp.bridge.ScreenshotBridge
import com.aura.mcp.bridge.SessionLogSink
import com.aura.mcp.bridge.SystemIntentBridge
import com.aura.mcp.bridge.ToolPhaseSink
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.bridge.BrowserBridge
import com.aura.mcp.bridge.WebSearchBridge
import com.aura.mcp.cache.PerceptionCache
import com.aura.mcp.tools.registerAppTools
import com.aura.mcp.tools.registerDeepLinkTools
import com.aura.mcp.tools.registerDeviceTools
import com.aura.mcp.tools.registerEchoTool
import com.aura.mcp.tools.registerEndSessionTool
import com.aura.mcp.tools.registerGetUsageGuideTool
import com.aura.mcp.tools.registerGestureTools
import com.aura.mcp.tools.registerKeyTools
import com.aura.mcp.tools.registerMediaTools
import com.aura.mcp.tools.registerNotificationTools
import com.aura.mcp.tools.registerContactTools
import com.aura.mcp.tools.registerFileTools
import com.aura.mcp.tools.registerPerceiveScreenTool
import com.aura.mcp.tools.registerPerceptionTools
import com.aura.mcp.tools.registerReadScreenTool
import com.aura.mcp.tools.registerPolicyTools
import com.aura.mcp.tools.registerSystemIntentTool
import com.aura.mcp.tools.registerVerifyActionTool
import com.aura.mcp.tools.registerWaitForTool
import com.aura.mcp.tools.registerBrowserTools
import com.aura.mcp.tools.registerWebSearchTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * Pure factory for the MCP [Server] instance.
 *
 * Kept separate from [com.aura.mcp.McpServerController] so future bridge-wiring
 * tests can construct the same server without spinning up a Ktor engine.
 */
internal object McpServerBuilder {

    fun build(
        serverName: String,
        version: String,
        deviceBridge: DeviceBridge,
        screenshotBridge: ScreenshotBridge,
        uiTreeBridge: UiTreeBridge,
        perceptionBridge: PerceptionBridge,
        webSearchBridge: WebSearchBridge,
        deepLinkBridge: DeepLinkBridge,
        notificationBridge: NotificationBridge,
        mediaBridge: MediaBridge,
        systemIntentBridge: SystemIntentBridge,
        // Nullable: hosts without contact resolution simply don't register the
        // resolve_contact tool (the agent then routes via the Contacts app UI).
        contactsBridge: ContactsBridge? = null,
        filesBridge: FilesBridge? = null,
        // Nullable for the same reason: a host with no browser surface simply
        // doesn't register the browser_* family rather than registering tools
        // that always fail.
        browserBridge: BrowserBridge? = null,
        auditLogger: McpAuditLogger,
        // Nullable, default null = "leave the installed sink alone". `phaseSink` is a
        // process-global, and TWO servers are built per process (the external WebRTC
        // server AND the agent's in-process server). The in-process build omits this arg,
        // so it must NOT reset the sink the external server installed — otherwise the
        // status-bar verb freezes during every on-device agent run. See
        // ToolPhaseSinkPreservationTest.
        toolPhaseSink: ToolPhaseSink? = null,
        sessionLogSink: SessionLogSink = NoOpSessionLogSink,
        // Spec 2026-07-17 — two-way shared memory; NoOp = pre-seam behavior.
        learningsGateway: LearningsGateway = NoOpLearningsGateway,
        /** Spec 2026-09-23 — per-call gate/post trail for the host's run log. */
        trailSink: ToolTrailSink = ToolTrailSink.NOOP,
        // Spec 2026-07-17 — device skills exposed as MCP prompts (slash-commands).
        promptSkills: List<PromptSkill> = emptyList(),
        /**
         * The host app's own package name — its overlay chip animates on every
         * tool call, so its accessibility events must not count as screen
         * activity when settling (E1). Null disables the filter.
         */
        ownPackageName: String? = null,
        /**
         * Spec 2026-07-31 — control lock. Reports whether the human currently has the
         * wheel; every tool call is refused while they do.
         *
         * Null = no human to yield to (unit tests, non-device hosts). Deliberately a
         * plain lambda rather than a bridge interface: the answer is one in-memory flag,
         * and giving it a whole port would imply state this layer has no business owning.
         *
         * It must be passed to BOTH servers built in this process — the external
         * WebRTC server and the agent's in-process one. The spec's ordering is
         * `human > local agent > MCP client`, so a pause that only reached remote clients
         * would leave the on-device agent still driving, which is the wrong half.
         */
        pausedByHuman: (() -> Boolean)? = null,
        /** Whether `read_screen` attaches its annotated screenshot. Read per call, so a toggle applies at once. */
        readScreenImage: () -> Boolean = { false },
        /**
         * Spec 2026-07-31 — *arms* the control lock. Fires on every tool call.
         *
         * Pass this on the server that faces **remote** clients only. That server's call
         * traffic is the sole evidence this process has that a PC is driving the phone,
         * because a remote client has no run lifecycle to report. The agent's in-process
         * server should leave it null and announce its own run boundaries instead —
         * inferring "the local agent is busy" from its own tool calls would keep the lock
         * armed through the gaps between them, which is where the user actually picks the
         * phone up.
         *
         * Null = nothing to arm (unit tests, non-device hosts). See [DriverActivitySink].
         */
        onToolDispatched: (() -> Unit)? = null,
    ): Server {
        // Perception cache: stores som_id → (center_x, center_y) mappings from
        // the latest perceive_screen call. Allows tap/double_tap/long_press tools
        // to resolve som_id to full-resolution coordinates server-side, avoiding
        // coordinate-mismatch errors when the model eyeballs a downscaled image.
        // The rescue probe: when a counter claims the screen is dirty, ask the screen
        // itself before invalidating som_ids. Costs one tree read, and only on the
        // dirty path — it buys back the perceives that a no-pixel WRITE tool (volume,
        // media transport) or an unrelated notification used to throw away.
        val perceptionCache = PerceptionCache(
            screenProbe = {
                val snap = uiTreeBridge.snapshot()
                if (snap.ok) snap.payloadJson else null
            },
        )
        val cvMemo = com.aura.mcp.cache.CvMemo()
        // Shared "when did we last look at the screen" baseline. Written by
        // perceive_screen and by every post-action settle, so change detection always
        // compares against the most recent observation from EITHER route.
        val lastSeenScreen = com.aura.mcp.cache.LastSeenScreen()

        // Phase 9 — the live tool-phase sink drives the status-bar chip and is
        // DELIBERATELY shared across servers. Only install when the caller
        // supplies one; a null (the in-process agent server) preserves the host's
        // sink so the agent's own tool calls keep driving the verb.
        if (toolPhaseSink != null) phaseSink = toolPhaseSink
        // E1+E2 — settle-then-observe after every screen-mutating WRITE tool:
        // event-quiescence wait (no more wait_for round trips for ordinary
        // transitions) + compact post-state bundled into the gesture result.
        val postObserver = PostActionObservation.observer(
            bridge = uiTreeBridge,
            config = ScreenSettle.Config(ignoredPackages = setOfNotNull(ownPackageName)),
            // Compare against the last time the server LOOKED — which is the previous
            // gesture's settle in a multi-step chain, not the last perceive.
            lastSeen = lastSeenScreen,
        )

        // GP5+GP6 — foreground gate reads the live tree root package, only for
        // gated tools (see ForegroundGuard), on every server (external + in-process).
        val foregroundGate = ForegroundGate.fromUiTree(uiTreeBridge)

        // Phase 9 → spec 2026-07-17: deliver the SURVIVAL KIT at `initialize`,
        // not the full playbook — clients truncate instructions (~2 KB in
        // Claude Code; only 15% of the 13.7 KB playbook survived, measured
        // 2026-07-17). The kit is self-sufficient and points to get_usage_guide
        // + the aura://guide resource for the full contract.
        val server = Server(
            serverInfo = Implementation(name = serverName, version = version),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    // Resources (read-only context) + Prompts (workflow templates)
                    // are static — no change notifications, no subscriptions.
                    resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                ),
            ),
            instructions = AuraInstructions.survivalKit,
        )

        // GP1 — bind this server's sinks BEFORE any tool registration, so
        // scopedTool() captures them into each handler closure. A later build
        // (the per-run in-process server) gets its own map entry and can never
        // clobber this server's already-registered handlers. `phase` carries the
        // effective (possibly host-preserved) chip sink resolved just above.
        server.installSinks(
            ServerSinks(
                audit = auditLogger,
                phase = phaseSink,
                session = sessionLogSink,
                observer = postObserver,
                gate = foregroundGate,
                lock = pausedByHuman?.let { ControlLockGate(it) } ?: ControlLockGate.NOOP,
                driver = onToolDispatched?.let { DriverActivitySink(it) } ?: DriverActivitySink.NOOP,
                // Read fresh on every call, never snapshotted: a handoff starts and ends
                // in the middle of a run, so a captured boolean would be stale by design.
                browserHandedOff = { browserBridge?.isHandedOff == true },
                learnings = learningsGateway,
                trail = trailSink,
            ),
        )

        // Phase 1 — proof of life
        server.registerEchoTool()

        // Phase 2 — first five real device-control tools
        server.registerDeviceTools(deviceBridge, perceptionCache)

        // Phase 3 — gestures, apps, keys, and policy stubs
        server.registerGestureTools(deviceBridge, perceptionCache)
        server.registerAppTools(deviceBridge, perceptionCache)
        server.registerKeyTools(deviceBridge)
        // Pass lookup so validate_action resolves app NAMES to packages and screens
        // the resolved package (raw names match no package pattern) — advisory verdict
        // then matches the enforcing launch_app gate.
        server.registerPolicyTools(deviceBridge::lookupApp)

        // Phase 4 — screenshot, events
        server.registerPerceptionTools(screenshotBridge, uiTreeBridge)

        // 2026-08-18 — read_screen: the cheap settled look, and the agent's default.
        // Shares perceptionCache (so read_screen → tap grounds without a perceive) and
        // lastSeenScreen (a read IS a look) with perceive_screen and the post-action
        // settle. Same settle config as the post-action observer — one definition of
        // "quiet" for the whole server.
        server.registerReadScreenTool(
            uiTreeBridge = uiTreeBridge,
            perceptionCache = perceptionCache,
            settleConfig = ScreenSettle.Config(ignoredPackages = setOfNotNull(ownPackageName)),
            lastSeenScreen = lastSeenScreen,
            screenshotBridge = screenshotBridge,
            perceptionBridge = perceptionBridge,
            annotateImage = readScreenImage,
        )

        // Phase 9 — single perception façade replaces the legacy raw trio
        // (perceive_screen / get_annotated_screenshot / omniparser_detect).
        // The underlying helpers in PerceptionAiTools.kt are still internal-
        // available for any future composition.
        server.registerPerceiveScreenTool(
            screenshotBridge,
            uiTreeBridge,
            perceptionBridge,
            perceptionCache,
            cvMemo = cvMemo,
            lastSeenScreen = lastSeenScreen,
        )

        // Phase 9 — flow-control trio. verify_action + wait_for close the
        // perceive→act→verify loop without the agent having to invent its own.
        server.registerVerifyActionTool(uiTreeBridge)
        server.registerWaitForTool(uiTreeBridge)

        // E4/P17 — dispatch-time text-targeted tap (live tree, no som cache involved).

        // Phase 10B — agent-callable session-close marker. Takes the device
        // bridge so a clean end_session restores the automation-suppressed
        // soft keyboard immediately.
        server.registerEndSessionTool(deviceBridge)

        // Spec 2026-07-17 — the full playbook on demand (handshake carries only
        // the survival kit; clients truncate the instructions field).
        server.registerGetUsageGuideTool()

        // Phase 6 — web_search via Tavily for task-grounding documentation
        server.registerWebSearchTool(webSearchBridge)

        // Browser plane — reading and driving web pages at DOM level instead of
        // screenshotting Chrome and tapping coordinates. Same open→read→act
        // vocabulary across every engine (see BrowserBridge).
        browserBridge?.let { server.registerBrowserTools(it) }

        // Deep links — discover (list/resolve, READ) and fire (open, WRITE) the
        // express-lane Intent path that skips gesture navigation entirely.
        server.registerDeepLinkTools(deepLinkBridge, deviceBridge::lookupApp)

        // Assistant plane — the non-gesture action surfaces Google Assistant
        // lives on: notifications (read/act/direct-reply/dismiss), cross-app
        // media transport control, and standard system verbs (alarm, dial,
        // SMS compose, calendar, share, navigate). All deterministic, none
        // touch pixels; the routing ladder in AuraInstructions prefers them
        // over gesture navigation.
        server.registerNotificationTools(notificationBridge)
        server.registerMediaTools(mediaBridge)
        server.registerSystemIntentTool(systemIntentBridge)

        // Contacts — on-device name→number resolution feeding system_intent
        // dial/SMS and wa.me-style deep-link templates.
        if (contactsBridge != null) server.registerContactTools(contactsBridge)

        // Files — MediaStore search + guarded viewer-open, replacing Files-app
        // gesture navigation for "open my <file>" goals.
        if (filesBridge != null) server.registerFileTools(filesBridge)

        // Resources — read-only context (policy doc, tool-selection guide, live
        // device status) the agent can pull on demand.
        server.registerResources(deviceBridge)

        // Prompts — vetted workflow runbooks (automate_task, open_app) so the
        // agent follows the perceive→act→verify, deep-link-first discipline.
        server.registerPrompts()

        // Spec 2026-07-17 — the phone's skills (bundled + user) as prompts, so
        // any client can invoke a vetted playbook as a slash-command.
        server.registerSkillPrompts(promptSkills)

        return server
    }
}
