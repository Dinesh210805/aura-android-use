package com.aura.aura_ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aura.aura_ui.MainActivity
import com.aura.aura_ui.R
import com.aura.aura_ui.agent.AgentStreamEvent
import com.aura.aura_ui.agent.AuraAgent
import com.aura.aura_ui.agent.ToolVerbs
import com.aura.aura_ui.agent.conversation.progressToolName
import com.aura.aura_ui.agent.hitl.AskUserBroker
import com.aura.aura_ui.agent.hitl.PendingQuestion
import com.aura.aura_ui.agent.ledger.ResumeOffer
import com.aura.aura_ui.agent.ledger.RunLedgerStore
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.audio.AuraTTSManager
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.agent.voice.WhisperSttController
import com.aura.mcp.bridge.McpScope
import com.aura.mcp.server.McpToolScopes
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.aura.aura_ui.conversation.ConversationPhase
import com.aura.aura_ui.conversation.ConversationViewModel
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.accessibility.ScreenCapturePermissionActivity
import com.aura.aura_ui.data.preferences.ThemeManager
import com.aura.aura_ui.presentation.screens.VoiceAssistantCallbacks
import com.aura.aura_ui.presentation.screens.VoiceAssistantOverlay
import com.aura.aura_ui.presentation.screens.VoiceAssistantState
import com.aura.aura_ui.ui.theme.AuraUITheme
import com.aura.aura_ui.voice.ListeningModeController
import com.aura.aura_ui.voice.VoiceCaptureController
import com.aura.aura_ui.network.ConnectionManager
import com.aura.aura_ui.network.ConnectionState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import android.animation.ValueAnimator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import com.aura.aura_ui.BuildConfig
import com.aura.aura_ui.compat.ForegroundServiceCompat
import com.aura.aura_ui.compat.ForegroundServiceCompat.startForegroundSafely
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/**
 * AuraOverlayService - System-wide overlay service for AURA voice assistant.
 * 
 * This service displays the voice assistant UI as a system overlay,
 * allowing users to interact with AURA while using other apps.
 * 
 * Key features:
 * - True system overlay (TYPE_APPLICATION_OVERLAY)
 * - Foreground service with persistent notification
 * - Full Compose UI support via ComposeView
 * - Proper lifecycle management for ViewModel
 * - WebSocket connection via VoiceCaptureController
 * - Touch handling for interactive overlay
 * - Back button handling for dismissal
 * - Wake lock management for voice capture
 * 
 * Edge cases handled:
 * - Permission denied: Falls back gracefully
 * - Service killed: Restarts via START_STICKY
 * - Configuration changes: Properly handled
 * - Memory pressure: Cleanup on low memory
 * - Screen rotation: Window params auto-update
 * - Multiple show/hide calls: Singleton pattern
 */
class AuraOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AuraServicesEntryPoint {
        fun connectionManager(): ConnectionManager
    }

    companion object {
        private const val TAG = "AuraOverlayService"
        // Default neural voice for spoken agent answers (AuraTTSManager maps this
        // edge-tts-style id to the device's best offline neural voice).
        private const val TTS_VOICE_ID = "en-US-AriaNeural"

        /** How long the mic stays open for a follow-up after the assistant finishes speaking. */
        private const val FOLLOW_UP_WINDOW_MS = 8_000L

        /** Ignore barge-in VAD onsets this long after TTS starts (hardware AEC convergence). */
        private const val AEC_CONVERGENCE_MS = 400L
        private const val NOTIFICATION_ID = 1001

        /**
         * Longest the status strip may outlive the run that allowed it, waiting for its
         * last caption to finish.
         *
         * A cap, not a duration — the normal exit is the pacer publishing a null utterance,
         * which happens well inside this. It exists for the speech paths that never retract:
         * `AuraTTSManager.speak` publishes once and is done, so without a ceiling the strip
         * would sit on the user's screen until something else happened to clear it.
         *
         * 4 s covers the Live pacer's worst case — the catch-up running to the end of a long
         * sentence (~800 ms) followed by `CAPTION_DWELL_MS` (1.5 s) — with room to spare.
         */
        private const val STRIP_FAREWELL_MAX_MS = 4_000L
        // OxygenOS/OnePlus Live Alert chips truncate hard past ~15 chars.
        private const val CHIP_MAX_CHARS = 15
        private const val CHANNEL_ID = "aura_overlay_channel"
        private const val CHANNEL_ID_WORKING = "aura_working_channel" // For Live Alert / Dynamic Island
        // IMPORTANCE_MIN channel for the idle FGS notification. Android requires
        // every foreground service to post a notification; IMPORTANCE_MIN ones get
        // tucked into the silent group at the bottom of the shade and never show
        // as a card or heads-up — effectively invisible while still satisfying
        // the FGS contract.
        private const val CHANNEL_ID_SILENT = "aura_overlay_silent"
        private const val ACTION_SHOW = "com.aura.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.aura.HIDE_OVERLAY"
        private const val ACTION_SHOW_AND_LISTEN = "com.aura.SHOW_AND_LISTEN" // For wake word trigger
        private const val ACTION_MINIMIZE = "com.aura.MINIMIZE_OVERLAY"
        private const val ACTION_RESTORE = "com.aura.RESTORE_OVERLAY"
        private const val ACTION_CANCEL_TASK = "com.aura.CANCEL_TASK"

        /**
         * Run a task exactly as if it had been typed into the overlay — pill, THINKING phase,
         * stream events, spoken reply, control-lock handling and all.
         *
         * Added for the eval cockpit, which previously called [AuraAgent.runFromSavedSettings]
         * itself. That measured a code path no user ever takes: none of [executeAgent]'s control
         * handling ran, so a suite could keep issuing tasks straight past a human pause. The
         * caller learns the outcome through [AgentRunBridge].
         */
        const val ACTION_RUN_TASK = "com.aura.RUN_TASK"
        const val EXTRA_TASK_TEXT = "task_text"

        private var instance: AuraOverlayService? = null

        /**
         * Check if overlay permission is granted
         */
        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        /**
         * Refuses to open the assistant while the remote gate blocks (kill switch, blocklist,
         * version floor), and shows the full-screen block page instead.
         *
         * - Why here: every trigger (wake word, volume-button shortcut, assist gesture, app)
         *   funnels through [show]/[showAndListen], so this covers them all before the
         *   foreground service is even started.
         */
        private fun refusedByRemoteGate(context: Context): Boolean {
            if (!com.aura.aura_ui.remote.RemoteGateManager.isBlocked()) return false
            Log.w(TAG, "Overlay refused — remote gate is blocking")
            com.aura.aura_ui.remote.GateEnforcer.openBlockScreen(context)
            return true
        }

        /**
         * Show the overlay
         */
        /** @param source how the user opened AURA, for the `aura_open` analytics event */
        fun show(context: Context, source: String = "app") {
            if (refusedByRemoteGate(context)) return
            com.aura.aura_ui.telemetry.AuraAnalytics.assistantOpened(context, source)
            if (!canDrawOverlays(context)) {
                Log.w(TAG, "Cannot show overlay - permission not granted")
                return
            }
            val intent = Intent(context, AuraOverlayService::class.java).apply {
                action = ACTION_SHOW
            }
            // Android 12+ rejects a background foreground-service start with
            // ForegroundServiceStartNotAllowedException, which would crash the
            // *caller* (wake word, boot receiver, MCP reconnect). Degrade instead.
            if (!ForegroundServiceCompat.startSafely(context, intent)) {
                Log.w(TAG, "Overlay service start refused — app is not foreground-eligible")
            }
        }

        /**
         * Show the overlay and immediately start listening.
         * Called by wake word detection to auto-start voice capture.
         */
        /** @param source how the user opened AURA, for the `aura_open` analytics event */
        fun showAndListen(context: Context, source: String = "app") {
            if (refusedByRemoteGate(context)) return
            com.aura.aura_ui.telemetry.AuraAnalytics.assistantOpened(context, source)
            if (!canDrawOverlays(context)) {
                Log.w(TAG, "Cannot show overlay - permission not granted")
                return
            }
            val intent = Intent(context, AuraOverlayService::class.java).apply {
                action = ACTION_SHOW_AND_LISTEN
            }
            if (!ForegroundServiceCompat.startSafely(context, intent)) {
                Log.w(TAG, "Wake-word overlay start refused — app is not foreground-eligible")
            }
        }

        /**
         * Hide the overlay
         */
        fun hide(context: Context) {
            val intent = Intent(context, AuraOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        /**
         * Tear down an already-running overlay without starting a new service just to hide it.
         * This is used when AccessibilityService is being disabled by the restricted-app gate.
         */
        fun hideIfRunning() {
            val service = instance ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.hideOverlay()
            } else {
                Handler(Looper.getMainLooper()).post {
                    instance?.hideOverlay()
                }
            }
        }

        /**
         * Minimize overlay during automation - hide UI but keep service running.
         * Shows "executing command" notification.
         */
        fun minimize(context: Context) {
            instance?.minimizeOverlay(showExecutingNotification = true)
        }
        
        /**
         * Temporarily hide overlay without showing "executing" notification.
         * Used when opening settings or other non-command activities.
         */
        fun temporarilyHide(context: Context) {
            instance?.minimizeOverlay(showExecutingNotification = false)
        }

        /**
         * Restore overlay after automation completes
         */
        fun restore(context: Context) {
            instance?.restoreOverlay()
        }

        /** True while the overlay is up (shown or minimized) and may own the microphone. */
        fun isVisible(): Boolean = instance?.isOverlayVisible?.value == true

        /**
         * Get the service instance
         */
        fun getInstance(): AuraOverlayService? = instance
    }

    // Lifecycle management for Compose
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry 
        get() = savedStateRegistryController.savedStateRegistry

    // Service components
    private var windowManager: WindowManager? = null

    /** Watches whether the overlay actually reached the screen; cancelled on teardown. */
    private var visibilityProbe: OverlayVisibilityProbe? = null
    private var overlayView: ComposeView? = null

    /** Longest a single agent step may go without a tool event before the guard lapses. */
    private val AGENT_DRIVING_MAX_MS = 90_000L

    /**
     * True from the agent's first tool call until the run finishes.
     *
     * Guards the one rule that matters for a screen-sized overlay: while the agent is
     * driving the device, this window must not take touches. Kept as plain state rather
     * than derived from [_isMinimized] because minimize/restore is a *visual* concern
     * that turned out not to track "is automation running" — restores for HITL, for
     * status, and on finish all un-minimized the overlay mid-run and handed the touch
     * trap straight back.
     */
    @Volatile
    private var agentDriving = false
        set(value) {
            field = value
            agentDrivingSince = if (value) SystemClock.elapsedRealtime() else 0L
        }

    /**
     * When [agentDriving] was last set, for the staleness guard below.
     *
     * A guard whose "off" switch has exactly one caller is a latch waiting to happen —
     * a run that dies without emitting [AgentStreamEvent.Finished] (observed on device:
     * two sessions with `endReason: null`) would otherwise leave the overlay permanently
     * untouchable, which is a worse failure than the freeze this guard exists to prevent.
     */
    @Volatile
    private var agentDrivingSince = 0L

    /**
     * [agentDriving], but false once the claim goes stale. Nothing legitimately drives the
     * device for this long without a tool event, so an older claim means the run died
     * without telling us and the user must get their overlay back.
     */
    private val agentDrivingNow: Boolean
        get() = agentDriving &&
            (SystemClock.elapsedRealtime() - agentDrivingSince) < AGENT_DRIVING_MAX_MS

    /**
     * True only while an `ask_user` card is on screen waiting for the person to answer.
     * The single legitimate reason to be touchable during a run: the run is blocked on
     * that answer, so nothing is being driven underneath.
     */
    @Volatile
    private var hitlAwaitingInput = false

    // Live Alert pill animation state (replaces floating toast overlay)
    private var _workingVerb: String = "Working"
    private var _workingStepCurrent: Int = 0
    private var _workingStepTotal: Int = 0
    private var _workingGoal: String = ""
    private var _taskStartTime: Long = 0L
    private var _dotAnimJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // State management
    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()
    
    private val _isMinimized = MutableStateFlow(false)
    val isMinimized: StateFlow<Boolean> = _isMinimized.asStateFlow()

    // Coroutine scope for service
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The bottom-centre run controls — Pause for the whole run, Cancel once paused. */
    private val pausePill by lazy {
        com.aura.aura_ui.presentation.overlay.PausePillOverlay(this)
    }

    /** Previous value of the run flag, so the strip can act on the edge rather than the level. */
    private var runWasActive = false

    /** The status strip under the notch. Shown on every device; see [AgentStatusOverlay]. */
    private val statusStrip by lazy {
        com.aura.aura_ui.presentation.overlay.AgentStatusOverlay(this)
    }
    private var connectionJob: Job? = null

    // MCP device connection state — tracked separately from voice overlay visibility
    private var isMcpDeviceConnected = false

    // HITL: when the agent calls ask_user mid-task the chat is often minimized to
    // the automation pill — restore it so the question card is actually visible.
    private var hitlObserverJob: Job? = null
    private var mcpConnectionObserverJob: Job? = null
    private var edgeGlowSettingJob: Job? = null
    // Debounce: suppress repeated "Connected" chip expansions within 30 s
    private var lastConnectedChipMs = 0L

    // Voice capture controller (always connected for device control / gesture execution)
    private var voiceCaptureController: VoiceCaptureController? = null

    // BYOK direct Gemini Live (no backend) — the only Live voice path now that the Python
    // backend is retired. Active when the companion is enabled + a key is saved.
    private var companionLiveController: com.aura.aura_ui.agent.conversation.CompanionLiveController? = null
    private fun companionConfigured(): Boolean {
        val c = com.aura.aura_ui.agent.conversation.CompanionConfig(this)
        return c.enabled && c.byokKey() != null
    }
    private fun liveActive(): Boolean = companionLiveController != null
    private fun liveSessionActive(): Boolean = companionLiveController?.isSessionActive == true
    private fun liveStartCapture() {
        companionLiveController?.startCapture()
    }
    private fun liveCancelCapture() {
        companionLiveController?.cancelCapture()
    }
    /** Have the Live model relay an agent question aloud — see CompanionLiveController.askUserAloud. */
    private fun liveSpeak(question: String) {
        companionLiveController?.askUserAloud(question)
    }

    private fun liveSendText(text: String) {
        companionLiveController?.sendTextCommand(text)
    }

    /** True when the Gemini Live audio layer is active. */
    private val useGeminiLive: Boolean
        get() {
            val prefs = getSharedPreferences("aura_settings", Context.MODE_PRIVATE)
            return prefs.getBoolean("use_gemini_live", false)
        }

    // ViewModel for conversation state (service-scoped)
    private val conversationViewModel = ConversationViewModel()

    // ── Phase 3 slice 1 — on-device agent (typed-text path) ──────────────────
    // AuraAgent is per-call self-contained (each run spins up its own in-process
    // MCP server + bridges and tears them down in finally), so the overlay can
    // own one instance and call runFromSavedSettings() directly — no bridge
    // hoisting needed for correctness. [agentRunJob] tracks the active run so the
    // cancel button can stop a long (maxIterations=50) loop mid-flight.
    private val auraAgent by lazy { AuraAgent(applicationContext) }

    /** Writes each finished overlay conversation into the diary. See [AgentLaneEpisodeWriter]. */
    private val episodeWriter by lazy {
        val memory = com.aura.aura_ui.agent.memory.memoryService(applicationContext)
        val reconciler = com.aura.aura_ui.agent.conversation.brainFactReconciler(applicationContext, memory)
        com.aura.aura_ui.agent.conversation.AgentLaneEpisodeWriter(
            com.aura.aura_ui.agent.conversation.AgentBrainSummarizer(applicationContext),
            memory,
            saveFact = { reconciler.reconcile(it) },
        )
    }

    /**
     * The goal an ACTION_RUN_TASK intent handed us — the eval cockpit and AndroidWorld's only door.
     * A run whose text matches it is a benchmark task, not something the person asked for, so it
     * stays out of memory.
     */
    @Volatile private var harnessTaskText: String? = null
    private val providerKeyStore by lazy { ProviderKeyStore(applicationContext) }
    private var agentRunJob: Job? = null

    // Voice input → on-device agent (Phase 3 slice 2b). Groq Whisper STT; the LLM
    // provider can be anything, but Whisper needs a Groq key. whisperActive tracks an
    // in-progress push-to-talk capture so the mic button knows to stop+transcribe.
    private val whisperStt by lazy { WhisperSttController(applicationContext) }

    /** Voice-communication audio mode (AEC path + speakerphone) for the cascade loop. */
    private val commAudioMode by lazy {
        com.aura.aura_ui.audio.CommAudioModeController(applicationContext)
    }

    /** Elapsed-time stamp when the current TTS playback began — AEC convergence guard. */
    private var ttsStartElapsed = 0L
    private var whisperActive = false

    // Voice output (Phase 3 slice 2b — TTS). The agent's final answer is spoken via
    // Android TextToSpeech, which on Google-TTS devices uses offline neural voices.
    // Explicit Lazy so cleanup can release the engine only if it was ever started.
    private val ttsManagerLazy = lazy { AuraTTSManager(applicationContext) }
    private val ttsManager get() = ttsManagerLazy.value

    // Tap-to-type: the overlay window is FLAG_NOT_FOCUSABLE by default (so it never
    // steals the global input/focus channel), which also blocks the IME. We flip it
    // focusable only while the user is typing. Compose-backed so the input bar
    // recomposes (requests focus / shows keyboard) when this changes.
    private val inputActiveState = mutableStateOf(false)

    /**
     * Toggle the overlay window's input focusability via [WindowManager.updateViewLayout].
     * The overlay is [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] by default (see
     * [createWindowParams]) — that's what keeps a screen-sized transparent window from
     * stealing focus from apps beneath it, but it also prevents the soft keyboard from
     * attaching. Clear the flag only while typing, then restore it.
     */
    private fun setOverlayInputFocusable(focusable: Boolean) {
        val view = overlayView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val currentlyFocusable =
            (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (currentlyFocusable == focusable) return
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager?.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "updateViewLayout(focusable=$focusable) failed: ${it.message}") }
        Log.d(TAG, "Overlay input focusable=$focusable")
    }

    /**
     * Enable or disable pointer delivery to the overlay panel window.
     *
     * NOT_TOUCH_MODAL only lets events land outside the window reach the app behind. The
     * panel itself still consumes anything inside its bounds, and it sits over the bottom
     * of the screen where the agent frequently taps — so automation must make the window
     * explicitly NOT_TOUCHABLE before the underlying app receives any gesture.
     */
    private fun setOverlayTouchable(touchable: Boolean) {
        val view = overlayView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return

        // THE INVARIANT: while the agent is driving the device, this window must not
        // take touches — no matter which path asked for them.
        //
        // The old code minimized (and so un-touchabled) only on the first *device*
        // action, which left the window touchable through set_plan / launch_app /
        // perceive_screen, and re-touchable after every restore. Those gaps are the
        // measured freeze. Enforcing it here rather than at each call site means a
        // future caller cannot reopen the hole by accident.
        //
        // HITL is the deliberate exception: ask_user draws option chips the user is
        // required to tap, and the run is blocked waiting on that answer, so nothing
        // is being driven underneath.
        val desired = touchable && (!agentDrivingNow || hitlAwaitingInput)
        if (touchable && !desired) {
            Log.d(TAG, "Overlay touch request refused — agent is driving")
        }

        val currentlyTouchable =
            (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
        if (currentlyTouchable == desired) return
        params.flags = if (desired) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager?.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "updateViewLayout(touchable=$desired) failed: ${it.message}") }
        Log.d(TAG, "Overlay touchable=$desired (requested=$touchable, agentDriving=$agentDriving)")
    }

    /** User tapped the text field — make the window focusable so the IME can attach. */
    private fun activateTextInput() {
        setOverlayInputFocusable(true)
        inputActiveState.value = true
    }

    /** Typing ended (send / focus loss / dismiss) — restore the non-focusable window. */
    private fun deactivateTextInput() {
        inputActiveState.value = false
        setOverlayInputFocusable(false)
    }

    /** True once the user has configured a provider (key when required + selected model). */
    private fun isAgentConfigured(): Boolean {
        val id = providerKeyStore.getSelectedEndpointId()
        val endpoint = com.aura.aura_ui.agent.llm.LlmEndpointCatalog.builtinById(id)
            ?: com.aura.aura_ui.mcp.bridge.CustomEndpointStore(
                com.aura.aura_ui.agent.memory.EncryptedJsonStore(
                    applicationContext,
                    com.aura.aura_ui.mcp.bridge.CustomEndpointStore.STORE_NAME,
                ),
            ).allEndpoints().firstOrNull { it.id == id }
        val keyOk = endpoint?.requiresKey == false || providerKeyStore.getKey(id) != null
        return keyOk && !providerKeyStore.getSelectedModel(id).isNullOrBlank()
    }

    /**
     * Route a typed command to the on-device [AuraAgent] instead of the legacy
     * Python `/ws/device` path. Renders the user message + THINKING, runs the
     * agent loop, then shows the final answer (or a friendly error) and returns
     * to IDLE. The run is cancellable via the overlay/notification cancel buttons
     * ([agentRunJob]); only one run is active at a time.
     *
     * Note: this is the always-works *typed* floor (Phase 3 slice 1). Speaking the
     * result (TTS) and spoken input (STT) are the next slices.
     */
    private fun runOnDeviceAgent(text: String, source: String = "text") {
        if (text.isBlank()) return

        if (agentRunJob?.isActive == true) {
            Log.d(TAG, "runOnDeviceAgent: a run is already active — ignoring '$text'")
            return
        }
        agentRunJob = serviceScope.launch { executeAgent(text, source) }
    }

    /**
     * The agent-run body itself. Assumes it is already running inside a tracked
     * coroutine ([agentRunJob]) — both the typed path ([runOnDeviceAgent]) and the
     * voice path ([stopWhisperAndRun]) call it from within that job. Crucially, the
     * voice path calls this *directly* rather than via [runOnDeviceAgent]: the
     * transcription and the run share one job, so the "already active" re-entrancy
     * guard in [runOnDeviceAgent] would otherwise reject the run against itself and
     * leave the overlay stuck on THINKING.
     */
    /**
     * Say what is about to happen, then run it.
     *
     * Spoken through [ttsManager] — the service's own engine — and NOT by the caller. The eval
     * cockpit used to announce tasks with a TextToSpeech instance of its own, and because Android's
     * TTS service is shared, that second engine's QUEUE_FLUSH cut this one off mid-reply. It looked
     * like the agent was being terminated before it could speak; it was being talked over.
     *
     * The run starts from the completion callback rather than alongside the announcement, so the
     * user hears which task is beginning before the screen starts moving under them.
     */
    private fun announceThenRun(task: String) {
        conversationViewModel.updatePhase(ConversationPhase.RESPONDING)
        ttsManager.speak("Okay, I am starting this task. $task", TTS_VOICE_ID) {
            // Fires on a TTS thread; serviceScope.launch is the safe way back onto our own context.
            serviceScope.launch { runOnDeviceAgent(task, source = "external") }
        }
    }

    /**
     * If the agent is suspended on an `ask_user` question, treat [text] as the answer.
     *
     * ### Why this is a chokepoint and not a branch in one caller
     *
     * The typed path had this check and the SPOKEN path did not, so answering out loud started a
     * brand-new run — the agent asked "which Amma?", the user said "Amma", and that became a
     * fresh task while the original run sat waiting for an answer that had already been given.
     * Voice is the default input on this device (Silero → Whisper → agent), so the lane most
     * people use was the broken one.
     *
     * One rule, stated once: **while a question is pending, the next thing the user says or types
     * is the answer to it.** Every lane routes through here — typed text, transcribed speech, and
     * the Live plane's transcript — so a new input path cannot quietly miss it.
     *
     * Answering is idempotent and first-wins: [AskUserBroker.answer] is synchronized and returns
     * false for a stale id, so a voice answer and a tapped chip racing each other resolve once.
     *
     * @return true when the text was consumed as an answer and must NOT start a run.
     */
    private fun answerPendingQuestion(text: String): Boolean {
        val pendingQ = AskUserBroker.shared.pending.value ?: return false
        if (text.isBlank()) return false
        val accepted = AskUserBroker.shared.answer(pendingQ.id, text.trim())
        if (accepted) {
            Log.i(TAG, "ask_user answered: \"${text.trim()}\"")
            conversationViewModel.addUserMessage(text.trim())
        }
        return accepted
    }

    /** @param source `text`, `voice` or `external`, for the `agent_task` analytics event */
    private suspend fun executeAgent(text: String, source: String) {
        // Before anything else, including the control-command parser below: an answer is not a
        // command and must not be reinterpreted as one. See [answerPendingQuestion].
        if (answerPendingQuestion(text)) return

        // Spec 2026-08-02 — control commands are handled HERE, not in runOnDeviceAgent.
        // This function is the funnel BOTH paths reach: the typed path arrives via
        // runOnDeviceAgent, and the voice path calls this directly (see the KDoc above —
        // it bypasses the re-entrancy guard on purpose). The hook lived upstairs, so
        // spoken "pause" only ever worked on typed input, which is exactly the reported
        // "it says paused but it is actually running".
        if (handleControlUtterance(text)) return

        // Spec 2026-08-02 — refuse new work while the human has the wheel, OUT LOUD.
        // Previously a new task ran silently while the pill read "Paused". A UI that
        // lies is worse than a pause that fails, because the user acts on what they see.
        val lock = com.aura.aura_ui.services.ControlLockStore.shared
        val pausedOn = lock.activeTask
        if (lock.isPausedByHuman && pausedOn != null) {
            val refusal = "I'm paused on $pausedOn. Say continue to carry on, or drop it to start something new."
            conversationViewModel.addUserMessage(text)
            conversationViewModel.addAssistantMessage(refusal)
            respondWithVoice(refusal)
            return
        }
        // Paused, but on nothing — there is no half-finished task to protect, so the
        // refusal had nothing to name ("I'm paused." → paused on WHAT?) and no purpose.
        // Someone opening AURA and asking for something IS the hand-back; making them say
        // "continue" first, to continue nothing, is a dead end dressed up as safety.
        if (lock.isPausedByHuman) lock.resume()
        lock.noteTaskStarted(text)

        val remember = harnessTaskText != text
        if (!remember) harnessTaskText = null

        conversationViewModel.clearAgentOutputs()
        conversationViewModel.clearAgentSteps()
        conversationViewModel.addUserMessage(text)
        conversationViewModel.updatePhase(ConversationPhase.THINKING)
        // Kept so the run's outcome can be reported as an ERROR rather than as a reply that
        // happens to read like an apology — the eval harness must not score a provider outage as
        // the agent failing the task.
        var failure: Throwable? = null
        // Counts only, for the `agent_task` analytics event; the events' text stays here.
        var steps = 0
        var toolFailures = 0
        val startedAt = System.currentTimeMillis()
        val reply = try {
            withContext(Dispatchers.IO) {
                auraAgent.runFromSavedSettings(text, remember = remember) { event ->
                    when (event) {
                        is com.aura.aura_ui.agent.AgentStreamEvent.ToolStarting -> steps++
                        is com.aura.aura_ui.agent.AgentStreamEvent.ToolFailed -> toolFailures++
                        else -> Unit
                    }
                    onAgentStreamEvent(event)
                }
            }
        } catch (ce: CancellationException) {
            com.aura.aura_ui.telemetry.AuraAnalytics.agentTask(
                this, source, "cancelled", steps, toolFailures, System.currentTimeMillis() - startedAt,
            )
            // Structured cancellation (user dismissed) — onDismiss owns the phase
            // reset and stops TTS; don't speak or fall to IDLE here.
            //
            // But a waiter still has to be told, or it waits out its own timeout on a run that is
            // already over — measured 2026-08-26: two eval tasks cancelled at 4.4 s and 7.2 s were
            // both filed as 360-second timeouts. NonCancellable because this scope is already
            // cancelling and a plain emit would go with it.
            withContext(NonCancellable) {
                com.aura.aura_ui.agent.AgentRunBridge.publishCancelled(text)
            }
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "On-device agent run failed: ${t.message}", t)
            failure = t
            t.message ?: "The on-device agent hit an error. Check Settings → Agent Brain."
        }
        com.aura.aura_ui.telemetry.AuraAnalytics.agentTask(
            this, source, if (failure == null) "done" else "failed", steps, toolFailures,
            System.currentTimeMillis() - startedAt, failure,
        )
        conversationViewModel.addAssistantMessage(reply)
        // Tell anyone waiting on this run that it finished. startService() is fire-and-forget, so
        // without this the eval cockpit would have no way to know a task it asked for is done.
        // Published before speaking: the waiter should not be held hostage to TTS finishing.
        com.aura.aura_ui.agent.AgentRunBridge.publish(
            com.aura.aura_ui.agent.AgentRunBridge.Outcome(goal = text, reply = reply, error = failure),
        )
        // Speak the answer (a Q&A reply, or the automation's completion summary). TTS
        // owns the terminal phase: RESPONDING while speaking → IDLE when done.
        respondWithVoice(reply)
    }

    /**
     * Spec 2026-08-02 — spoken pause / resume / drop.
     *
     * Returns true when the utterance was a control command and must NOT become a task.
     *
     * Every branch speaks. A silent pause or resume leaves the user unable to tell which
     * switch took effect — the "two switches out of sync" complaint — and the confirmation
     * costs one short sentence.
     *
     * @return true if handled as a control command
     */
    private suspend fun handleControlUtterance(text: String): Boolean {
        val lock = com.aura.aura_ui.services.ControlLockStore.shared
        val command = com.aura.aura_ui.services.VoiceControl.parse(text) ?: return false

        val spoken = when (command) {
            com.aura.aura_ui.services.VoiceControl.Command.PAUSE,
            com.aura.aura_ui.services.VoiceControl.Command.STOP,
            -> {
                lock.pause()
                "Paused. Say continue when you're ready."
            }

            com.aura.aura_ui.services.VoiceControl.Command.RESUME -> {
                // Naming the task is the difference between "continuing" (continuing
                // WHAT?) and knowing the right thing resumed.
                val onTask = lock.activeTask?.let { " with $it" } ?: ""
                lock.resume()
                "Continuing$onTask."
            }

            com.aura.aura_ui.services.VoiceControl.Command.DROP -> {
                lock.dropTask()
                "Dropped it. What would you like instead?"
            }
        }

        Log.d(TAG, "Voice control: $command (from '$text')")
        conversationViewModel.addUserMessage(text)
        conversationViewModel.addAssistantMessage(spoken)
        respondWithVoice(spoken)
        return true
    }

    // ── Build 3 — resumable runs ─────────────────────────────────────────────
    // A run that died mid-task (app killed, budget, crash) leaves a persisted ledger. On the
    // resting overlay we offer to continue it (never auto-resume — user decision): a chip that
    // routes to AuraAgent.resumeRun, or a Dismiss that abandons the ledger so it stops re-offering.

    /** Query the persisted ledger store off-main and surface a resume chip, if any. */
    private fun refreshResumeOffer() {
        serviceScope.launch {
            val offer = withContext(Dispatchers.IO) {
                runCatching {
                    val store = RunLedgerStore(EncryptedJsonStore(applicationContext, RunLedgerStore.STORE_NAME))
                    ResumeOffer.from(store.latestResumable())
                }.getOrNull()
            }
            conversationViewModel.setResumeOffer(offer)
        }
    }

    /** User tapped the resume chip → continue the interrupted run through the normal agent surface. */
    private fun handleResumeClick() {
        val offer = conversationViewModel.state.value.resumeOffer ?: return
        conversationViewModel.setResumeOffer(null)
        if (agentRunJob?.isActive == true) {
            Log.d(TAG, "handleResumeClick: a run is already active — ignoring")
            return
        }
        agentRunJob = serviceScope.launch { executeResume(offer) }
    }

    /** User dismissed the resume chip → mark the ledger abandoned so it is never re-offered. */
    private fun dismissResumeOffer() {
        val offer = conversationViewModel.state.value.resumeOffer ?: return
        conversationViewModel.setResumeOffer(null)
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                RunLedgerStore(EncryptedJsonStore(applicationContext, RunLedgerStore.STORE_NAME)).dismiss(offer.ledgerId)
            }
        }
    }

    /** Resume body — mirrors [executeAgent] but continues the ledger via [AuraAgent.resumeRun]. */
    private suspend fun executeResume(offer: ResumeOffer) {
        conversationViewModel.clearAgentOutputs()
        conversationViewModel.clearAgentSteps()
        conversationViewModel.addUserMessage("Resume: ${offer.goal}")
        conversationViewModel.updatePhase(ConversationPhase.THINKING)
        val reply = try {
            withContext(Dispatchers.IO) {
                auraAgent.resumeRun(offer.ledgerId, remember = true) { event -> onAgentStreamEvent(event) }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Resume run failed: ${t.message}", t)
            t.message ?: "Couldn't resume the task. Check Settings → Agent Brain."
        }
        conversationViewModel.addAssistantMessage(reply)
        respondWithVoice(reply)
    }

    /**
     * Speak [text] with the neural TTS engine and drive the closing phase. Replaces
     * the old unconditional finally→IDLE: the run isn't "done" until AURA has finished
     * speaking, so IDLE is set from the TTS completion callback.
     */
    private fun respondWithVoice(text: String) {
        if (text.isBlank()) {
            conversationViewModel.updatePhase(ConversationPhase.IDLE)
            commAudioMode.exit()
            return
        }
        conversationViewModel.updatePhase(ConversationPhase.RESPONDING)
        // Edge-TTS exposes no PCM stream, so drive the chat waveform with a
        // speech-shaped synthetic pulse while playback runs (the Live path uses
        // real playback amplitude). Cancelled the moment RESPONDING ends.
        speakingPulseJob?.cancel()
        speakingPulseJob = serviceScope.launch {
            val rnd = java.util.Random()
            while (conversationViewModel.state.value.conversationState == ConversationPhase.RESPONDING) {
                audioAmplitude.floatValue = 0.25f + rnd.nextFloat() * 0.5f
                kotlinx.coroutines.delay(90)
            }
            audioAmplitude.floatValue = 0f
        }
        // Barge-in: keep the mic + VAD hot while TTS plays so the user can interrupt —
        // but ONLY when a hardware AEC actually attached, else the speaker feeds straight
        // back into the mic. Without AEC we go half-duplex: mic closed during playback,
        // reopened for the follow-up once TTS stops (echo-safe because the speaker is silent).
        var bargeInArmed = false
        if (!whisperActive && startCascadeListening(phase = ConversationPhase.RESPONDING)) {
            if (whisperStt.aecActive) {
                bargeInArmed = true
                ttsStartElapsed = SystemClock.elapsedRealtime()
            } else {
                Log.i(TAG, "no AEC → half-duplex, mic closed during TTS")
                whisperActive = false
                whisperStt.cancel()
                audioAmplitude.floatValue = 0f
            }
        }
        ttsManager.speak(text, TTS_VOICE_ID) {
            // Fires on a TTS thread when playback finishes. Only act if we're still the
            // active response — a dismiss, or a barge-in that already moved us to
            // LISTENING, means someone else owns the phase now.
            if (conversationViewModel.state.value.conversationState == ConversationPhase.RESPONDING) {
                startFollowUpWindow(alreadyListening = bargeInArmed && whisperActive)
            }
        }
    }

    /**
     * After the assistant finishes speaking, open (or keep) the mic for a short
     * follow-up so the user can continue hands-free; wind down to IDLE if they don't.
     * [alreadyListening] is true when full-duplex barge-in kept the mic hot through TTS.
     */
    private fun startFollowUpWindow(alreadyListening: Boolean) {
        val listening = alreadyListening || startCascadeListening(phase = ConversationPhase.LISTENING)
        if (!listening) {
            conversationViewModel.updatePhase(ConversationPhase.IDLE)
            commAudioMode.exit()
            return
        }
        conversationViewModel.updatePhase(ConversationPhase.LISTENING)
        followUpJob?.cancel()
        followUpJob = serviceScope.launch {
            // While the agent is suspended on an ask_user question, the mic stays open until the
            // question resolves. The 8s window is sized for "anything else?" after a finished
            // reply; a question the user has to think about is routinely answered later than
            // that, and closing the mic at t+8s left them talking to a phone that had stopped
            // listening — the card was still up, so it looked like AURA was ignoring them.
            // first { it == null } returns immediately when nothing pends, so the ordinary
            // follow-up is unchanged. The broker's own 120s deadline bounds this wait.
            // Cost: comm audio mode stays entered for the life of the question, so media
            // routing is not restored until it resolves — correct when the question IS the run.
            AskUserBroker.shared.pending.first { it == null }
            kotlinx.coroutines.delay(FOLLOW_UP_WINDOW_MS)
            // Nothing said → wind down to IDLE and leave comm mode (restores media routing).
            if (whisperActive &&
                conversationViewModel.state.value.conversationState == ConversationPhase.LISTENING
            ) {
                whisperActive = false
                whisperStt.cancel()
                audioAmplitude.floatValue = 0f
                conversationViewModel.updatePhase(ConversationPhase.IDLE)
                commAudioMode.exit()
            }
        }
    }

    /** Synthetic waveform driver for classic-TTS speech (no PCM available). */
    private var speakingPulseJob: kotlinx.coroutines.Job? = null

    /**
     * Map a live [AgentStreamEvent] into the conversation trace. Fires on the agent's
     * IO coroutine; [conversationViewModel] mutates StateFlow values, which is safe
     * from any thread. (B will additionally consume these to minimize on the first
     * WRITE-scoped tool and drive the pill verb; D will restyle the trace.)
     */
    private fun onAgentStreamEvent(event: AgentStreamEvent) {
        when (event) {
            is AgentStreamEvent.ToolStarting -> {
                val verb = toolToVerb(event.tool)
                conversationViewModel.startAgentStep(event.tool, verb, prettifyArgs(event.args))
                // WRITE scope = the tool drives the device → this run is an automation,
                // not a Q&A. Source of truth is McpToolScopes (shared with the server).
                val isDeviceAction =
                    McpToolScopes.requiredScopeFor(event.tool) == McpScope.WRITE
                // HITL: ask_user is a client-side tool that renders a question card
                // in the overlay. It must NEVER minimize — doing so hides the card
                // and leaves the agent stuck waiting for an answer the user can't see.
                val isHitlTool = event.tool == "ask_user"
                // minimize/restore/pushLiveUpdate touch views + notifications → main thread.
                serviceScope.launch {
                    _workingVerb = verb
                    // Mark the run live on the FIRST tool call, not the first device
                    // action. launch_app and perceive_screen already put the target app
                    // on screen, and the old code left the overlay touchable across
                    // exactly that window.
                    agentDriving = true
                    hitlAwaitingInput = isHitlTool
                    // Only cut touches when the panel is also going away (or already gone).
                    // A READ-scope tool (perceive_screen on the very first step, near enough
                    // every run) does not minimize the panel — going untouchable here while
                    // it stays fully drawn and expanded left a chat window on screen that
                    // looked normal but silently passed every tap through to whatever app
                    // sat underneath it. That fallen-through touch landed on the real app,
                    // read as a human grabbing the phone, and paused the run on its own UI.
                    if (!isHitlTool && (isDeviceAction || _isMinimized.value)) {
                        setOverlayTouchable(false)
                    }
                    Log.w(
                        "OVL_TRACE",
                        "ToolStarted tool=${event.tool} isHitl=$isHitlTool " +
                            "isDeviceAction=$isDeviceAction visible=${_isOverlayVisible.value} " +
                            "minimized=${_isMinimized.value}",
                    )
                    when {
                        // HITL tool — keep the overlay open (restore if already minimized)
                        // so the AskUserCard is visible.
                        isHitlTool && _isMinimized.value -> restoreOverlay()
                        isHitlTool -> { /* already visible — nothing to do */ }
                        // First device-driving action → get the chat out of the way and
                        // let the notch pill narrate the work.
                        isDeviceAction && _isOverlayVisible.value && !_isMinimized.value ->
                            minimizeOverlay()
                        // Already minimized → refresh the pill verb for this step.
                        _isMinimized.value -> pushLiveUpdate("$verb…")
                    }
                }
            }
            is AgentStreamEvent.ToolCompleted ->
                // Keep the args as the step subtitle (pass blank); status flips to DONE.
                conversationViewModel.completeAgentStep(event.tool, "")
            is AgentStreamEvent.ToolFailed ->
                conversationViewModel.failAgentStep(event.tool, event.error)
            // Run finished → restore the chat so the user sees the final answer
            // (added as the assistant bubble by runOnDeviceAgent). Minimize is not
            // a one-way trip.
            is AgentStreamEvent.Finished ->
                serviceScope.launch {
                    // Release the touch guard BEFORE restoring, or restoreOverlay's
                    // setOverlayTouchable(true) is refused and the chat comes back
                    // unable to receive taps.
                    agentDriving = false
                    hitlAwaitingInput = false
                    if (_isMinimized.value) restoreOverlay() else setOverlayTouchable(true)
                }
        }
    }

    /** Follow-up countdown after TTS finishes; cancelled the moment anyone speaks. */
    private var followUpJob: kotlinx.coroutines.Job? = null

    /**
     * Start (or restart) cascade listening. Used by the mic tap, by barge-in
     * capture while TTS speaks, and by the follow-up window after TTS ends.
     * Returns false when the cascade isn't available (no key/permission/agent,
     * or a Live mode owns the audio).
     */
    private fun startCascadeListening(phase: ConversationPhase = ConversationPhase.LISTENING): Boolean {
        if (liveActive()) return false
        val groqKey = providerKeyStore.getKey(LlmEndpointCatalog.GROQ_ID)
        if (!isAgentConfigured() || groqKey.isNullOrBlank() || !whisperStt.hasMicPermission()) return false
        conversationViewModel.clearError()
        // Enter comm mode BEFORE capture so the AEC binds to the voice-comm path and
        // TTS (also on that path) is cancelled from the mic. Idempotent across turns.
        commAudioMode.enter()
        whisperActive = true
        whisperStt.start(
            onAmplitude = { amp ->
                // During RESPONDING the synthetic speaking pulse owns the waveform.
                if (conversationViewModel.state.value.conversationState == ConversationPhase.LISTENING) {
                    audioAmplitude.floatValue = amp
                }
            },
            onSpeechStart = { onCascadeSpeechStart() },
            onSpeechEnd = { stopWhisperAndRun() },
        )
        conversationViewModel.updatePhase(phase)
        return true
    }

    /**
     * Sustained speech detected. If the assistant is mid-reply this is a
     * barge-in: kill TTS and hand the floor to the user. Either way the
     * follow-up idle countdown is cancelled — someone is talking.
     */
    private fun onCascadeSpeechStart() {
        followUpJob?.cancel()
        if (conversationViewModel.state.value.conversationState == ConversationPhase.RESPONDING) {
            // Hardware AEC needs a moment to converge after playback starts; onsets in
            // that window are residual echo, not the user. Ignore them so the assistant
            // doesn't barge in on its own first syllable.
            if (SystemClock.elapsedRealtime() - ttsStartElapsed < AEC_CONVERGENCE_MS) {
                Log.d(TAG, "barge-in onset ignored (AEC convergence window)")
                return
            }
            Log.i(TAG, "barge-in — stopping TTS, user has the floor")
            if (ttsManagerLazy.isInitialized()) ttsManager.stop()
            speakingPulseJob?.cancel()
            audioAmplitude.floatValue = 0f
            conversationViewModel.updatePhase(ConversationPhase.LISTENING)
        }
    }

    /** Stop the push-to-talk Whisper capture, transcribe via Groq, and run the result. */
    private fun stopWhisperAndRun() {
        // Guard against double-fire (VAD auto-stop racing a manual mic tap).
        if (!whisperActive) return
        followUpJob?.cancel()
        Log.i(TAG, "⏹️ stopWhisperAndRun — transcribing")
        whisperActive = false
        audioAmplitude.floatValue = 0f
        val groqKey = providerKeyStore.getKey(LlmEndpointCatalog.GROQ_ID)
        if (groqKey.isNullOrBlank()) {
            whisperStt.cancel()
            conversationViewModel.updatePhase(ConversationPhase.IDLE)
            return
        }
        conversationViewModel.updatePhase(ConversationPhase.THINKING)
        // Track the transcription on agentRunJob so a dismiss/cancel during the THINKING
        // window aborts it — otherwise the HTTP call would complete and launch a full
        // agent run against an overlay the user already dismissed.
        agentRunJob = serviceScope.launch {
            val text = try {
                withContext(Dispatchers.IO) { whisperStt.stopAndTranscribe(groqKey, language = null) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Whisper transcribe failed: ${t.message}", t)
                conversationViewModel.addAssistantMessage(t.message ?: "Couldn't transcribe that.")
                conversationViewModel.updatePhase(ConversationPhase.IDLE)
                return@launch
            }
            if (text.isBlank()) {
                conversationViewModel.updatePhase(ConversationPhase.IDLE)
                return@launch
            }
            conversationViewModel.addRecentCommand(text)
            // Run on *this* job, not via runOnDeviceAgent() — we are already inside
            // agentRunJob, so its "already active" guard would reject the handoff and
            // strand the overlay on THINKING. One continuous job also means onDismiss's
            // single agentRunJob.cancel() aborts transcription and the run as a unit.
            executeAgent(text, source = "voice")
        }
    }

    /** Short present-tense verb shown on the notch pill while [tool] runs.
     * Delegates to the shared [ToolVerbs] map so the classic path and the
     * Gemini Live path can never show different verbs for the same tool. */
    private fun toolToVerb(tool: String): String = ToolVerbs.verbFor(tool)

    /**
     * Turn a raw JSON args blob into a compact human subtitle for the step timeline.
     * `{"package_name":"com.android.clock"}` → `package_name: com.android.clock`.
     * Empty/`{}` args → "" (the step shows just its verb).
     */
    private fun prettifyArgs(args: String): String =
        args.trim()
            .removeSurrounding("{", "}")
            .replace("\"", "")
            .replace(":", ": ")
            .replace(",", ", ")
            .trim()
            .take(120)

    // UI State (observable)
    private val audioAmplitude = mutableFloatStateOf(0f)

    // Legacy Python backend URL, resolved from user settings at connect time.
    // Empty means "not configured" — the on-device MCP server is canonical and
    // no network backend is required for a normal install.
    private var serverUrl: String = ""

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Edge lighting must honour the saved value even when this service starts without
        // MainActivity having run — boot autostart, wake word. The observer's lifetime is
        // the SERVICE, not the panel: the glow can also be up on MCP-connect with no panel
        // ever shown, and the setting has to reach that window too.
        ThemeManager.hydrateEdgeGlow(this)
        observeEdgeGlowSetting()

        // CRITICAL: set transparent theme BEFORE any view is created.
        // ComposeView(context) inherits the context's windowBackground drawable.
        // Without this, the service context uses Theme.Material.Light which draws
        // a white/dark opaque background behind every overlay ComposeView.
        setTheme(R.style.Theme_Aura_Transparent)

        // Initialize lifecycle
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        windowManager = windowManagerContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Create notification channel
        createNotificationChannel()

        // ── On-screen run surface: status strip + run controls (2026-09-07) ───
        //
        // Both windows follow the same two facts, combined in one place. Letting the run
        // flag and the pause flag each drive their own show/hide is how you end up with a
        // Resume button offering to resume a run that already ended.
        //
        // Driven off the control lock's LOCAL run flag: a remote MCP client has no
        // "finished" event, so it cannot turn these off again. Remote-driven runs keep the
        // notification's Cancel and get no on-screen controls.
        serviceScope.launch {
            val lock = com.aura.aura_ui.services.ControlLockStore.shared
            kotlinx.coroutines.flow.combine(
                lock.localRunning,
                lock.pausedByHuman,
            ) { running, paused -> running to paused }
                .collect { (running, paused) ->
                    pausePill.render(localRunActive = running, paused = paused)
                    // On the TRANSITION into a run only — during the run AURA may speak, and
                    // clearing on every emission would swallow that.
                    if (running && !runWasActive) {
                        com.aura.aura_ui.services.AgentStatusRegistry.clearSpeech()
                    }
                    runWasActive = running
                    // Controls follow the RUN; the strip follows the CONVERSATION, which is
                    // wider — it stays up while the chat overlay is open so a spoken exchange
                    // (and a barge-in) is visible even when no tool is being called.
                    renderStatusStrip(runActive = running)
                }
        }

        // The strip reads the registry and nothing else, so the *source* of the status line
        // stays swappable — today the verb the notification already derives, a model-written
        // sentence later — without this file changing.
        serviceScope.launch {
            com.aura.aura_ui.services.AgentStatusRegistry.beat.collect { statusStrip.setBeat(it) }
        }
        serviceScope.launch {
            com.aura.aura_ui.services.AgentStatusRegistry.speech.collect { statusStrip.setSpeech(it) }
        }

        // The chat overlay opening or closing changes the strip's answer too — a spoken
        // exchange with no tool call still belongs on screen.
        serviceScope.launch {
            _isOverlayVisible.collect {
                renderStatusStrip(
                    runActive = com.aura.aura_ui.services.ControlLockStore.shared.localRunning.value,
                )
            }
        }

        // AURA's own windows must not appear in AURA's own screenshots. Perception would
        // give a white pill carrying a Cancel button a som_id on every single screen — and
        // the model could then tap it and cancel its own run.
        com.aura.aura_ui.services.OverlayCaptureGate.hook =
            object : com.aura.aura_ui.services.OverlayCaptureGate.Hook {
                override fun hideForCapture() {
                    statusStrip.setHiddenForCapture(true)
                    pausePill.setHiddenForCapture(true)
                }

                override fun restoreAfterCapture() {
                    statusStrip.setHiddenForCapture(false)
                    pausePill.setHiddenForCapture(false)
                }

                // Only the controls: the status strip cannot swallow a gesture.
                override fun controlsBounds() = pausePill.boundsOnScreen()
            }

        // Publishes the conversation phase (Listening/Thinking/Speaking) to the
        // process-global VoiceStatusRegistry, which AssistantForegroundService
        // merges into the single promoted chip. Registered ONCE here — it used
        // to live inside the legacy Gemini-Live branch only, so the BYOK
        // companion and classic Whisper modes never showed "Listening".
        serviceScope.launch {
            combine(conversationViewModel.state, _isOverlayVisible) { state, visible -> state to visible }
                .collect { (state, visible) ->
                // Hidden = idle. Returning early here instead left the last phase (usually
                // Listening) in the registry, pinning the chip after the overlay closed.
                if (!visible) {
                    com.aura.aura_ui.services.VoiceStatusRegistry.set(com.aura.aura_ui.services.AuraStatus.Idle)
                    return@collect
                }
                if (_isMinimized.value) return@collect // automation pill owns the notch
                val status = when (state.conversationState) {
                    ConversationPhase.LISTENING -> com.aura.aura_ui.services.AuraStatus.Listening
                    ConversationPhase.THINKING -> com.aura.aura_ui.services.AuraStatus.Thinking
                    ConversationPhase.RESPONDING -> com.aura.aura_ui.services.AuraStatus.Speaking
                    else -> com.aura.aura_ui.services.AuraStatus.Idle
                }
                com.aura.aura_ui.services.VoiceStatusRegistry.set(status)
            }
        }

        Log.i(TAG, "AuraOverlayService created")
    }

    /**
     * The `foregroundServiceType` this service declares in the manifest.
     * Android 14+ validates the claim against live permissions, so it has to be
     * narrowed at runtime — see [ForegroundServiceCompat.startForegroundSafely].
     */
    private val declaredFgsTypes: Int
        get() = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    /**
     * Enter the foreground without ever taking the process down.
     *
     * Android 14+ throws `SecurityException` from `startForeground()` when the
     * `microphone` type is claimed without a live RECORD_AUDIO grant — which is
     * the normal state for a user who declined the mic, or revoked it later.
     * Unguarded, that exception propagated out of `onStartCommand` and the
     * assistant simply never appeared, with no visible error.
     */
    private fun enterForeground(notification: Notification): Boolean =
        startForegroundSafely(NOTIFICATION_ID, notification, declaredFgsTypes)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service immediately (never fatally).
        if (!enterForeground(createNotification())) {
            Log.w(TAG, "Could not enter foreground — overlay cannot run in this state")
            stopSelf()
            return START_NOT_STICKY
        }

        // Starts that bypass show()/showAndListen() (the pause pill, the run-task action, a
        // sticky restart) must not open the assistant or run a task while the gate blocks.
        val opens = intent?.action.let { it == ACTION_SHOW || it == ACTION_SHOW_AND_LISTEN || it == ACTION_RUN_TASK || it == null }
        if (opens && com.aura.aura_ui.remote.RemoteGateManager.isBlocked()) {
            Log.w(TAG, "Remote gate blocks AURA — not opening the overlay")
            hideOverlay()
            // Only when a person asked; a sticky restart (null intent) just stops quietly.
            if (intent != null) com.aura.aura_ui.remote.GateEnforcer.openBlockScreen(this)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_SHOW_AND_LISTEN -> showOverlayAndStartListening()
            ACTION_HIDE -> hideOverlay()
            ACTION_RUN_TASK -> {
                val task = intent.getStringExtra(EXTRA_TASK_TEXT)?.trim().orEmpty()
                if (task.isEmpty()) {
                    Log.w(TAG, "ACTION_RUN_TASK with no $EXTRA_TASK_TEXT — nothing to run")
                } else {
                    Log.i(TAG, "Run task requested externally: \"$task\"")
                    harnessTaskText = task
                    episodeWriter.excludeCurrentConversation()
                    // Show the overlay first: the caller asked for the real user experience, and
                    // half of that is being able to watch it and hit pause.
                    showOverlay()
                    announceThenRun(task)
                }
            }
            ACTION_CANCEL_TASK -> {
                Log.i(TAG, "Cancel task requested from notification")
                // Stop an in-flight on-device agent loop (maxIterations=50) first.
                agentRunJob?.cancel()
                // Live (companion) runs: cancel ONLY the drive_phone task — the
                // conversation session survives and the model narrates the cancel.
                // resumeMicAfterTask fires via onToolFinished, restoring the overlay.
                val liveTaskCancelled = companionLiveController?.cancelTask() == true
                when {
                    liveTaskCancelled -> Log.i(TAG, "Live drive_phone task cancelled — session kept alive")
                    liveActive() -> {
                        // No task in flight — user is killing the whole Live session.
                        liveCancelCapture()
                    }
                    else -> voiceCaptureController?.sendCancelTask()
                }
                if (!liveTaskCancelled) {
                    restoreOverlay()
                    conversationViewModel.resetToIdle()
                }
            }
            else -> {
                // Default action: show overlay
                showOverlay()
            }
        }

        // Return START_STICKY to restart service if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        
        // Cleanup
        hideOverlay()
        // A window left attached after the service dies leaks and can strand a control the
        // user can no longer act on.
        pausePill.hide()
        statusStrip.setEnabled(false)
        com.aura.aura_ui.services.AgentStatusRegistry.clear()
        // Leaving a hook pointing at a dead service would suspend every future capture on
        // windows that no longer exist.
        com.aura.aura_ui.services.OverlayCaptureGate.hook = null
        cleanupResources()
        
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        serviceScope.cancel()
        instance = null

        Log.i(TAG, "AuraOverlayService destroyed")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory - cleaning up non-essential resources")
        releaseWakeLock()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_RUNNING_LOW -> {
                Log.w(TAG, "Memory pressure (level=$level) - releasing resources")
                releaseWakeLock()
            }
        }
    }

    /**
     * Show the overlay UI
     */
    private fun showOverlay() {
        Log.w(
            "OVL_TRACE",
            "showOverlay() visible=${_isOverlayVisible.value} minimized=${_isMinimized.value} " +
                "attached=${overlayView?.isAttachedToWindow}",
            Throwable("OVL_TRACE caller"),
        )
        // Case 1 — minimized: the window is still attached but the view was animated
        // to GONE (e.g. opening Settings calls temporarilyHide()). A "show" request
        // here means RESTORE, not a no-op. Without this, tapping the Live Alert pill
        // after leaving Settings via Home (which never calls restore()) silently does
        // nothing — the user sees the pill but the overlay never reappears.
        if (_isMinimized.value) {
            Log.i(TAG, "showOverlay: overlay was minimized — restoring instead of no-op")
            restoreOverlay()
            return
        }

        // Case 2 — genuinely visible: trust the ACTUAL view, not just the flag. A
        // stale `true` with a null/detached view is a desync; fall through and
        // recreate rather than early-returning into an invisible overlay.
        if (_isOverlayVisible.value && overlayView?.isAttachedToWindow == true) {
            Log.d(TAG, "Overlay already visible")
            return
        }
        if (_isOverlayVisible.value) {
            Log.w(TAG, "showOverlay: flag was visible but view is null/detached — recreating")
            // Light reset only — do NOT call hideOverlay() here: it ends with
            // stopSelf(), which would tear down the service as we rebuild below.
            overlayView?.let { v -> runCatching { windowManager?.removeView(v) } }
            overlayView = null
            _isOverlayVisible.value = false
        }

        if (!canDrawOverlays(this)) {
            Log.e(TAG, "Cannot show overlay - permission not granted")
            stopSelf()
            return
        }

        try {
            // Move lifecycle to STARTED before creating the view
            lifecycleRegistry.currentState = Lifecycle.State.STARTED

            // Create and configure the overlay view
            overlayView = createOverlayView()

            // Create window params for overlay
            val params = createWindowParams()

            // Add view to window manager.
            //
            // A successful addView() does NOT mean the window is on screen. On
            // MIUI/HyperOS, ColorOS, Funtouch and EMUI a second, private vendor
            // permission ("Display pop-up windows while running in background")
            // can suppress the window with no exception and no log — the
            // long-standing "AURA's bubble never shows on my Redmi" report.
            // The only way to tell is to watch for a first draw; see
            // OverlayVisibilityProbe.
            var addViewThrew = false
            try {
                windowManager?.addView(overlayView, params)
                // The window is created NOT_TOUCHABLE (see createWindowParams). Opt in
                // here so the chat/mic UI still receives taps — but via the same guard
                // as everyone else, so a show() that races a running agent cannot
                // reinstate the screen-wide touch trap.
                setOverlayTouchable(true)
                // Rim glow for the open panel. Same window, same code as the automation
                // glow — the panel used to draw its own Compose copy inside itself, which
                // could only hug AURA's own window and read wrong once that window
                // stopped covering the screen.
                showEdgeGlow()
            } catch (e: Exception) {
                addViewThrew = true
                Log.e(TAG, "addView rejected by WindowManager", e)
            }
            // OVL_TRACE: a window can be attached, drawn and still show nothing if
            // the content measured to zero. Report the real post-layout geometry.
            overlayView?.post {
                val v = overlayView
                Log.w(
                    "OVL_TRACE",
                    "post-layout: size=${v?.width}x${v?.height} vis=${v?.visibility} " +
                        "alpha=${v?.alpha} transY=${v?.translationY} " +
                        "attached=${v?.isAttachedToWindow} " +
                        "density=${resources.displayMetrics.density} " +
                        "dpi=${resources.displayMetrics.densityDpi} " +
                        "realPx=${com.aura.aura_ui.accessibility.ScreenGeometry.realSizePx(this)}",
                )
            }

            visibilityProbe?.cancel()
            visibilityProbe = OverlayVisibilityProbe(this).also { probe ->
                probe.watch(overlayView, addViewThrew) { verdict ->
                    Log.w("OVL_TRACE", "probe verdict=$verdict")
                    if (verdict.isSuccess) {
                        OverlayRecoveryNotifier.clear(this)
                    } else {
                        onOverlayInvisible(verdict)
                    }
                }
            }

            // Move lifecycle to RESUMED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED

            // Acquire partial wake lock for voice capture
            acquireWakeLock()

            // Initialize voice capture controller
            initializeVoiceController()

            // Observe MCP device connection state — drives edge glow + Live Alert chip
            startMcpConnectionObserver()

            // Observe HITL questions — restore from the pill so the ask_user card shows.
            startHitlObserver()

            _isOverlayVisible.value = true
            updateNotification(true)

            // Overlay just became visible for real (this path only runs on a fresh
            // create — already-visible/minimized cases early-return above). Fire the
            // open cue haptic.
            com.aura.aura_ui.presentation.utils.HapticUtils.performOverlayChimeHaptic(this)

            Log.i(TAG, "Overlay shown successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
            hideOverlay()
        }
    }
    
    /**
     * Show overlay and immediately start voice capture.
     * Called when wake word is detected.
     *
     * In Gemini Live mode: just show the overlay — do NOT auto-start the session.
     * The Gemini Live session is continuous and the user must explicitly press the
     * mic button to begin. Auto-starting caused the "listening on open" bug.
     *
     * In classic STT mode: auto-start as before (push-to-talk style).
     */
    private fun showOverlayAndStartListening() {
        Log.i(TAG, "Show overlay (wake word triggered)")

        // Show the overlay first
        showOverlay()

        // Only auto-start for classic VoiceCaptureController (STT/TTS mode).
        // Gemini Live is always-on once the user presses mic — never auto-start.
        if (liveActive()) {
            Log.i(TAG, "Gemini Live mode — waiting for user to press mic button")
            return
        }

        serviceScope.launch {
            // Give the voice controller time to initialise and connect
            var attempts = 0
            while (voiceCaptureController == null && attempts < 20) {
                kotlinx.coroutines.delay(100)
                attempts++
            }

            if (voiceCaptureController != null) {
                Log.i(TAG, "Auto-starting VoiceCaptureController after wake word")
                voiceCaptureController?.startCapture()
            } else {
                Log.e(TAG, "No voice controller ready for auto-capture")
            }
        }
    }

    /**
     * Hide the overlay UI
     */
    private fun hideOverlay() {
        // OVL_TRACE: distinguishes "window torn down" from "view set GONE by
        // minimizeOverlay". Both look identical on screen.
        Log.w(
            "OVL_TRACE",
            "hideOverlay() visible=${_isOverlayVisible.value} viewNull=${overlayView == null}",
            Throwable("OVL_TRACE caller"),
        )
        if (!_isOverlayVisible.value && overlayView == null) {
            Log.w("OVL_TRACE", "hideOverlay: EARLY RETURN")
            return
        }
        // The overlay going away is where a conversation ends. Before any teardown can suspend.
        runCatching { episodeWriter.onConversationEnded(conversationViewModel.messages.value) }

        try {
            // Stop the visibility probe FIRST. A deliberate teardown must never
            // let a pending deadline fire and tell a user with a perfectly
            // healthy phone that their manufacturer is blocking AURA.
            visibilityProbe?.cancel()
            visibilityProbe = null

            // Stop MCP connection observer
            mcpConnectionObserverJob?.cancel()
            mcpConnectionObserverJob = null
            isMcpDeviceConnected = false
            hitlObserverJob?.cancel()
            hitlObserverJob = null

            // Remove edge glow if automation was running
            hideEdgeGlow()

            // Remove view from window manager
            overlayView?.let { view ->
                try {
                    windowManager?.removeView(view)
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing overlay view", e)
                }
            }
            overlayView = null

            // Cleanup voice controller
            cleanupVoiceController()

            // Release wake lock
            releaseWakeLock()

            // Move lifecycle back to STARTED
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
            }

            _isOverlayVisible.value = false
            _isMinimized.value = false
            updateNotification(false)

            Log.i(TAG, "Overlay hidden")

            // Stop service when hidden
            stopSelf()

        } catch (e: Exception) {
            Log.e(TAG, "Error hiding overlay", e)
        }
    }
    
    /**
     * Minimize the overlay during automation - hide the UI but keep service running
     * @param showExecutingNotification If true, shows "AURA is working..." notification
     */
    fun minimizeOverlay(showExecutingNotification: Boolean = true) {
        // OVL_TRACE: the overlay "appears then vanishes" symptom is this method
        // firing right after show. Log WHO called it — the stack trace is the
        // only way to tell an automation minimize from temporarilyHide().
        Log.w(
            "OVL_TRACE",
            "minimizeOverlay(showExec=$showExecutingNotification) " +
                "visible=${_isOverlayVisible.value} minimized=${_isMinimized.value}",
            Throwable("OVL_TRACE caller"),
        )
        if (!_isOverlayVisible.value || _isMinimized.value) {
            Log.w("OVL_TRACE", "minimizeOverlay: EARLY RETURN (not visible, or already minimized)")
            return
        }

        // Flag immediately to prevent re-entry before animation completes
        _isMinimized.value = true

        try {
            // Do this before starting the 280ms visual animation. NOT_TOUCH_MODAL only
            // passes through touches landing outside the window; inside its bounds the
            // panel still takes them, and would eat taps at the bottom of the screen
            // while the minimize animation runs or gets interrupted.
            setOverlayTouchable(false)

            // Smooth slide-down + fade-out before hiding the view
            overlayView?.let { view ->
                view.animate()
                    .alpha(0f)
                    .translationY(50f)
                    .setDuration(280)
                    .setInterpolator(AccelerateInterpolator(1.5f))
                    .withEndAction {
                        view.visibility = android.view.View.GONE
                        view.alpha = 1f          // reset for next restore
                        view.translationY = 0f
                        // Edge glow + haptic kick once view is fully gone
                        if (showExecutingNotification) {
                            triggerAutomationStartHaptic()
                            showEdgeGlow()
                        } else if (!isMcpDeviceConnected) {
                            // The glow is now also the open panel's rim, so a plain
                            // minimize has to take it down — otherwise it stays on the
                            // user's screen with no panel and no run behind it.
                            hideEdgeGlow()
                        }
                    }
                    .start()
            } ?: run {
                // No view to animate — go straight
                if (showExecutingNotification) {
                    triggerAutomationStartHaptic()
                    showEdgeGlow()
                } else if (!isMcpDeviceConnected) {
                    hideEdgeGlow()
                }
            }

            if (showExecutingNotification) {
                _taskStartTime = System.currentTimeMillis()
                updateNotificationForAutomation(chipText = buildChipText())
                Log.i(TAG, "Overlay minimized for automation (with notification)")
            } else {
                updateNotification(false)
                Log.i(TAG, "Overlay minimized (no execution notification)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error minimizing overlay", e)
        }
    }
    
    /**
     * Restore the overlay after automation completes.
     * Ensures execution on main thread since it modifies views.
     */
    /**
     * The user said goodbye and the Live model called `end_conversation`: close the assistant.
     *
     * Deliberately NOT the same as [onDismiss]'s teardown. Dismiss cancels whatever is running,
     * because the user pressed stop. Signing off is different — ending the conversation ends the
     * conversation, not their work — so [agentRunJob] is left untouched and a phone task in flight
     * carries on behind the notification pill. Anything else would throw away real work every time
     * someone politely says goodbye.
     *
     * The Live session itself is already torn down by the caller
     * ([com.aura.aura_ui.agent.conversation.CompanionLiveController]) once the farewell has
     * finished playing; this only handles the UI side.
     */
    fun endVoiceConversation() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { endVoiceConversation() }
            return
        }
        Log.i(TAG, "endVoiceConversation — task still running=${agentRunJob?.isActive == true}")
        followUpJob?.cancel()
        if (whisperActive) {
            whisperActive = false
            whisperStt.cancel()
            audioAmplitude.floatValue = 0f
        }
        if (ttsManagerLazy.isInitialized()) ttsManager.stop()
        commAudioMode.exit()
        conversationViewModel.resetToIdle()
        // Release the global input/focus channel before the window goes away.
        deactivateTextInput()
        // Re-arm the wake word so "Hey AURA" brings the assistant back.
        ListeningModeController.getInstance(this).transitionToPassive()
        hide(this)
    }

    fun restoreOverlay() {
        Log.w("OVL_TRACE", "restoreOverlay() minimized=${_isMinimized.value}", Throwable("OVL_TRACE caller"))
        if (!_isMinimized.value) {
            Log.w("OVL_TRACE", "restoreOverlay: EARLY RETURN (was not minimized)")
            return
        }
        
        // Ensure we're on main thread for view operations
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { restoreOverlay() }
            return
        }
        
        try {
            // Cancel live-notification dot animation and reset state
            _dotAnimJob?.cancel()
            _dotAnimJob = null
            _workingVerb = "Working"
            _workingStepCurrent = 0
            _workingStepTotal = 0
            _workingGoal = ""
            _taskStartTime = 0L

            // Edge glow stays: the panel is coming back on screen and the glow is now
            // the panel's rim as well as automation's. It is torn down in hideOverlay().

            // Cancel any in-flight minimize animation first — its withEndAction
            // callback (sets view.visibility = GONE) would fire *after* the restore
            // and re-hide the overlay. This race caused the HITL ask_user card to
            // flash then vanish when the HITL observer restore raced the 280 ms
            // minimize slide-out.
            overlayView?.animate()?.cancel()

            // Smooth fade-in restore
            overlayView?.let { view ->
                setOverlayTouchable(true)
                view.alpha = 0f
                view.translationY = 0f
                view.visibility = android.view.View.VISIBLE
                view.animate()
                    .alpha(1f)
                    .setDuration(260)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            _isMinimized.value = false
            if (isMcpDeviceConnected) {
                updateNotificationForAutomation(
                    statusText = "Device connected — ready for commands",
                    chipText = "\u25CF Connected"
                )
            } else {
                updateNotification(true)
            }

            Log.i(TAG, "Overlay restored after automation")

        } catch (e: Exception) {
            Log.e(TAG, "Error restoring overlay", e)
        }
    }
    
    // ── Live Alert pill animation helpers ──────────────────────────────────────

    /** Map pipeline agent names → short evocative verb for the notch chip text (≤10 chars). */
    private fun agentToShortVerb(agent: String): String = when (agent.trim().uppercase()) {
        "COMMANDER"  -> "Decoding"
        "PLANNER"    -> "Charting"
        "REACTIVE"   -> "Thinking"
        "PERCEIVER"  -> "Seeing"
        "ACTOR"      -> "Tapping"
        "EXECUTOR"   -> "Firing"
        "VERIFIER"   -> "Confirming"
        "RESPONDER"  -> "Crafting"
        "AURA"       -> "Weaving"
        else         -> "Working"
    }

    /**
     * Compose the chip text for the notch pill — just the current verb
     * ("Seeing", "Tapping"). The coarse elapsed suffix ("·4s") was removed by
     * request; the ticking timer still lives in the expanded card's chronometer.
     * Capped defensively so a long verb can't overflow the OxygenOS chip.
     */
    private fun buildChipText(): String = _workingVerb.take(CHIP_MAX_CHARS)

    /**
     * Push a single Live Alert chip update.
     * The animation loop has been removed — the chip updates once per agent/step
     * transition instead of every 350 ms, reducing notification churn.
     * Always dispatched on serviceScope so it is safe to call from any thread/coroutine.
     */
    /**
     * Show the status strip whenever AURA is either working or talking.
     *
     * Two triggers, one decision point: the run controls follow the RUN, but the strip also
     * has to be up during a plain conversation, which is when a barge-in happens and when
     * there is no tool call to drive it. Resolving both here keeps the two collectors from
     * fighting over show/hide.
     */
    private fun renderStatusStrip(runActive: Boolean) {
        val allowed = runActive || _isOverlayVisible.value
        // A pending farewell is always cancelled first: the strip becoming allowed again
        // mid-goodbye must not be torn down a second later by a job from the last one.
        stripFarewell?.cancel()
        stripFarewell = null
        if (allowed) {
            // Only ALLOWS the strip — the strip itself stays off screen until it has a line
            // to show, so opening the chat no longer paints an empty bar across the top.
            statusStrip.setEnabled(true)
            // "Working…" belongs to a RUN and only to a run. Seeded here rather than as the
            // view's default so it can never appear during a plain conversation, where
            // nothing is being automated and the word would simply be untrue.
            if (runActive) com.aura.aura_ui.services.AgentStatusRegistry.seedWorking()
            return
        }
        // Nothing on the glass — go now, and clear on the way OUT rather than the way in, so
        // the strip can never open on the last run's line.
        if (com.aura.aura_ui.services.AgentStatusRegistry.speech.value == null) {
            statusStrip.setEnabled(false)
            com.aura.aura_ui.services.AgentStatusRegistry.clear()
            return
        }
        // A CAPTION IS STILL UP. Disabling here is what cut the last words off.
        //
        // These two calls used to run back to back, and between them is the entire bug the
        // user reported: `setEnabled(false)` detaches the window immediately — no linger, no
        // dwell — and `clear()` then wipes the text that was mid-reveal. The run ending and
        // the caption finishing are different events, and the run reliably wins the race,
        // because the transcript pacer is still catching the text up to audio that stopped a
        // moment ago.
        //
        // So the strip outlives the run by exactly as long as the caption needs. The pacer
        // owns that decision and signals it the one way it already does — publishing a null
        // utterance once its dwell expires — which is what this waits for.
        stripFarewell = serviceScope.launch {
            // Capped, because not every speech path clears itself. The Live pacer does; a
            // plain `AuraTTSManager.speak` publishes once and never retracts, so an
            // uncapped await would leave the strip on screen for the rest of the session.
            kotlinx.coroutines.withTimeoutOrNull(STRIP_FAREWELL_MAX_MS) {
                com.aura.aura_ui.services.AgentStatusRegistry.speech.first { it == null }
            }
            statusStrip.setEnabled(false)
            com.aura.aura_ui.services.AgentStatusRegistry.clear()
        }
    }

    /** In flight while the strip is outliving a finished run to land its last caption. */
    private var stripFarewell: kotlinx.coroutines.Job? = null

    private fun pushLiveUpdate(statusText: String) {
        Log.i(TAG, "pushLiveUpdate: verb='$_workingVerb', status='${statusText.take(60)}'")
        _dotAnimJob?.cancel()
        _dotAnimJob = serviceScope.launch {
            updateNotificationForAutomation(
                statusText = statusText,
                chipText = buildChipText(),
                stepCurrent = _workingStepCurrent,
                stepTotal = _workingStepTotal
            )
        }
    }

    /**
     * Unified Live Alert / Promoted Notification updater.
     *
     * Everything that wants to update the notch pill funnels through here.
     * The [chipText] is what appears in the pill itself (≤15 chars).
     * [stepCurrent]/[stepTotal] drive the progress bar fill inside the pill.
     * [color] lets the completion flash switch to green momentarily.
     */
    private fun updateNotificationForAutomation(
        statusText: String = "Processing your request...",
        chipText: String = "Working...",
        stepCurrent: Int = 0,
        stepTotal: Int = 0,
        color: Int = 0xFFFFFFFF.toInt()  // Monochrome white
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, AuraOverlayService::class.java).apply {
            action = ACTION_CANCEL_TASK
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 99, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val indeterminate = stepTotal <= 0
        val title = if (_workingGoal.isNotEmpty()) "AURA \u2014 $_workingGoal" else "AURA"

        // The overlay's automation pill is live \u2014 the AssistantForegroundService
        // status chip yields (ChipVisibilityPolicy) so the notch shows ONE pill.
        com.aura.aura_ui.services.AutomationPillRegistry.set(true)

        // Expanded card: goal + live status + step progress, with a TICKING
        // chronometer in the header (right of the AURA name/logo) anchored to
        // the task start. Chronometer ticks without notify() churn.
        val stepLine = if (stepTotal > 0) "Step $stepCurrent of $stepTotal" else null
        val expanded = listOfNotNull(
            _workingGoal.takeIf { it.isNotBlank() }?.let { "$it" },
            "$_workingVerb \u2014 $statusText",
            stepLine,
        ).joinToString("\n")

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_WORKING)
            .setContentTitle(title)
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setSubText(stepLine)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setProgress(
                if (indeterminate) 0 else stepTotal,
                if (indeterminate) 0 else stepCurrent,
                indeterminate
            )
            .setColor(color)
            .setContentIntent(openPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // No chronometer: the ticking header timer looked wrong next to the
            // title. Elapsed time lives in the chip text instead ("Tapping·42s",
            // via PillText) — refreshed on every verb/step push.
            .setShowWhen(false)
            .addAction(R.drawable.ic_notification, "Cancel", cancelPendingIntent)

        val promoted = promoteIfSupported(
            builder.build(),
            chipText = chipText,
            max = if (indeterminate) 0 else stepTotal,
            progress = if (indeterminate) 0 else stepCurrent,
            indeterminate = indeterminate
        )
        // Always call startForeground() — notify() alone does not refresh setShortCriticalText
        // on OxygenOS / OnePlus Live Alert chips.
        enterForeground(promoted)
    }
    
    /**
     * Update the Live Alert pill with the current agent pipeline stage.
     *
     * Called from VoiceCaptureController on every `agent_status` WebSocket message.
     * Maps the agent name to a short verb and (re)starts the dot-pulse animation so
     * the notch pill text cycles  "Scanning" → "Scanning·" → "Scanning··" → "Scanning···"
     * at 450 ms intervals, giving it a living, breathing quality with zero extra screen area.
     */
    fun updateLiveNotification(agent: String, output: String) {
        if (_taskStartTime == 0L) _taskStartTime = System.currentTimeMillis()
        // Gemini Live drive_phone reports agent="AURA" with a "running <tool>"
        // stage string — recover the tool and use its real verb, otherwise the
        // pill sits on one verb ("Weaving") for the whole run. Legacy pipeline
        // agents (COMMANDER/PLANNER/…) carry no tool → agent-name verb as before.
        val newVerb = progressToolName(output)?.let { ToolVerbs.verbFor(it) }
            ?: agentToShortVerb(agent)
        if (newVerb != _workingVerb) {
            Log.i(TAG, "Verb changed: '$_workingVerb' → '$newVerb' (agent=$agent)")
            _workingVerb = newVerb
        } else {
            Log.d(TAG, "Verb unchanged: '$_workingVerb' (agent=$agent)")
        }
        pushLiveUpdate("$agent: $output")
    }

    /**
     * Update the Live Alert pill with task step progress.
     *
     * Called from VoiceCaptureController on every `task_progress` WebSocket message.
     * - While running: chip shows "2/5 · Scanning···" and the progress bar fills.
     * - On completion: pill flashes green with "Done ✓" for 2.5 s, then resets.
     */
    fun updateLiveNotificationProgress(goal: String, current: Int, total: Int, isComplete: Boolean, isAborted: Boolean = false) {
        if (isAborted) {
            _dotAnimJob?.cancel()
            _dotAnimJob = null
            // Red flash: "Cancelled" for 2 s then restore overlay and normal notification
            serviceScope.launch {
                updateNotificationForAutomation(
                    statusText = "Task cancelled",
                    chipText = "Cancelled",
                    stepCurrent = current,
                    stepTotal = total.takeIf { it > 0 } ?: 1,
                    color = 0xFFFF5252.toInt()   // Red
                )
                kotlinx.coroutines.delay(2000)
                updateNotification(false)
                // Restore overlay so user can issue a new command
                if (_isMinimized.value) restoreOverlay()
            }
            return
        }
        if (isComplete) {
            _dotAnimJob?.cancel()
            _dotAnimJob = null
            // Green flash: "Done ✓" for 2.5 s then restore the normal notification
            serviceScope.launch {
                updateNotificationForAutomation(
                    statusText = "Task complete!",
                    chipText = "Done \u2713",
                    stepCurrent = total,
                    stepTotal = total,
                    color = 0xFF4CAF50.toInt()   // Material Green
                )
                kotlinx.coroutines.delay(2500)
                updateNotification(false)
            }
            return
        }

        _workingGoal = goal
        _workingStepCurrent = current
        _workingStepTotal = total
        Log.i(TAG, "Progress update: verb='$_workingVerb', step=$current/$total")
        // Push update — chip immediately reflects new step count
        pushLiveUpdate("$_workingVerb: step $current of $total")
    }

    /**
     * On Android 16+ (API 36), build a Live Update notification using direct
     * platform APIs (no reflection). Requires compileSdk=36.
     */
    @Suppress("NewApi")
    private fun promoteIfSupported(
        notification: Notification,
        chipText: String = "AURA",
        max: Int = 0,
        progress: Int = 0,
        indeterminate: Boolean = false
    ): Notification {
        if (Build.VERSION.SDK_INT < 36) return notification

        val nm = getSystemService(NotificationManager::class.java)
        val permGranted = checkSelfPermission("android.permission.POST_PROMOTED_NOTIFICATIONS")
        Log.i(TAG, "POST_PROMOTED_NOTIFICATIONS permission: ${if (permGranted == android.content.pm.PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}")
        Log.i(TAG, "canPostPromotedNotifications(): ${nm.canPostPromotedNotifications()}")

        return try {
            val openIntent = Intent(this, MainActivity::class.java)
            val openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Build ProgressStyle (required to obtain a promoted chip on Android 16+).
            // IMPORTANT: setProgressIndeterminate(true) renders a scrolling animation bar
            // that EXPANDS the Live Alert into a full banner — this causes the expanded
            // notification to appear in PERCEIVER screenshots and block underlying UI.
            // Fix: for indeterminate state, set progress=0 with indeterminate=false so the
            // ProgressStyle is present (enabling promotion) but renders NO visible bar.
            // Only show a real progress fill when we have deterministic step data.
            val progressStyle = Notification.ProgressStyle()
            if (!indeterminate && max > 0) {
                val scaledProgress = ((progress.toFloat() / max) * 100).toInt().coerceIn(0, 100)
                progressStyle.setProgress(scaledProgress)
                Log.i(TAG, "ProgressStyle: determinate progress=$scaledProgress ($progress/$max)")
            } else {
                // No bar — stays compact. The spinner in setShortCriticalText signals activity.
                progressStyle.setProgress(0)
                Log.i(TAG, "ProgressStyle: hidden (indeterminate suppressed to avoid expansion)")
            }
            progressStyle.setStyledByProgress(false)

            // Cancel button for the promoted Live Alert
            val cancelIntentPromoted = Intent(this, AuraOverlayService::class.java).apply {
                action = ACTION_CANCEL_TASK
            }
            val cancelPendingIntentPromoted = PendingIntent.getService(
                this, 99, cancelIntentPromoted,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Build notification with direct API calls
            val builder = Notification.Builder(this, CHANNEL_ID_WORKING)
                .setContentTitle(notification.extras.getString(Notification.EXTRA_TITLE) ?: "AURA")
                .setContentText(notification.extras.getString(Notification.EXTRA_TEXT) ?: "")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                // FLAG_ONLY_ALERT_ONCE: prevents the Live Alert pill from re-expanding
                // on every status update — it stays as the collapsed chip and only
                // expands when the user taps it.
                .setOnlyAlertOnce(true)
                .setColor(0xFFFFFFFF.toInt())  // White — glass-like Live Alert border
                .setContentIntent(openPendingIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFAULT)
                .setStyle(progressStyle)
                // Chip text: setShortCriticalText and setUsesChronometer are mutually
                // exclusive for the status-bar chip (docs say "or").  Chronometer
                // was winning, so the verb never updated.  Use shortCriticalText only
                // to show the agent verb; the elapsed timer still appears in the
                // notification body via the compat builder.
                .setShortCriticalText(chipText)
                .addAction(
                    Notification.Action.Builder(
                        null, "Cancel", cancelPendingIntentPromoted
                    ).build()
                )

            // Set EXTRA_REQUEST_PROMOTED_ONGOING via extras (key from AOSP source)
            val promoExtras = android.os.Bundle()
            promoExtras.putBoolean("android.requestPromotedOngoing", true)
            builder.addExtras(promoExtras)

            val result = builder.build()

            // Diagnostic logging
            val hasPromotable = result.hasPromotableCharacteristics()
            val nflags = result.flags
            val isOngoing = (nflags and Notification.FLAG_ONGOING_EVENT) != 0
            val reqPromoted = result.extras.getBoolean("android.requestPromotedOngoing")
            Log.i(TAG, "hasPromotableCharacteristics(): $hasPromotable")
            Log.i(TAG, "  flags=0x${Integer.toHexString(nflags)}, ongoing=$isOngoing, requestPromoted=$reqPromoted")
            Log.i(TAG, "  title=${result.extras.getString(Notification.EXTRA_TITLE)}")
            Log.i(TAG, "  shortCriticalText='$chipText'")
            Log.i(TAG, "  contentView=${result.contentView}, bigContentView=${result.bigContentView}, headsUpContentView=${result.headsUpContentView}")

            result
        } catch (e: Exception) {
            Log.w(TAG, "Live Update build failed: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            notification
        }
    }

    // ── Edge Glow + Haptic ────────────────────────────────────────────────────

    /**
     * The one rim-glow implementation, extracted to [EdgeGlow] so the assistant panel
     * and automation show the identical effect from identical code.
     */
    private val edgeGlow by lazy { EdgeGlow(this) }

    /**
     * Whether AURA's own lifecycle wants the glow up (panel open / automation running /
     * device connected), tracked separately from whether the *user* allows it. Without
     * this the setting could only take effect at the next show/hide, so toggling it off
     * mid-run would do nothing and toggling it back on would leave the screen bare.
     */
    private var edgeGlowWanted = false

    /** Attach the rim glow (non-interactive, non-obscuring). No-op when already up. */
    private fun showEdgeGlow() {
        edgeGlowWanted = true
        if (ThemeManager.enableEdgeGlow.value) edgeGlow.show()
    }

    /** Detach the rim glow. */
    private fun hideEdgeGlow() {
        edgeGlowWanted = false
        edgeGlow.hide()
    }

    /** Apply Settings → Edge lighting the moment it changes, not at the next show(). */
    private fun observeEdgeGlowSetting() {
        edgeGlowSettingJob?.cancel()
        edgeGlowSettingJob = serviceScope.launch {
            ThemeManager.enableEdgeGlow.collect { enabled ->
                if (enabled && edgeGlowWanted) edgeGlow.show() else if (!enabled) edgeGlow.hide()
            }
        }
    }

    /** True physical display pixel dimensions (includes status bar + gesture nav). */
    private fun realScreenSize(): Pair<Int, Int> =
        com.aura.aura_ui.accessibility.ScreenGeometry.realSizePx(this)

    /**
     * A Context whose `WINDOW_SERVICE` is bound to the real default display, not this
     * bare Service's own base Context.
     *
     * Device evidence (2026-08-26, a Xiaomi/Redmi phone via adb): the overlay window
     * added through `getSystemService(WINDOW_SERVICE)` on the Service context carried
     * `mGlobalScale=1.2272727` and the `COMPATIBLE_WINDOW` private flag in
     * `dumpsys window windows` — Android's legacy screen-compatibility scaling. The
     * window requested `screenW=1080` (correct — matches `wm size`), but the compositor
     * then stretched the rendered surface to ~1325px against a 1080px-wide physical
     * display, and the excess on the right edge was clipped — the panel visibly running
     * off the side of the screen. `MainActivity`'s window, dumped on the SAME device at
     * the SAME moment, showed no such scale: an Activity is inherently associated with a
     * display, a Service is not, and this OEM's WindowManager falls back to the legacy
     * compat path for a display-less context.
     *
     * `createWindowContext` (API 30+) is the API built for exactly this; `createDisplayContext`
     * is the API 26–29 fallback down to this app's minSdk — coarser (no window-type
     * association) but still display-bound, which is the part that matters here.
     */
    private fun windowManagerContext(): Context {
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val displayContext = createDisplayContext(display)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            displayContext
        }
    }

    /**
     * Single crisp haptic double-tap when automation starts.
     * Feels like a satisfying mechanical "click-clack" — snappy, not buzzy.
     */
    private fun triggerAutomationStartHaptic() {
        try {
            val timings    = longArrayOf(0, 18, 55, 28)
            val amplitudes = intArrayOf(0, 220, 0, 130)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic error", e)
        }
    }

    /**
     * Observe [ConnectionManager.state] for the lifetime of the overlay.
     * - Connected  → edge glow on, Live Alert chip "● Connected"
     * - Otherwise  → edge glow off (unless automation is running), normal notification
     */
    private fun startMcpConnectionObserver() {
        mcpConnectionObserverJob?.cancel()
        val cm = try {
            EntryPointAccessors.fromApplication(
                applicationContext, AuraServicesEntryPoint::class.java
            ).connectionManager()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot resolve ConnectionManager: ${e.message}")
            return
        }

        mcpConnectionObserverJob = serviceScope.launch {
            cm.state.collect { state ->
                val wasConnected = isMcpDeviceConnected
                isMcpDeviceConnected = state is ConnectionState.Connected

                if (isMcpDeviceConnected && !wasConnected) {
                    // Just connected — show glow + "● Connected" chip
                    // Debounce: suppress chip expansion within 30 s to avoid animation loop.
                    val now = System.currentTimeMillis()
                    Log.i(TAG, "MCP device connected — activating edge glow")
                    showEdgeGlow()
                    if (!_isMinimized.value && now - lastConnectedChipMs > 30_000L) {
                        lastConnectedChipMs = now
                        updateNotificationForAutomation(
                            statusText = "Device connected — ready for commands",
                            chipText = "\u25CF Connected"
                        )
                    }
                } else if (!isMcpDeviceConnected && wasConnected) {
                    // Just disconnected — remove glow, revert chip
                    Log.i(TAG, "MCP device disconnected — removing edge glow")
                    hideEdgeGlow()
                    if (!_isMinimized.value) {
                        updateNotification(_isOverlayVisible.value)
                    }
                }
            }
        }
    }

    /**
     * Restore the chat from the automation pill whenever the agent poses a HITL
     * question, so the ask_user card (rendered in the overlay) is actually on
     * screen. The card itself lives in the Compose tree and clears when the
     * broker resolves; this observer only handles the minimized→restored jump.
     */
    private fun startHitlObserver() {
        hitlObserverJob?.cancel()
        hitlObserverJob = serviceScope.launch {
            var spokenFor: String? = null
            AskUserBroker.shared.pending.collect { pending ->
                if (pending == null) {
                    spokenFor = null
                    return@collect
                }
                if (_isMinimized.value) {
                    Log.i(TAG, "ask_user pending — restoring overlay from pill")
                    restoreOverlay()
                }
                // Ask out loud as well as on screen. Keyed on the question id so a recomposition
                // or a re-emission cannot make AURA repeat itself mid-sentence.
                if (spokenFor != pending.id) {
                    spokenFor = pending.id
                    speakQuestion(pending)
                }
            }
        }
    }

    /**
     * Say the question aloud, so it can be answered without looking at the phone.
     *
     * ### Why this exists
     *
     * `ask_user` was screen-only. On a device driven by voice — and voice is the default input
     * here — that means the agent silently stops mid-task and waits for someone to notice a card.
     * Worse, the run that most needs a question is the one where the user is watching the *phone
     * do something*, not watching AURA. Task 19 in the 2026-08-26 suite asked "Where would you
     * like to go?" and died waiting.
     *
     * ### Why the options are spoken too
     *
     * A spoken question with unspoken options is unanswerable: the user hears "which contact?"
     * and has no idea the answer is one of four. They are read as a short list, and the closing
     * line tells the user they are not confined to it — which is the whole point of the free-text
     * path and the thing a menu normally takes away.
     *
     * Either lane is enough on its own. Live speaks in its own voice when connected; otherwise
     * TTS reads it. The card is always up regardless, because Live is off by default and speech
     * can be missed, unheard, or spoken into a noisy room.
     */
    private fun speakQuestion(pending: PendingQuestion) {
        val spoken = buildString {
            append(pending.question)
            if (pending.options.isNotEmpty()) {
                append(" You can say ")
                append(
                    pending.options.mapIndexed { i, opt ->
                        if (i == pending.options.lastIndex && pending.options.size > 1) "or $opt" else opt
                    }.joinToString(", "),
                )
                append(", or just tell me something else.")
            }
        }
        Log.i(TAG, "ask_user speaking: \"$spoken\"")
        if (liveActive()) {
            // The Live model says it in its own voice, so the question does not arrive in a
            // different one mid-conversation. The user's reply comes back as a transcript and
            // reaches answerPendingQuestion through the same path as any other utterance.
            liveSpeak(spoken)
        } else {
            respondWithVoice(spoken)
        }
    }

    /**
     * Create the ComposeView for the overlay
     */
    private fun createOverlayView(): ComposeView {
        return ComposeView(this).apply {
            // Set lifecycle and state registry owners
            setViewTreeLifecycleOwner(this@AuraOverlayService)
            setViewTreeSavedStateRegistryOwner(this@AuraOverlayService)

            setContent {
                // fillMaxWidth, NOT fillMaxSize. This Box is the ComposeView's root, so
                // its measured height IS what the window's WRAP_CONTENT height resolves
                // to. fillMaxSize expands to the full incoming constraint — the display
                // height — which silently re-inflates the window to full screen: the
                // panel gets drawn at the TOP of a full-height box (Box aligns TopStart)
                // and the window swallows every touch on the phone again. Both symptoms
                // of exactly that, measured on device 2026-08-17.
                //
                // Use transparent background - critical for true overlay effect
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Transparent)
                ) {
                    AuraUITheme(darkTheme = true) {
                        OverlayContent()
                    }
                }
            }
        }
    }

    /**
     * Compose content for the overlay
     */
    @Composable
    private fun OverlayContent() {
        val conversationState by conversationViewModel.state.collectAsState()
        val conversationMessages by conversationViewModel.messages.collectAsState()
        val agentOutputs by conversationViewModel.agentOutputs.collectAsState()
        val latestAgentOutput by conversationViewModel.latestAgentOutput.collectAsState()
        val taskProgress by conversationViewModel.taskProgress.collectAsState()
        val agentSteps by conversationViewModel.agentSteps.collectAsState()
        val screenCaptureEnabled by ThemeManager.enableScreenCapture.collectAsState()
        // "Sharing" = the pref is on AND a live MediaProjection session is held.
        val isSharingScreen = screenCaptureEnabled &&
            (AuraAccessibilityService.instance?.isMediaProjectionAvailable() == true)

        // DO NOT auto-request screen capture from here.
        //
        // This used to launch ScreenCapturePermissionActivity the instant the
        // overlay composed, to save the user a manual step. It cost the overlay
        // itself: SystemUI's MediaProjection consent is a system permission
        // dialog, and Android hides EVERY non-system overlay window while one is
        // showing (tapjacking protection). So the overlay drew, then vanished
        // ~3 ms later — with nothing in AURA's own logs, because the system did
        // the hiding, not us. Measured on a Redmi (2026-08-06):
        //
        //   13:44:11.530  overlay post-layout 1080x2400 VISIBLE, probe=VISIBLE
        //   13:44:11.533  START ScreenCapturePermissionActivity
        //   13:44:11.661  START MediaProjectionPermissionActivity  → overlay gone
        //
        // It only looked fine on devices where MediaProjection was ALREADY
        // granted, because then this branch never ran — which is exactly why it
        // survived on the primary test device and broke on a fresh install.
        //
        // Screen capture is requested from the app UI instead (MainActivity's
        // onRequestScreenCapture / the Home permission row), where no overlay is
        // on screen to be destroyed.

        // Create overlay state
        val overlayState = VoiceAssistantState(
            isVisible = true,
            isListening = conversationState.conversationState == ConversationPhase.LISTENING,
            isProcessing = conversationState.conversationState == ConversationPhase.THINKING,
            isResponding = conversationState.conversationState == ConversationPhase.RESPONDING,
            partialTranscript = conversationState.partialTranscript,
            messages = conversationMessages,
            serverConnected = conversationState.isServerConnected,
            audioAmplitude = audioAmplitude.floatValue,
            processingContext = conversationState.processingContext,
            suggestedCommands = conversationState.suggestedCommands,
            recentCommands = conversationState.recentCommands,
            agentOutputs = agentOutputs,
            latestAgentOutput = latestAgentOutput,
            taskProgress = taskProgress,
            isGeminiLiveSession = liveSessionActive(),
            inputActive = inputActiveState.value,
            agentSteps = agentSteps,
            resumeOffer = conversationState.resumeOffer,
            isScreenSharing = isSharingScreen,
        )

        // Build 3: when the overlay is at rest (no messages, idle), check for an interrupted run
        // to offer. Keyed so it re-queries after a run finishes and the chat is cleared.
        LaunchedEffect(conversationState.conversationState, conversationMessages.isEmpty()) {
            if (conversationMessages.isEmpty() && conversationState.conversationState == ConversationPhase.IDLE) {
                refreshResumeOffer()
            }
        }

        // Create callbacks
        val overlayCallbacks = VoiceAssistantCallbacks(
            onDismiss = {
                Log.i(TAG, "onDismiss phase=${conversationState.conversationState} whisperActive=$whisperActive")
                // Cancel any ongoing operation and hide
                when (conversationState.conversationState) {
                    ConversationPhase.LISTENING -> {
                        followUpJob?.cancel()
                        if (whisperActive) {
                            whisperActive = false
                            whisperStt.cancel()
                            audioAmplitude.floatValue = 0f
                        }
                        commAudioMode.exit()
                        if (liveActive()) liveCancelCapture() else voiceCaptureController?.cancelCapture()
                        conversationViewModel.resetToIdle()
                    }
                    ConversationPhase.THINKING, ConversationPhase.RESPONDING -> {
                        // Cancel an in-flight on-device agent run, if any, stop any
                        // spoken answer mid-playback, and drop the barge-in capture.
                        followUpJob?.cancel()
                        agentRunJob?.cancel()
                        if (whisperActive) {
                            whisperActive = false
                            whisperStt.cancel()
                            audioAmplitude.floatValue = 0f
                        }
                        if (ttsManagerLazy.isInitialized()) ttsManager.stop()
                        commAudioMode.exit()
                        conversationViewModel.resetToIdle()
                    }
                    else -> { }
                }
                // Restore the non-focusable window so the dismissed/minimized overlay
                // never holds the global input/focus channel.
                deactivateTextInput()
                // Always return to PASSIVE so wake word re-arms after dismiss
                ListeningModeController.getInstance(this@AuraOverlayService).transitionToPassive()
                hide(this@AuraOverlayService)
            },
            onMicClick = {
                val phase = conversationState.conversationState
                Log.i(TAG, "onMicClick phase=$phase whisperActive=$whisperActive")
                when {
                    // In-progress push-to-talk capture → stop + transcribe (manual stop;
                    // voice-activity detection also auto-stops). Works from any phase.
                    whisperActive -> stopWhisperAndRun()
                    // Gemini Live owns the mic when active.
                    liveActive() && phase == ConversationPhase.LISTENING -> liveCancelCapture()
                    liveActive() -> liveStartCapture()
                    // Legacy Android STT mid-listen → stop.
                    phase == ConversationPhase.LISTENING -> voiceCaptureController?.stopCapture()
                    // Otherwise START. Treat IDLE *and* a stale ERROR (from the retired
                    // Python connection) as "ready to listen". On-device Whisper if
                    // configured, else legacy Android STT.
                    else -> {
                        // Cascade (Silero VAD → Whisper → agent) when available,
                        // else legacy Android STT.
                        if (!startCascadeListening()) {
                            voiceCaptureController?.startCapture()
                        }
                    }
                }
            },
            onSettingsClick = {
                // Open main activity for settings
                val intent = Intent(this@AuraOverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("NAVIGATE_TO_SETTINGS", true)
                }
                startActivity(intent)
            },
            onTextSubmit = { text ->
                // HITL first: if the agent is suspended on an ask_user question, a typed line is
                // that question's free-text answer (the card's "Other…" chip routes the user
                // here) — resolve it, don't start a new run. Shared with the spoken path, which
                // reaches the same helper via executeAgent.
                if (!answerPendingQuestion(text)) {
                    conversationViewModel.addRecentCommand(text)
                    // Routing order (Phase 3 slice 1):
                    //  1. Gemini Live session active → the typed text belongs to it.
                    //  2. Provider configured → drive the on-device AuraAgent.
                    //  3. Otherwise → legacy Python /ws/device floor (no regression).
                    when {
                        liveActive() -> liveSendText(text)
                        isAgentConfigured() -> runOnDeviceAgent(text)
                        else -> voiceCaptureController?.sendTextCommand(text)
                    }
                }
            },
            onMessageCopy = { text ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("AURA Message", text)
                clipboard.setPrimaryClip(clip)
            },
            onMessageRetry = { message ->
                when {
                    liveActive() -> liveSendText(message.text)
                    isAgentConfigured() -> runOnDeviceAgent(message.text)
                    else -> voiceCaptureController?.sendTextCommand(message.text)
                }
            },
            onMessageShare = { text ->
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, text)
                    type = "text/plain"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(shareIntent, "Share via").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            },
            onMessageDelete = { messageId ->
                conversationViewModel.deleteMessage(messageId)
            },
            onSuggestionClick = { suggestion ->
                conversationViewModel.addRecentCommand(suggestion)
                when {
                    liveActive() -> liveSendText(suggestion)
                    isAgentConfigured() -> runOnDeviceAgent(suggestion)
                    else -> voiceCaptureController?.sendTextCommand(suggestion)
                }
            },
            onClearChat = {
                runCatching { episodeWriter.onConversationEnded(conversationViewModel.messages.value) }
                conversationViewModel.clearAllMessages()
            },
            onInputActivate = { activateTextInput() },
            onInputDeactivate = { deactivateTextInput() },
            onResumeClick = { handleResumeClick() },
            onResumeDismiss = { dismissResumeOffer() },
            onScreenShareToggle = {
                val sharing = ThemeManager.enableScreenCapture.value &&
                    (AuraAccessibilityService.instance?.isMediaProjectionAvailable() == true)
                if (sharing) {
                    ThemeManager.updateScreenCapture(false)
                } else {
                    ThemeManager.updateScreenCapture(true)
                    if (AuraAccessibilityService.instance?.isMediaProjectionAvailable() != true) {
                        runCatching {
                            startActivity(ScreenCapturePermissionActivity.createIntent(this@AuraOverlayService))
                        }
                    }
                }
            },
            onMessageLike = { msg -> Log.i(TAG, "like ${msg.id}") },
            onMessageDislike = { msg -> Log.i(TAG, "dislike ${msg.id}") },
        )

        VoiceAssistantOverlay(
            state = overlayState,
            callbacks = overlayCallbacks,
        )
    }

    /**
     * The overlay was created but never reached the screen.
     *
     * This is silent by design on the OEMs that cause it — no exception, no
     * system log, `isAttachedToWindow == true`. Without this hook the user sees
     * "I said Hey AURA and nothing happened" and has no way to learn why.
     * Surface it as a tappable prompt that routes to the vendor screen.
     */
    private fun onOverlayInvisible(verdict: OverlayVerdict) {
        Log.e(TAG, "Overlay did not reach the screen: $verdict")
        OverlayRecoveryNotifier.notify(this, verdict)
    }

    /**
     * Create WindowManager.LayoutParams for the overlay
     */
    private fun createWindowParams(): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        // Full display WIDTH so the panel spans edge to edge (MATCH_PARENT is relative to
        // the app window area and leaves inset gaps). HEIGHT is WRAP_CONTENT on purpose.
        //
        // This window used to be the full display height. A screen-sized window has no
        // "outside", so every touch on the phone either hit this window or — once it was
        // made NOT_TOUCHABLE — was still stamped FLAG_WINDOW_IS_OBSCURED for the app
        // underneath, which is what views with setFilterTouchesWhenObscured() reject on.
        //
        // Bottom-anchored WRAP_CONTENT makes the window only as tall as the pill + cards +
        // transcript sheet, so everything above it is genuinely outside this window and
        // reaches the app behind untouched and unobscured. That is what makes the app
        // behind scrollable again while the assistant is open.
        //
        // It also stops this window covering the top and middle of the screen, which is
        // where the "an app is blocking the screen" refusals (Google Calendar's save) were
        // seen. The rim glow — the other full-screen window — is now a trusted
        // TYPE_ACCESSIBILITY_OVERLAY (see [EdgeGlow]) and does not obscure either. Neither
        // is verified against the Calendar repro yet, and the "any enabled a11y service
        // blocks, categorically" explanation is still untested.
        val (screenW, _) = realScreenSize()

        return WindowManager.LayoutParams(
            screenW, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            // FLAG_NOT_FOCUSABLE is critical: without it a screen-sized overlay
            // steals the input/focus channel from anything beneath, so tapping
            // the launcher icon fires the launch Intent but MainActivity never
            // comes to front.
            //
            // FLAG_NOT_TOUCHABLE is the DEFAULT here on purpose (device evidence
            // 2026-08-16). Back when this window was the size of the whole display it
            // swallowed every touch on the phone while touchable — AURA's injected
            // gestures AND the user's real finger — and the app underneath looked
            // frozen. Measured on Swiggy: a window at frame=[0,0][1240,2772] with
            // inputConfig=NOT_FOCUSABLE (no NOT_TOUCHABLE) sat over the screen for
            // ~6s and ~10s mid-run. The window is bottom-anchored WRAP_CONTENT now,
            // so the blast radius is the panel rather than the display, but the pill
            // still sits exactly where the agent taps bottom-of-screen controls —
            // opt-out stays the default.
            //
            // NOT_TOUCH_MODAL passes through touches landing OUTSIDE the window. That
            // is now a real area (everything above the panel) rather than the empty
            // set it was when the window covered the display.
            //
            // Touch is now opt-in via [setOverlayTouchable] for the moments the
            // overlay genuinely needs it (chat, text input, HITL card). Forgetting
            // to opt in leaves a harmless pass-through overlay; forgetting to opt
            // OUT — the old default — bricked the phone's touch input.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0; y = 0
            // ADJUST_NOTHING, not ADJUST_PAN: the content already lifts itself over the
            // keyboard with imePadding() in VoiceAssistantOverlay. On a bottom-anchored
            // WRAP_CONTENT window PAN would move the whole window up as well, and the
            // panel would jump twice the keyboard height.
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            }
        }
    }

    /**
     * Initialize the voice capture controller
     */
    private fun initializeVoiceController() {
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            try {
                // Discover server URL
                serverUrl = discoverServerUrl()

                if (useGeminiLive && companionConfigured()) {
                    // ── BYOK Gemini Live (direct, no backend) ─────────────────────
                    // CompanionLiveController owns ALL voice I/O directly with the user's key;
                    // drive_phone runs in-process through the gate. No server, no gesture pipe.
                    Log.i(TAG, "BYOK Gemini Live — direct to Google, no backend")
                    // Live summarizes its own sessions; a second episode here would duplicate it.
                    episodeWriter.excludeCurrentConversation()
                    companionLiveController = com.aura.aura_ui.agent.conversation.CompanionLiveController(
                        context = this@AuraOverlayService,
                        viewModel = conversationViewModel,
                        scope = serviceScope,
                        onAmplitude = { amplitude -> audioAmplitude.floatValue = amplitude },
                    )
                    val byokConnected = companionLiveController?.connect() ?: false
                    if (!byokConnected) {
                        Log.w(TAG, "BYOK Live connect failed — voice unavailable")
                        companionLiveController = null
                    } else {
                        Log.i(TAG, "BYOK Live connected — direct companion is the voice pipeline")
                        // Fresh session (wake word OR manual open): AURA greets and starts
                        // listening, instead of connecting silently and waiting for a mic press.
                        // initializeVoiceController only runs on a fresh overlay create — a
                        // re-shown/persisted session early-returns before here, so no re-greet;
                        // reconnects go through connect() directly, not this path.
                        companionLiveController?.greet()
                    }
                    return@launch
                }

                // ── Classic STT/TTS mode ──────────────────────────────────────────
                // VoiceCaptureController owns mic + TTS + gesture pipe. This is the only
                // path left once BYOK Live isn't configured — the server-routed Gemini
                // Live mode was retired along with the Python backend it depended on.
                Log.i(TAG, "Classic mode — using Groq STT + Edge-TTS pipeline")

                voiceCaptureController = VoiceCaptureController(
                    context = this@AuraOverlayService,
                    serverUrl = serverUrl,
                    viewModel = conversationViewModel,
                    scope = serviceScope,
                    onAmplitudeUpdate = { amplitude ->
                        audioAmplitude.floatValue = amplitude
                    },
                )
                // When the on-device agent is the brain, the legacy Python WebSocket
                // is retired — connecting only spams setError → phase=ERROR, which
                // breaks the overlay (mic does nothing, waveform vanishes mid-listen).
                // Skip it; voice/text route to the on-device agent instead.
                if (isAgentConfigured() || serverUrl.isBlank()) {
                    Log.i(
                        TAG,
                        "Legacy VCC connect skipped (agent=${isAgentConfigured()}, " +
                            "backendConfigured=${serverUrl.isNotBlank()})",
                    )
                } else {
                    val vccConnected = voiceCaptureController?.connect() ?: false
                    if (!vccConnected) {
                        Log.w(TAG, "VoiceCaptureController: failed to connect (will retry on interaction)")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error initializing voice controller", e)
            }
        }
    }

    /**
     * Cleanup the voice capture controller
     */
    private fun cleanupVoiceController() {
        connectionJob?.cancel()
        connectionJob = null
        companionLiveController?.cleanup()
        companionLiveController = null
        voiceCaptureController?.cleanup()
        voiceCaptureController = null
    }

    /**
     * Resolve the legacy Python backend URL.
     *
     * Order: the user's own setting, then the build-time default (empty unless a
     * developer set `aura.defaultServerUrl` in local.properties). There is
     * deliberately no hardcoded fallback — one used to live here, pointing at a
     * developer's LAN address, which meant every other user's install spent its
     * connection attempts on a host that could never answer.
     *
     * @return the configured URL, or "" when no backend is configured.
     */
    private suspend fun discoverServerUrl(): String {
        val prefs = getSharedPreferences("aura_settings", Context.MODE_PRIVATE)
        val configured = prefs.getString("server_url", null)?.trim().orEmpty()
        return configured.ifEmpty { BuildConfig.DEFAULT_SERVER_URL }
    }

    /**
     * Create notification channel for foreground service
     * 
     * OnePlus Live Alerts Requirements:
     * - Channel importance must be DEFAULT or higher
     * - Notification must be ongoing
     * - App must appear in Live Alerts settings
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Main channel - DEFAULT importance for Live Alert compatibility
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AURA Voice Assistant",
                NotificationManager.IMPORTANCE_DEFAULT  // DEFAULT required for Live Alert visibility
            ).apply {
                description = "AURA voice assistant service - appears in Live Alerts"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // Disable sound but keep importance for Live Alert
                setSound(null, null)
                enableVibration(false)
            }
            
            // High priority channel for active commands / Live Alert pill
            val workingChannel = NotificationChannel(
                CHANNEL_ID_WORKING,
                "AURA Active",
                NotificationManager.IMPORTANCE_HIGH  // HIGH for Live Alert pill appearance
            ).apply {
                description = "Shows in Live Alert pill when AURA is working"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // Disable sound/vibration but keep high importance for Live Alert
                setSound(null, null)
                enableVibration(false)
            }

            // IMPORTANCE_MIN — invisible-by-default placeholder for the idle FGS notification.
            // Without IMPORTANCE_MIN the shade would show "AURA Assistant — Listening • Tap to toggle"
            // even when nothing is happening, cluttering the user's notification list and
            // competing with the AssistantForegroundService Promoted Ongoing chip.
            val silentChannel = NotificationChannel(
                CHANNEL_ID_SILENT,
                "AURA Overlay (silent)",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Invisible placeholder for the floating overlay's foreground-service requirement"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            notificationManager?.createNotificationChannel(workingChannel)
            notificationManager?.createNotificationChannel(silentChannel)
        }
    }

    /**
     * Silent placeholder for the overlay foreground service.
     *
     * Android requires every FGS to post a notification, but we don't want one
     * cluttering the shade — the user's Promoted Ongoing chip
     * (AssistantForegroundService) is already the single authoritative status
     * surface. This notification posts to an IMPORTANCE_MIN channel, has no
     * actions and no content intent, so it never renders as a card or heads-up.
     *
     * Tapping the launcher icon now reaches MainActivity unobstructed because:
     *  1. createWindowParams() sets FLAG_NOT_FOCUSABLE — the overlay no longer
     *     steals the input/focus channel from a freshly-launched activity.
     *  2. There's no toggle/Hide button on this notification because there's
     *     no notification card to tap in the first place.
     *
     * When AURA is actively working, pushLiveUpdate() / promoteIfSupported()
     * replace this placeholder with the Live Alert chip on CHANNEL_ID_WORKING
     * using the same NOTIFICATION_ID, so the swap is atomic.
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID_SILENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    /**
     * Update the notification based on overlay state.
     * isVisible=true  → pin/refresh the quiet DEFAULT-channel foreground notification.
     * isVisible=false → stop any dot animation, demote the foreground service (removes
     *                   the Live Alert / promoted pill), then re-pin a quiet DEFAULT
     *                   notification only if the overlay is still alive.
     */
    private fun updateNotification(isVisible: Boolean) {
        // Either branch means the automation pill is gone — let the status chip return.
        com.aura.aura_ui.services.AutomationPillRegistry.set(false)
        if (isVisible) {
            enterForeground(createNotification())
        } else {
            // Cancel live-update animation loop
            _dotAnimJob?.cancel()
            _dotAnimJob = null
            // Remove the foreground status + its notification (cancels Live Alert pill)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            // Re-pin with a quiet DEFAULT-channel notification only while the overlay
            // is still running (avoids the "no notification for foreground service" crash).
            if (_isOverlayVisible.value) {
                enterForeground(createNotification())
            }
        }
    }

    /**
     * Acquire wake lock for voice capture
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "aura:overlay_wake_lock"
            ).apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
        Log.d(TAG, "Wake lock acquired")
    }

    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "Wake lock released")
            }
        }
    }

    /**
     * Cleanup all resources
     */
    private fun cleanupResources() {
        cleanupVoiceController()
        if (ttsManagerLazy.isInitialized()) ttsManager.release()
        commAudioMode.exit()
        releaseWakeLock()
        overlayView = null
    }
}
