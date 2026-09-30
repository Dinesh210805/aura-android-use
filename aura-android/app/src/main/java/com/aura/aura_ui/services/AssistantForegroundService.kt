package com.aura.aura_ui.services

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.aura.aura_ui.BuildConfig
import com.aura.aura_ui.R
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.agent.AuraAgent
import com.aura.aura_ui.agent.skills.isTrusted
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.agent.llm.ModelCatalog
import com.aura.aura_ui.data.audio.AudioCaptureManager
import com.aura.aura_ui.mcp.MCPCommandRouter
import com.aura.aura_ui.mcp.audit.McpAuditRegistry
import com.aura.aura_ui.data.deeplink.DeepLinkManager
import com.aura.aura_ui.mcp.bridge.AppContactsBridge
import com.aura.aura_ui.mcp.bridge.AppDeepLinkBridge
import com.aura.aura_ui.mcp.bridge.AppBrowserBridge
import com.aura.aura_ui.mcp.bridge.AppFilesBridge
import com.aura.aura_ui.mcp.bridge.AppDeviceBridge
import com.aura.aura_ui.mcp.bridge.AppMediaBridge
import com.aura.aura_ui.mcp.bridge.AppNotificationBridge
import com.aura.aura_ui.mcp.bridge.AppPerceptionBridge
import com.aura.aura_ui.mcp.bridge.AppScreenshotBridge
import com.aura.aura_ui.mcp.bridge.AppSystemIntentBridge
import com.aura.aura_ui.mcp.bridge.AppUiTreeBridge
import com.aura.aura_ui.mcp.bridge.AppWebSearchBridge
import com.aura.aura_ui.mcp.bridge.TavilyKeyStore
import com.aura.aura_ui.network.ConnectionManager
import com.aura.mcp.McpServerController
import com.aura.mcp.McpServerHealth
import com.aura.aura_ui.network.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import com.aura.aura_ui.compat.ForegroundServiceCompat
import com.aura.aura_ui.compat.ForegroundServiceCompat.startForegroundSafely
import com.aura.aura_ui.remote.RemoteGateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import com.aura.aura_ui.agent.AgentRunBridge
import com.aura.aura_ui.overlay.AuraOverlayService
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/**
 * Foreground service responsible for maintaining the floating voice assistant overlay
 * and handling background operations for voice processing.
 *
 * Keep-Alive mode (controlled by [PREF_KEEP_ALIVE] in [PREFS_NAME]):
 * - When ON: the WebSocket connection persists even when the overlay is dismissed or
 *   the user closes the app. The service stays alive in the foreground with a slim
 *   "Connected (background)" notification so the MCP server can always reach the device.
 * - When OFF: connection lifetime matches the overlay — stopping the overlay stops
 *   the service and the connection (legacy behaviour).
 */
@AndroidEntryPoint
class AssistantForegroundService : LifecycleService() {

    @Inject lateinit var audioManager: AudioCaptureManager
    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var remoteGateManager: RemoteGateManager

    private lateinit var mcpRouter: MCPCommandRouter

    /**
     * Phase 9 — single source of truth for the [AuraStatus] verb shown on
     * the status-bar chip. The MCP server's [com.aura.mcp.bridge.ToolPhaseSink]
     * pushes transitions in; the chip-rendering collector observes the flow.
     */
    private val statusController = StatusController()

    /**
     * Shared on-device perception bridge. Hoisted to a field (rather than
     * constructed inline below) so [onCreate] can warm it up — calibrate the
     * fastest ONNX execution provider + pay graph compilation — off the
     * user-facing critical path, on the SAME instance the MCP server serves with.
     */
    private val perceptionBridge by lazy { AppPerceptionBridge(applicationContext) }

    /**
     * The on-device MCP server. Lifecycle tied to this service: started in [onCreate], stopped in
     * [onDestroy]. Reachable from the local network only (see `WebRtcTransport`). The bridges
     * passed in keep `:mcp-server` from reaching into app internals.
     */
    private val mcpServerController by lazy {
        // Phase 10B — share one ScreenshotBridge between the server and
        // the session logger so they both see the same MediaProjection.
        val sharedScreenshotBridge = AppScreenshotBridge(applicationContext)
        val sessionLogger = com.aura.aura_ui.mcp.log.McpSessionLogger(
            applicationContext, sharedScreenshotBridge,
        )
        McpServerController(
            deviceBridge = AppDeviceBridge(applicationContext),
            screenshotBridge = sharedScreenshotBridge,
            uiTreeBridge = AppUiTreeBridge(),
            perceptionBridge = perceptionBridge,
            webSearchBridge = AppWebSearchBridge(TavilyKeyStore(applicationContext)),
            // Deep links — discovery via DomainVerificationManager + the app's
            // own static shortcuts + curated catalog + manifest intent-filters,
            // firing via guarded VIEW intents. DeepLinkManager is normally
            // Hilt-injected; constructed manually here to match the rest of
            // this hand-wired bridge set.
            deepLinkBridge = AppDeepLinkBridge(applicationContext, DeepLinkManager(applicationContext)),
            // Assistant plane — notifications (read/reply/dismiss), cross-app
            // media control, and standard system verbs. Same bridges the
            // in-process agent server uses.
            notificationBridge = AppNotificationBridge(applicationContext),
            mediaBridge = AppMediaBridge(applicationContext),
            systemIntentBridge = AppSystemIntentBridge(applicationContext),
            contactsBridge = AppContactsBridge(applicationContext),
            filesBridge = AppFilesBridge(applicationContext),
            // Browser plane — AURA's own offscreen WebView. Reading a page at DOM
            // level instead of screenshotting Chrome and tapping coordinates.
            browserBridge = AppBrowserBridge(applicationContext),
            auditLogger = McpAuditRegistry.INSTANCE,
            // Phase 9 — live tool dispatches drive the AuraStatus chip.
            // Keep-awake (2026-07-19): the same dispatch events also hold a
            // session-scoped screen lease so the display survives client
            // think-gaps between tool calls; released on end_session / idle.
            toolPhaseSink = CompositeToolPhaseSink(
                listOf(
                    StatusToolPhaseSink(statusController),
                    com.aura.aura_ui.services.keepawake.KeepAwakeToolPhaseSink(
                        com.aura.aura_ui.services.keepawake.ScreenAwake.controller(applicationContext),
                    ),
                ),
            ),
            // Phase 10B — forensic per-call log with raw screenshots.
            sessionLogSink = sessionLogger,
            // Spec 2026-07-17 — shared memory: external clients read learned hints
            // in tool results and teach the store back via end_session outcomes.
            learningsGateway = com.aura.aura_ui.mcp.AppLearningsGateway(applicationContext),
            // Spec 2026-07-17 — the phone's skills (bundled + user, trusted only)
            // surface as MCP prompts. Small asset/file reads; runBlocking is
            // bounded and the whole provider is fail-soft in the controller.
            promptSkillsProvider = {
                kotlinx.coroutines.runBlocking {
                    com.aura.aura_ui.agent.skills.SkillRepository(
                        listOf(
                            com.aura.aura_ui.agent.skills.BundledSkillSource.fromAssets(applicationContext),
                            com.aura.aura_ui.agent.skills.UserSkillStore(applicationContext),
                        ),
                    ).all()
                        .filter { it.trust.isTrusted }
                        .map { com.aura.mcp.server.PromptSkill(it.id, it.description, it.body) }
                }
            },
            // Phase 11 — surface connected clients in the MCP Center screen.
            connectionTracker = com.aura.aura_ui.mcp.AppConnectionTracker(),
            // Trust & transparency — token→metadata ledger behind auto-approve
            // and the Trusted Devices screen (revoke, first/last-connected).
            trustedClients = com.aura.aura_ui.mcp.AppTrustedClients.get(applicationContext),
            // E1 — settle ignores our own overlay's accessibility events.
            ownPackageName = applicationContext.packageName,
            // Spec 2026-07-31 — control lock. This is the server remote MCP clients talk
            // to, and the gap it closes: today a PC keeps firing tap / type_text /
            // browser_act into a phone whose owner picked it up thirty seconds ago.
            pausedByHuman = { ControlLockStore.shared.isPausedByHuman },
            readScreenImage = { com.aura.aura_ui.data.preferences.ReadScreenImageStore.enabled.value },
            // …and this is what ARMS that lock for the remote case. A remote client has
            // no run lifecycle AURA can see, so "a PC is driving right now" can only be
            // inferred from calls arriving. Passed here and nowhere else: the agent's
            // in-process server reports its own run boundaries instead.
            onToolDispatched = {
                ControlLockStore.shared.noteRemoteToolCall()
                com.aura.aura_ui.telemetry.AuraAnalytics.mcpToolCall()
            },
        )
    }

    // Distinct from AuraOverlayService's NOTIFICATION_ID = 1001 — they're both
    // foreground services in the same package, so notification IDs must not collide
    // or they'll overwrite each other.
    private val notificationId = 7001

    /**
     * Receives ACTION_USER_PRESENT (screen unlocked) to trigger reconnection.
     *
     * ACTION_USER_PRESENT cannot be registered in AndroidManifest on Android 8+
     * (it is in the restricted implicit broadcast list), so we register it here
     * dynamically. This ensures that when the user unlocks the device after the
     * screen was off, we immediately attempt to re-establish the WebSocket
     * connection — important for the MCP "always-on" mode.
     *
     * ConnectionManager.start() is idempotent: calling it while already connected
     * is safe and simply resets the backoff counter.
     */
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT &&
                isKeepAliveEnabled() &&
                isLegacyPythonBackendEnabled()
            ) {
                Log.i("AssistantService", "Screen unlocked — re-arming legacy Python WS")
                connectionManager.start(getServerUrl())
            }
        }
    }

    /**
     * Phase 0 spike — **debug-only** trigger for the on-device [AuraAgent].
     *
     * Registered only when [BuildConfig.DEBUG] is true and exported so it can be
     * fired from a workstation via:
     * ```
     * adb shell am broadcast -a com.aura.aura_ui.AGENT_SPIKE \
     *   --es key  "<groq_api_key>" \
     *   --es goal "Get the device status and tell me the foreground app"
     * ```
     * Since Phase 2 slice 3, the default run mode also works **without** `--es key`:
     * it then drives the agent from the provider/key/model saved in Settings →
     * Agent Brain ([com.aura.aura_ui.agent.AuraAgent.runFromSavedSettings]), which
     * is how the UI→store→agent wiring is validated until the Phase 3 voice/overlay
     * entry points land. The `vision`/`models` probe modes still require `--es key`.
     *
     * Observe the run with `adb logcat -s AuraAgentSpike`. This is a throwaway
     * harness to prove the Koog-on-device + MCP round-trip on a real device; it
     * is replaced by the real voice/overlay entry points in Phase 3 and never
     * exists in a release build.
     */
    private val agentSpikeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_AGENT_SPIKE) return
            val apiKey = intent.getStringExtra(EXTRA_AGENT_KEY)?.trim().orEmpty()
            val goal = intent.getStringExtra(EXTRA_AGENT_GOAL)?.trim().orEmpty()
            if (goal.isEmpty()) {
                Log.e("AuraAgentSpike", "Missing --es goal; nothing to run")
                return
            }
            val mode = intent.getStringExtra(EXTRA_AGENT_MODE)?.trim().orEmpty()
            val providerName = intent.getStringExtra(EXTRA_AGENT_PROVIDER)?.trim().orEmpty()
            val endpoint = LlmEndpointCatalog.builtinById(providerName.lowercase())
                ?: LlmEndpointCatalog.BUILTINS.first()
            val modelId = intent.getStringExtra(EXTRA_AGENT_MODEL_ID)?.trim()?.takeIf { it.isNotEmpty() }
            Log.i(
                "AuraAgentSpike",
                "Spike broadcast received — mode=\"$mode\" provider=${endpoint.id} model=$modelId " +
                    "key=${if (apiKey.isEmpty()) "<saved>" else "<adb>"} goal=\"$goal\"",
            )
            lifecycleScope.launch {
                runCatching {
                    when {
                        // vision/models are key-driven probes — they need an explicit --es key.
                        mode == "vision" -> requireKey(apiKey)?.let { agent().runVisionSpike(it, goal) }
                        mode == "models" -> requireKey(apiKey)?.let { logModelCatalog(it, goal) }
                        // An adb-supplied key means the caller is probing a specific
                        // provider/model, which the overlay path cannot express — it always uses
                        // the saved Settings selection. Keep the direct call for that case.
                        apiKey.isNotEmpty() -> agent().runSpike(apiKey, goal, endpoint, modelId)
                        // `--es mode direct` keeps the old behaviour: construct a private
                        // AuraAgent and run it headless. Kept only as an escape hatch.
                        mode == "direct" -> agent().runFromSavedSettings(goal)
                        // Default since the AndroidWorld harness landed: hand the goal to the
                        // overlay exactly as if it had been typed into it. See [runViaOverlay].
                        else -> runViaOverlay(goal)
                    }
                }.onFailure { Log.e("AuraAgentSpike", "Spike run threw: ${it.message}", it) }
            }
        }
    }

    companion object {
        /** Boot / keep-alive: start the persistent WebSocket without showing the overlay. */
        const val ACTION_START_CONNECTION_ONLY   = "com.aura.aura_ui.START_CONNECTION_ONLY"

        // MCP server control — dispatched by [com.aura.aura_ui.mcp.McpHealthRegistry]
        // from the in-app MCP Center screen. The user can toggle / restart the
        // server without leaving the app.
        const val ACTION_MCP_START_WEBRTC = "com.aura.aura_ui.MCP_START_WEBRTC"
        const val ACTION_MCP_STOP       = "com.aura.aura_ui.MCP_STOP"
        const val ACTION_MCP_RESTART    = "com.aura.aura_ui.MCP_RESTART"
        const val ACTION_MCP_DISCONNECT = "com.aura.aura_ui.MCP_DISCONNECT"
        const val EXTRA_SESSION_KEY     = "session_key"

        // Approve/Deny actions fired from the [McpApprovalNotifier] action buttons
        // (or its swipe-to-dismiss), so a pairing request can be resolved without
        // opening the app — the whole point being it must not require the user to
        // be on the MCP Center screen.
        const val ACTION_MCP_APPROVE_CONNECTION = "com.aura.aura_ui.MCP_APPROVE_CONNECTION"
        const val ACTION_MCP_DENY_CONNECTION    = "com.aura.aura_ui.MCP_DENY_CONNECTION"

        // Phase 0 spike — debug-only on-device agent trigger (see [agentSpikeReceiver]).
        /**
         * Ceiling on how long the spike waits for an overlay run to publish its outcome.
         * Generous on purpose: an AndroidWorld task can legitimately run for minutes, and the
         * cost of waiting too long is one slow line in a log, while the cost of giving up early
         * is a task filed as a failure while it is still succeeding on screen.
         */
        private const val OVERLAY_RUN_TIMEOUT_MS = 15L * 60_000L

        const val ACTION_AGENT_SPIKE    = "com.aura.aura_ui.AGENT_SPIKE"
        const val EXTRA_AGENT_KEY       = "key"
        const val EXTRA_AGENT_GOAL      = "goal"
        const val EXTRA_AGENT_MODE      = "mode" // "vision" → runVisionSpike; "models" → fetch; else runSpike
        const val EXTRA_AGENT_PROVIDER  = "provider" // GROQ (default) | OPENROUTER | GEMINI
        const val EXTRA_AGENT_MODEL_ID  = "model" // provider model id; null → Groq Llama 4 Scout default

        const val PREFS_NAME      = "aura_prefs"       // keep_alive_background lives here
        const val SETTINGS_PREFS_NAME = "aura_settings" // server_url lives here (written by SettingsRepositoryImpl)
        const val PREF_KEEP_ALIVE = "keep_alive_background"

        /**
         * Phase 8 retirement flag for the legacy Python WebSocket backend.
         *
         * **Default: false** — the on-device Ktor MCP server is now canonical.
         * When this pref is false (the new default), the foreground service does
         * NOT call `connectionManager.start(...)` from any of its action handlers,
         * and the legacy reconnect loop stays dormant. Old user setups can flip
         * this back to true in settings if they still want the Python brain.
         *
         * Behaviour matrix:
         * | Pref state      | Python WS started? | MCP server started? | Chip reflects |
         * | --------------- | ------------------ | ------------------- | ------------- |
         * | false (default) | no                 | yes                 | MCP health    |
         * | true            | yes (legacy)       | yes                 | MCP health    |
         *
         * The chip always reflects MCP health regardless — the legacy WS chip
         * is gone for good; users found it misleading.
         */
        const val PREF_LEGACY_PYTHON_BACKEND = "legacy_python_backend_enabled"

        private val DEFAULT_SERVER_URL = BuildConfig.DEFAULT_SERVER_URL

        /** Re-check the remote gate every 15 min from the service's own scope. */
        private const val GATE_RECHECK_INTERVAL_MS = 15L * 60L * 1000L


        /** Called from BootReceiver when keep-alive is enabled and device booted. */
        fun startConnectionOnly(context: Context) {
            val intent = Intent(context, AssistantForegroundService::class.java).apply {
                action = ACTION_START_CONNECTION_ONLY
            }
            // BOOT_COMPLETED is always a background start — the single most likely
            // source of ForegroundServiceStartNotAllowedException on Android 12+.
            ForegroundServiceCompat.startSafely(context, intent)
        }

        /**
         * Revive the service from MainActivity.onCreate when keep-alive is enabled
         * but the process was killed (e.g. by aggressive OEMs like OnePlus / Xiaomi).
         *
         * Safe to call unconditionally — no-ops if keep-alive is disabled. Idempotent
         * — calling while the service is already running just delivers another
         * ACTION_START_CONNECTION_ONLY, which ConnectionManager.start() handles
         * safely (resets backoff, reuses existing socket if connected).
         */
        fun ensureRunningIfKeepAliveEnabled(context: Context) {
            val enabled = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEEP_ALIVE, false)
            if (enabled) {
                Log.i("AssistantService", "Keep-alive ON — reviving service from app open")
                startConnectionOnly(context)
            }
        }

        /**
         * **Always** start the foreground service when the user opens the app so
         * the on-device MCP server boots and starts listening on port 8765 —
         * independent of the keep-alive setting (which only controls whether the
         * service survives the user closing the app).
         *
         * This is the fix for the "I opened the app but `/mcp` says connection
         * refused" failure mode. Before this, the service only auto-started when
         * keep-alive was enabled, so brand-new installs would never start the
         * MCP server until the user explicitly toggled keep-alive on.
         *
         * Idempotent: if the service is already running, the framework just
         * delivers another `onStartCommand` with the chosen action — no
         * duplicate `onCreate`, no second MCP server bind.
         */
        fun ensureMcpServerRunning(context: Context) {
            Log.i("AssistantService", "ensureMcpServerRunning — starting FGS unconditionally")
            startConnectionOnly(context)
        }
    }

    // ── SharedPreferences listener ────────────────────────────────────────────

    /**
     * Watches for:
     * - `server_url` changes  → reconnect immediately.
     * - `keep_alive_background` turned OFF while running in background-only mode
     *   → stop the connection and shut down the service.
     */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            "server_url" -> {
                val newUrl = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
                Log.i("AssistantService", "Server URL changed → $newUrl — reconnecting WebSocket")
                connectionManager.updateServerUrl(newUrl)
                // Keep the HTTP registration URL in sync so BackendCommunicator
                // re-registers with the correct host on the next registration attempt.
                AuraAccessibilityService.setBackendUrl(newUrl)
            }
            PREF_KEEP_ALIVE -> {
                val keepAlive = prefs.getBoolean(PREF_KEEP_ALIVE, false)
                Log.i("AssistantService", "keep_alive_background changed → $keepAlive")
                if (!keepAlive) {
                    // Was running background-only; now keep-alive turned off → full stop.
                    Log.i("AssistantService", "Keep-alive disabled while in background — stopping service")
                    connectionManager.stop()
                    stopSelf()
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d("AssistantService", "onCreate")
        LiveUpdateNotificationHelper.ensureChannelCreated(this)
        deleteOrphanChannels()
        // FGS notification IS the status-bar chip on Android 16+. The FOREGROUND_SERVICE
        // flag set by startForeground() is what makes the system honour Promoted Ongoing.
        // Start SILENT: the combine collector below upgrades to the visible chip only
        // when ChipVisibilityPolicy says something is genuinely happening — a boot in
        // the background must not pin a pill (the permanent "AURA ready" complaint).
        val initialChip = LiveUpdateNotificationHelper.buildSilentNotification(this)
        // Never fatal: a background-originated start (BootReceiver, MCP reconnect)
        // can be refused on Android 12+, and letting that escape onCreate took the
        // whole process down. connectedDevice is claimed deliberately — see the
        // manifest note about the Android 14 dataSync 6-hour cap.
        if (!startForegroundSafely(
                notificationId,
                initialChip,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        ) {
            Log.w("AssistantService", "Could not enter foreground — stopping cleanly")
            stopSelf()
            return
        }

        // Register pref listeners once here, in onCreate — not per-action — to
        // avoid double-registration when the service is re-signalled.
        // keep_alive_background is in PREFS_NAME ("aura_prefs"); server_url is in
        // SETTINGS_PREFS_NAME ("aura_settings") — we listen on both.
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefListener)
        getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefListener)

        // The on-device MCP server, reachable from the local network only. Started
        // here so a PC that is already paired reconnects without the user opening
        // the app. Failure is non-fatal: voice still works, and MCP Center shows why.
        if (RemoteGateManager.isBlocked()) {
            // Blocked (kill switch, blocklist, version floor): no MCP server. GateEnforcer
            // also stops this service; this covers the start that races it.
            Log.w("AssistantService", "Remote gate blocks AURA — not starting the MCP server")
        } else {
            runCatching { mcpServerController.startWebRtc(this) }
                .onFailure { Log.w("AssistantService", "WebRTC MCP auto-start failed (non-fatal)", it) }
        }

        // Perception warm-up + per-device EP calibration off the critical path.
        // First launch benchmarks XNNPACK/NNAPI/CPU and persists the winner;
        // later launches just rebuild it. Non-fatal — perceive_screen still works
        // (it lazily prepares) if this fails.
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching { perceptionBridge.warmUp() }
                .onFailure { Log.w("AssistantService", "Perception warm-up failed (non-fatal)", it) }
        }

        // Phase 10B — daily cleanup of session logs older than the retention window.
        // Idempotent via WorkManager unique-name + KEEP policy: safe to call every boot.
        com.aura.aura_ui.mcp.log.McpLogCleanupWorker.schedule(applicationContext)

        // M2 — daily memory consolidation (30-day TTL decay + de-dup + cap). Same
        // idempotent unique-name/KEEP pattern as the log cleanup above.
        com.aura.aura_ui.agent.memory.MemoryConsolidationWorker.schedule(applicationContext)

        // Mirror the controller's health flow into the process-global registry so
        // the MCP Center screen sees state changes even after this service dies
        // and gets revived. Cheap forwarder — one collector for the lifetime of
        // the service.
        lifecycleScope.launch {
            mcpServerController.health.collect { state ->
                com.aura.aura_ui.mcp.McpHealthRegistry.publish(state)
            }
        }
        
        lifecycleScope.launch {
            mcpServerController.webRtcApprovalRequest.collect { request ->
                com.aura.aura_ui.mcp.McpHealthRegistry.publishApprovalRequest(request)
                // The in-app dialog only exists while the MCP Center screen is on
                // screen. A pairing request can arrive while the user is in any
                // other app (or the phone is locked), so it also needs a system
                // notification with its own Approve/Deny actions — not just a
                // Compose dialog nobody is looking at.
                if (request != null) {
                    McpApprovalNotifier.show(this@AssistantForegroundService, request)
                } else {
                    McpApprovalNotifier.clear(this@AssistantForegroundService)
                }
            }
        }

        // Register ACTION_USER_PRESENT dynamically (manifest registration is
        // blocked on Android 8+ for this broadcast). When keep-alive is enabled,
        // this triggers a reconnect attempt every time the user unlocks the device.
        registerReceiver(
            userPresentReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
        )
        Log.d("AssistantService", "Registered ACTION_USER_PRESENT receiver")

        // Phase 0 spike — register the debug-only agent trigger. Exported so an
        // `adb shell am broadcast` from the workstation can reach it; never
        // registered in a release build.
        if (BuildConfig.DEBUG) {
            val spikeFilter = IntentFilter(ACTION_AGENT_SPIKE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(agentSpikeReceiver, spikeFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(agentSpikeReceiver, spikeFilter)
            }
            Log.i("AuraAgentSpike", "Debug agent-spike receiver registered (action=$ACTION_AGENT_SPIKE)")
        }

        // Initialize the MCP command router — handles ALL device-control messages
        // (gestures, screenshots, UI tree, app launch, etc.) over the persistent
        // /ws/device WebSocket. Works in both foreground and keep-alive background mode.
        mcpRouter = MCPCommandRouter(applicationContext, connectionManager, lifecycleScope)

        // Route all incoming WebSocket messages through the MCP router first.
        // Only connection_request needs special handling here (notification APIs).
        connectionManager.onMessage = { text ->
            if (!mcpRouter.dispatch(text)) {
                handleIncomingCommand(text)
            }
        }
        // Bridge ConnectionManager to AuraAccessibilityService which can't use Hilt directly
        AuraAccessibilityService.connectionManager = connectionManager

        // Send real device info to the MCP server each time the WebSocket opens so it
        // has accurate screen dimensions for coordinate mapping.
        lifecycleScope.launch {
            connectionManager.state
                .filterIsInstance<ConnectionState.Connected>()
                .collect {
                    val dm = Resources.getSystem().displayMetrics
                    val accessibilityService = AuraAccessibilityService.instance
                    val screenCaptureAvailable = accessibilityService?.isMediaProjectionAvailable() ?: false
                    val json = JSONObject().apply {
                        put("type", "device_info")
                        put("screen_width", accessibilityService?.getScreenWidth() ?: dm.widthPixels)
                        put("screen_height", accessibilityService?.getScreenHeight() ?: dm.heightPixels)
                        put("density_dpi", dm.densityDpi)
                        put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("android_version", Build.VERSION.RELEASE)
                        put("screen_capture_available", screenCaptureAvailable)
                    }
                    connectionManager.send(json.toString())
                    Log.i("AssistantService", "📱 Sent device_info to MCP server: screen_capture=$screenCaptureAvailable")
                }
        }

        // GAP FIX #3: Re-send device_info when MediaProjection is granted.
        // On first connect the accessibility service may not have MediaProjection yet,
        // so screen_capture_available is false. When the user grants permission later,
        // this collector fires and sends an updated device_info so the MCP server can
        // enable screenshot-based perception immediately without waiting for a reconnect.
        lifecycleScope.launch {
            AuraAccessibilityService.screenCaptureAvailable.collect { available ->
                if (available) {
                    val dm = Resources.getSystem().displayMetrics
                    val service = AuraAccessibilityService.instance
                    val json = JSONObject().apply {
                        put("type", "device_info")
                        put("screen_width", service?.getScreenWidth() ?: dm.widthPixels)
                        put("screen_height", service?.getScreenHeight() ?: dm.heightPixels)
                        put("density_dpi", dm.densityDpi)
                        put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("android_version", Build.VERSION.RELEASE)
                        put("screen_capture_available", true)
                    }
                    connectionManager.send(json.toString())
                    Log.i("AssistantService", "📸 Re-sent device_info: screen_capture_available=true")
                }
            }
        }

        // Phase 9 — drive the Promoted Ongoing chip from the MCP server's
        // health combined with the live [AuraStatus] from [statusController].
        // The status controller is fed by [StatusToolPhaseSink] which fires
        // on every MCP tool dispatch (started / completed / errored). When
        // the agent goes quiet, the sink drops the status back to Idle.
        //
        // startForeground() (re)applies the FOREGROUND_SERVICE notification
        // flag the system needs to honour the Promoted Ongoing chip request
        // on Android 16+; calling it repeatedly on the same id updates the
        // notification in place — no flicker, no duplicate posts.
        lifecycleScope.launch {
            // Activity signals (3) folded first, then combined with visibility
            // signals — the chip renders only when ChipVisibilityPolicy says so:
            // app open, client connected, or a live flow; and NEVER while the
            // overlay's automation pill owns the notch (double-pill fix). In
            // SILENT mode the FGS stays alive behind an invisible placeholder.
            val activity = combine(
                statusController.state,
                VoiceStatusRegistry.status,
                AutomationPillRegistry.active,
            ) { toolStatus, voiceStatus, automation -> Triple(toolStatus, voiceStatus, automation) }
            combine(
                mcpServerController.health,
                activity,
                AppVisibilityRegistry.appInForeground,
                com.aura.aura_ui.mcp.McpConnectionRegistry.clients,
            ) { health, (toolStatus, voiceStatus, automation), appForeground, clients ->
                val mode = ChipVisibilityPolicy.decide(
                    automationPillActive = automation,
                    appInForeground = appForeground,
                    connectedClients = clients.size,
                    toolStatus = toolStatus,
                    voiceStatus = voiceStatus,
                )
                // Health is no longer allowed to become an activity verb, so it gets
                // its own surface here. Without this a crash while the app is
                // backgrounded is completely silent — worse than the wrong verb.
                // Decision logic is in the pure HealthAlertPolicy; only a crash
                // alerts, and reaching Listening clears it.
                McpHealthNotifier.onHealth(this@AssistantForegroundService, health)

                if (mode == ChipVisibilityPolicy.Mode.CHIP) {
                    buildChipStatus(health, toolStatus, voiceStatus)
                } else {
                    null // silent keep-alive
                }
            }.collect { status ->
                val notification = if (status != null) {
                    LiveUpdateNotificationHelper.buildNotification(this@AssistantForegroundService, status)
                } else {
                    LiveUpdateNotificationHelper.buildSilentNotification(this@AssistantForegroundService)
                }
                startForegroundSafely(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            }
        }

        // Remote kill switch / blocklist / version floor: refresh on service start and
        // every 15 min so a change reaches an install that stays running, not just fresh
        // launches. A blocking result is acted on by GateEnforcer, which stops this
        // service (and with it the MCP server), the wake word and the overlay.
        lifecycleScope.launch {
            while (isActive) {
                remoteGateManager.refresh()
                delay(GATE_RECHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Compose the [AuraStatus] the chip should show right now.
     *
     * [AuraStatus] describes **activity only**. Server health is deliberately NOT
     * mapped into it: a stopped or booting MCP server is infrastructure state, not
     * something the agent is doing, and the old mapping produced the plainly wrong
     * "Stuck" verb while the server was merely *starting*. Health has its own
     * surface (the MCP screen); the chip stays honest about activity.
     *
     * When the server is not Listening, no tool can be dispatched, so there is by
     * definition no tool activity — but voice runs entirely on-device and is
     * unaffected, so it still shows.
     *
     * Priority between the two live sources: voice (overlay) over tool dispatches
     * (MCP). They are mutually exclusive in practice — the user cannot speak a
     * command while a remote agent is running tools — so whichever is non-Idle
     * wins, with voice taking precedence on a tie.
     */
    private fun buildChipStatus(
        health: McpServerHealth,
        toolStatus: AuraStatus,
        voiceStatus: AuraStatus,
    ): AuraStatus = ChipStatusPolicy.decide(
        health,
        toolStatus,
        voiceStatus,
        // Spec 2026-07-31 — the status pill and the Pause pill are mutually exclusive
        // in time. Read directly rather than mirrored into a field: one source of truth
        // for "is the human driving", and no second copy to drift out of sync.
        pausedByHuman = ControlLockStore.shared.isPausedByHuman,
    )

    /**
     * Fallback handler for messages not handled by [MCPCommandRouter].
     * The server auto-registers any WebSocket on connect — no approval flow is
     * needed, so this just logs unhandled message types for visibility.
     */
    /** Debug-only: construct an on-device agent for a spike run. */
    private fun agent() = AuraAgent(applicationContext)

    /**
     * Run [goal] through the overlay, exactly as if a human had typed it and pressed enter.
     *
     * ### Why not just call the agent
     *
     * Because that measures a code path nobody uses. `agent().runFromSavedSettings(goal)` builds a
     * private [AuraAgent] and runs it headless: no overlay, no edge lighting, no tap ripples, no
     * spoken announcement, no THINKING phase, no conversation entries — and, the part that changes
     * outcomes rather than appearances, none of `executeAgent`'s control-lock handling, so a suite
     * could keep firing goals straight past a human pause. [AgentRunBridge] exists for precisely
     * this reason and says so in its own KDoc.
     *
     * It also matters for the benchmark recordings: a screen capture of the headless path shows a
     * phone operating itself with no visible assistant, which is not what the product does.
     *
     * ### The subscribe-then-start order is load-bearing
     *
     * [AgentRunBridge.outcomes] has `replay = 0`, so an outcome published before this coroutine is
     * collecting is gone for good. A task the overlay refuses instantly — control lock held, no
     * provider configured — publishes within milliseconds of the service starting. `onSubscription`
     * fires *after* the subscription is registered, which is the only ordering that cannot lose it.
     */
    private suspend fun runViaOverlay(goal: String) {
        val outcome = withTimeoutOrNull(OVERLAY_RUN_TIMEOUT_MS) {
            AgentRunBridge.outcomes
                .onSubscription {
                    ForegroundServiceCompat.startSafely(
                        this@AssistantForegroundService,
                        Intent(this@AssistantForegroundService, AuraOverlayService::class.java).apply {
                            action = AuraOverlayService.ACTION_RUN_TASK
                            putExtra(AuraOverlayService.EXTRA_TASK_TEXT, goal)
                        },
                    )
                }
                // Two runs never overlap (the overlay refuses a second while one is active), but
                // matching the goal keeps a stale outcome from an earlier task out of this answer.
                .first { it.goal == goal }
        }
        when {
            outcome == null ->
                Log.e("AuraAgentSpike", "Spike run threw: no outcome within ${OVERLAY_RUN_TIMEOUT_MS / 1000}s")
            outcome.error != null ->
                Log.e("AuraAgentSpike", "Spike run threw: ${outcome.error?.message}", outcome.error)
            // Same wording the headless path logs, so anything waiting on the log — the
            // AndroidWorld adapter, run_eval_suite.ps1 — keeps working unchanged.
            //
            // On a normal run this duplicates `AuraAgent`'s own line, and that is deliberate:
            // the agent only logs when its loop actually ran. An overlay that refuses the task
            // outright — control lock held, no provider configured — still publishes an outcome,
            // and without this line a waiter would sit through its whole timeout on a run that
            // was declined in milliseconds.
            else -> Log.i("AuraAgentSpike", "✅ Agent result: ${outcome.reply}")
        }
    }

    /**
     * Returns [apiKey] when present, or logs an error and returns null. Used by the
     * key-driven spike modes (`vision`/`models`) which cannot fall back to the saved
     * Settings selection the way the default run mode does.
     */
    private fun requireKey(apiKey: String): String? =
        apiKey.ifEmpty { null }.also {
            if (it == null) Log.e("AuraAgentSpike", "This mode needs --es key; nothing to run")
        }

    /**
     * Debug-only (Phase 2 slice 1 validation): fetch a provider's live model list
     * via [ModelCatalog] and log it with vision flags. [providerName] comes from the
     * broadcast `--es goal` extra (e.g. "groq"); defaults to Groq.
     */
    private suspend fun logModelCatalog(apiKey: String, providerName: String) {
        val endpoint = LlmEndpointCatalog.builtinById(providerName.lowercase())
            ?: LlmEndpointCatalog.BUILTINS.first()
        Log.i("AuraAgentSpike", "📡 Fetching models for ${endpoint.id}…")
        ModelCatalog.fetch(endpoint, apiKey)
            .onSuccess { models ->
                Log.i(
                    "AuraAgentSpike",
                    "✅ ${models.size} models (${models.count { it.visionCapable }} vision-capable):",
                )
                models.forEach { m ->
                    val eye = if (m.visionCapable) "👁️ " else "   "
                    val ctx = m.contextWindow?.let { " (ctx $it)" }.orEmpty()
                    Log.i("AuraAgentSpike", "  $eye${m.id}$ctx")
                }
            }
            .onFailure { Log.e("AuraAgentSpike", "❌ Model fetch failed: ${it.message}", it) }
    }

    private fun handleIncomingCommand(text: String) {
        try {
            val json = JSONObject(text)
            Log.d("AssistantService", "Unhandled WS command type: ${json.optString("type")}")
        } catch (e: Exception) {
            Log.e("AssistantService", "Failed to parse incoming WS command: $text", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d("AssistantService", "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_START_CONNECTION_ONLY -> handleStartConnectionOnly()
            ACTION_MCP_START_WEBRTC -> {
                Log.i("AssistantService", "ACTION_MCP_START_WEBRTC")
                if (RemoteGateManager.isBlocked()) {
                    com.aura.aura_ui.remote.GateEnforcer.openBlockScreen(this@AssistantForegroundService)
                } else {
                    mcpServerController.startWebRtc(this@AssistantForegroundService)
                }
            }
            ACTION_MCP_STOP -> {
                Log.i("AssistantService", "ACTION_MCP_STOP")
                mcpServerController.stop()
                com.aura.aura_ui.mcp.McpHealthRegistry.publish(com.aura.mcp.McpServerHealth.Stopped)
                com.aura.aura_ui.mcp.McpConnectionRegistry.clearAll()
                // The MCP Center "Stop" button means "turn the server off" — the FGS
                // then has no more work to do. Without this the notification and
                // background process silently outlive the server they were hosting,
                // and the user has no way to actually stop them from the UI.
                //
                // This used to be guarded by `overlayManager == null` ("is a voice
                // overlay keeping us alive for a different reason?"). That guard was
                // vacuous — `overlayManager` was never the thing keeping the service
                // alive, `AuraOverlayService` is — so it always fell through to
                // stopSelf(). Unconditional here preserves that exact behaviour.
                // See REVIEW_LOG OV2 before "fixing" this: checking the right object
                // is a behaviour change, not a cleanup.
                Log.i("AssistantService", "MCP stopped — no remaining reason to stay foreground; stopping service")
                stopSelf()
            }
            ACTION_MCP_RESTART -> {
                Log.i("AssistantService", "ACTION_MCP_RESTART")
                com.aura.aura_ui.mcp.McpConnectionRegistry.clearAll()
                // startWebRtc is idempotent — it tears down the old transport first.
                if (RemoteGateManager.isBlocked()) {
                    mcpServerController.stop()
                } else {
                    mcpServerController.startWebRtc(this@AssistantForegroundService)
                }
            }
            ACTION_MCP_DISCONNECT -> {
                val key = intent.getStringExtra(EXTRA_SESSION_KEY)
                Log.i("AssistantService", "ACTION_MCP_DISCONNECT key=$key")
                if (!key.isNullOrEmpty()) {
                    mcpServerController.disconnectSession(key)
                    // The intercept's finally{} will call onDisconnect → registry
                    // remove(), but do a defensive remove here too so the UI
                    // updates immediately even if cancellation propagation lags.
                    com.aura.aura_ui.mcp.McpConnectionRegistry.remove(key)
                }
            }
            ACTION_MCP_APPROVE_CONNECTION -> {
                Log.i("AssistantService", "ACTION_MCP_APPROVE_CONNECTION")
                // The request's own onApprove() clears webRtcApprovalRequest back to
                // null, which the collector below turns into McpApprovalNotifier.clear().
                mcpServerController.webRtcApprovalRequest.value?.onApprove()
            }
            ACTION_MCP_DENY_CONNECTION -> {
                Log.i("AssistantService", "ACTION_MCP_DENY_CONNECTION")
                mcpServerController.webRtcApprovalRequest.value?.onDeny()
            }
            null -> {
                // Service was restarted by START_STICKY after process kill.
                // Re-arm the legacy WS only if both keep-alive AND the Python
                // backend are explicitly enabled — by default Phase 8 leaves
                // the Python brain dormant and only the on-device MCP server
                // (started in onCreate) runs.
                Log.i("AssistantService", "Sticky restart — checking legacy WS re-arm")
                if (isKeepAliveEnabled() && isLegacyPythonBackendEnabled()) {
                    connectionManager.start(getServerUrl())
                    Log.i("AssistantService", "Keep-alive + Python backend ON: legacy WS restarted after sticky restart")
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        mcpServerController.stop()
        // Tell the UI the server is gone — even if it's just this service dying
        // for restart, the registry should never lie about current state.
        com.aura.aura_ui.mcp.McpHealthRegistry.publish(com.aura.mcp.McpServerHealth.Stopped)
        com.aura.aura_ui.mcp.McpConnectionRegistry.clearAll()

        // Only kill the connection if keep-alive is OFF. When keep-alive is ON the
        // connection should survive independent of the service lifecycle — but since
        // ConnectionManager now owns its own scope (SupervisorJob), the socket keeps
        // reconnecting even if this service is destroyed momentarily before START_STICKY
        // restarts it. Calling stop() here would set ConnectionState.Stopped and break
        // that auto-reconnect loop, so we skip it when keep-alive is enabled.
        if (!isKeepAliveEnabled()) {
            connectionManager.stop()
        }

        runCatching { unregisterReceiver(userPresentReceiver) }
            .onFailure { Log.w("AssistantService", "userPresentReceiver already unregistered") }

        if (BuildConfig.DEBUG) {
            runCatching { unregisterReceiver(agentSpikeReceiver) }
                .onFailure { Log.w("AuraAgentSpike", "agentSpikeReceiver already unregistered") }
        }

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
        getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefListener)

        super.onDestroy()
    }

    // ── Action handlers ───────────────────────────────────────────────────────

    private fun handleStartConnectionOnly() {
        Log.i("AssistantService", "ACTION_START_CONNECTION_ONLY — checking legacy WS")
        if (isLegacyPythonBackendEnabled()) {
            connectionManager.start(getServerUrl())
            Log.i("AssistantService", "Background legacy Python WS started → ${getServerUrl()}")
        } else {
            Log.d("AssistantService", "Legacy Python WS disabled — MCP server already started in onCreate, nothing to do here")
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    /**
     * Silently delete channels created by previous builds that are no longer used.
     * Android rejects deletion of channels currently attached to an active FGS,
     * so runCatching lets those silently pass — they will orphan harmlessly.
     */
    private fun deleteOrphanChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            listOf("AuraAssistantChannel", "aura_live_update").forEach { id ->
                runCatching { nm.deleteNotificationChannel(id) }
                    .onSuccess { Log.d("AssistantService", "Deleted orphan channel: $id") }
                    .onFailure { Log.w("AssistantService", "Could not delete channel $id: ${it.message}") }
            }
        }
    }

    // ── Prefs helpers ─────────────────────────────────────────────────────────

    private fun isKeepAliveEnabled(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEEP_ALIVE, false)

    /**
     * Default false in Phase 8: the legacy Python brain is retired. Users who
     * still want it can re-enable via a settings toggle (or by writing the
     * pref directly for now until that UI lands).
     */
    private fun isLegacyPythonBackendEnabled(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_LEGACY_PYTHON_BACKEND, false)

    private fun getServerUrl(): String =
        getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
}
