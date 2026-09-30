package com.aura.aura_ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.annotation.SuppressLint
import com.aura.aura_ui.R
import android.app.NotificationManager
import android.content.Context
import android.graphics.Rect
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.content.ComponentName
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.aura.aura_ui.ime.AuraKeyboardService
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.aura.aura_ui.BuildConfig
import com.aura.aura_ui.services.ControlLockDiagnostics
import com.aura.aura_ui.services.OverlayCaptureGate
import com.aura.aura_ui.services.OverlayEvasion
import com.aura.aura_ui.services.ControlLockStore
import com.aura.aura_ui.services.PauseTrigger
import com.aura.aura_ui.services.SelfActionWindow
import com.aura.aura_ui.services.startActivityAsAura
import com.aura.aura_ui.accessibility.gesture.GestureCallback
import com.aura.aura_ui.accessibility.gesture.GestureCommand
import com.aura.aura_ui.accessibility.gesture.GestureError
import com.aura.aura_ui.accessibility.gesture.GestureInjector
import com.aura.aura_ui.accessibility.gesture.GestureOptions
import com.aura.aura_ui.accessibility.gesture.GestureResult
import com.aura.aura_ui.accessibility.gesture.GestureTarget
import com.aura.aura_ui.accessibility.gesture.GestureType
import com.aura.aura_ui.accessibility.gesture.SemanticClicker
import com.aura.aura_ui.accessibility.gesture.SemanticFallbackPolicy
import com.aura.aura_ui.accessibility.gesture.SwipeDirection
import com.aura.aura_ui.data.preferences.ThemeManager
import com.aura.aura_ui.network.ConnectionManager
import com.aura.aura_ui.utils.AgentLogger
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuraAccessibilityService : AccessibilityService() {
    companion object {
        private const val SCREENSHOT_REQUEST_CODE = 1000

        /**
         * Longest a tap waits for the target to repaint before concluding the gesture was
         * ignored. Only a tap that provokes NOTHING pays this in full — the wait exits at
         * the first event. Sized from the slow case, not the fast one: a Settings row that
         * launches an activity had not emitted its first event at 220ms, and calling that
         * a failure would double-fire a semantic click onto a tap that worked.
         */
        /**
         * How long a tap holds down. 50ms is the platform's own example value and is comfortably
         * above the touch-slop timing every toolkit uses to tell a tap from a press.
         */
        private const val TAP_DURATION_MS = 50L

        private const val TAP_REACTION_WINDOW_MS = 600L

        /** Poll granularity for the above. Small enough not to blunt the early exit. */
        private const val TAP_REACTION_POLL_MS = 25L

        /** Diagnostic ring size for [recentMutations] — enough to explain one tap. */
        private const val RECENT_MUTATION_LIMIT = 24

        // Debounce for the Vol Up+Vol Down voice shortcut so a held combo fires once.
        private const val SHORTCUT_COOLDOWN_MS = 1500L

        // How long the soft keyboard stays suppressed after the last automation
        // action before the watchdog restores it (dismissKeyboard's lease).
        private const val KEYBOARD_LEASE_MS = 30_000L

        // typeViaIme: poll for the focused field to rebind to the AURA keyboard after a
        // switchToInputMethod (binding is async). ~2s total (20 × 100ms).
        private const val IME_BIND_POLLS = 20
        private const val IME_BIND_POLL_MS = 100L

        var connectionManager: ConnectionManager? = null

        var BACKEND_URL = BuildConfig.DEFAULT_SERVER_URL

        private var lastRegisteredUrl: String? = null
        private var isRegistering = AtomicBoolean(false)
        
        // StateFlow to track screen capture availability for UI observers
        private val _screenCaptureAvailable = MutableStateFlow(false)
        val screenCaptureAvailable: StateFlow<Boolean> = _screenCaptureAvailable.asStateFlow()
        
        /** Update screen capture status and notify observers */
        fun updateScreenCaptureStatus(available: Boolean) {
            _screenCaptureAvailable.value = available
        }

        var instance: AuraAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null

        /**
         * Pending MediaProjection permission result that arrived before the service was running.
         * Consumed automatically in onServiceConnected().
         */
        var pendingMediaProjectionResultCode: Int? = null
        var pendingMediaProjectionData: Intent? = null

        fun setBackendUrl(url: String) {
            val normalizedUrl = url.trimEnd('/')
            BACKEND_URL = normalizedUrl

            AgentLogger.Auto.i("Backend URL updated", mapOf("url" to BACKEND_URL))

            // Update URL in all components (no automatic UI data send)
            instance?.updateAllBackendUrls(normalizedUrl)

            if (normalizedUrl != lastRegisteredUrl && !isRegistering.get()) {
                if (instance != null) {
                    instance?.registerDeviceWithBackend()
                } else {
                    AgentLogger.Auto.i(
                        "Accessibility service not ready yet; device registration will run on service connect",
                        mapOf("url" to normalizedUrl),
                    )
                }
            }

            // Command polling removed - all commands now use WebSocket
        }
        
        /**
         * Send screen capture permission result to backend.
         * Called by MainActivity when user grants or denies permission.
         */
        fun sendScreenCapturePermissionResult(granted: Boolean, error: String? = null) {
            updateScreenCaptureStatus(granted)
            val message = JSONObject().apply {
                put("type", "screen_capture_permission_result")
                put("granted", granted)
                if (error != null) put("error", error)
                put("timestamp", System.currentTimeMillis())
            }
            val sent = connectionManager?.send(message.toString()) ?: false
            if (sent) {
                AgentLogger.Screen.i("Sent screen_capture_permission_result via WebSocket",
                    mapOf("granted" to granted.toString()))
            } else {
                AgentLogger.Screen.w("screen_capture_permission_result queued (not connected)")
            }
        }
    }

    internal lateinit var screenCaptureManager: ScreenCaptureManager
    internal lateinit var uiTreeExtractor: UITreeExtractor
    private lateinit var backendCommunicator: BackendCommunicator
    // Command polling removed - all commands now use WebSocket

    lateinit var gestureInjector: GestureInjector
        private set

    private var serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var systemControlsHelper: SystemControlsHelper

    private var lastEventTime = 0L
    private val eventDebounceMs = 2000L
    private var isAuraAppActive = false

    // Keep-screen-on overlay — see acquireKeepScreenOn(). A 1×1 invisible
    // TYPE_ACCESSIBILITY_OVERLAY window whose only job is FLAG_KEEP_SCREEN_ON.
    // Owned here so the window dies with the service (no leak past unbind).
    private var keepScreenOnView: View? = null

    // Real-touch watcher — see onServiceConnected(). Owned here so the window dies with
    // the service, same ownership rule as keepScreenOnView above.
    private var touchWatchOverlay: TouchWatchOverlay? = null

    /**
     * Adds the keep-screen-on overlay window (idempotent). Main thread only.
     * Window flags are honored on every Android build — unlike deprecated
     * screen wake locks, which aggressive OEM battery managers cull — so this
     * is the primary keep-awake mechanism for automation runs
     * ([com.aura.aura_ui.services.keepawake.DefaultScreenAwakeMechanism]).
     *
     * @return false if the window could not be added (caller falls back to a
     *   wake lock); never throws.
     */
    fun acquireKeepScreenOn(): Boolean {
        if (keepScreenOnView != null) return true
        return runCatching {
            val view = View(this)
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = 0f
            }
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            keepScreenOnView = view
            AgentLogger.UI.i("Keep-screen-on overlay added for automation run")
            true
        }.getOrElse {
            AgentLogger.UI.w("Keep-screen-on overlay failed: ${it.message}")
            false
        }
    }

    /** Removes the keep-screen-on overlay. Safe without a prior acquire. */
    fun releaseKeepScreenOn() {
        val view = keepScreenOnView ?: return
        keepScreenOnView = null
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
            .onFailure { AgentLogger.UI.w("Keep-screen-on overlay removal failed: ${it.message}") }
        AgentLogger.UI.i("Keep-screen-on overlay removed")
    }

    // Keyboard-suppression lease — see dismissKeyboard(). Handler lives on the
    // main looper; dismiss/renew may be called from MCP/agent worker threads,
    // which is safe (Handler.postDelayed/removeCallbacks are thread-safe).
    private val keyboardWatchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val keyboardRestoreRunnable = Runnable {
        AgentLogger.Auto.i("Keyboard lease expired (${KEYBOARD_LEASE_MS}ms inactivity) — watchdog restoring")
        restoreKeyboard()
    }
    @Volatile private var keyboardSuppressed = false

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        // Keyboard starts in AUTO mode so the user can type normally when not
        // automating.  dismissKeyboard() switches to SHOW_MODE_HIDDEN on demand
        // during automation, and restoreKeyboard() switches back to AUTO when the
        // task finishes.

        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        // Real touch signal (2026-08-05). Accessibility events cannot distinguish a list
        // settling after AURA's own tap from a person flicking the screen — they are the
        // same event — which cost 13 false pauses in one measured session. This window
        // reports actual finger-down via ACTION_OUTSIDE without consuming the touch.
        // Degrades silently: if the window is refused, the CLICKED/LONG_CLICKED event path
        // still works.
        touchWatchOverlay = TouchWatchOverlay(this) { ControlLockStore.shared.onHumanTouch() }
            .also { it.attach() }

        uiTreeExtractor = UITreeExtractor(this)
        screenCaptureManager = ScreenCaptureManager(this, uiTreeExtractor)
        gestureInjector = GestureInjector(this)
        backendCommunicator = BackendCommunicator(this, uiTreeExtractor, serviceScope, BACKEND_URL)
        systemControlsHelper = SystemControlsHelper(this)
        // Command polling removed - all commands now use WebSocket

        AgentLogger.UI.i("AURA Accessibility Service connected")

        // Apply any pending MediaProjection permission that arrived while the service was not yet running
        val pendingCode = pendingMediaProjectionResultCode
        val pendingData = pendingMediaProjectionData
        if (pendingCode != null && pendingData != null) {
            pendingMediaProjectionResultCode = null
            pendingMediaProjectionData = null
            AgentLogger.Screen.i("Applying pending MediaProjection permission on service connect")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val success = screenCaptureManager.initializeMediaProjection(pendingCode, pendingData)
                if (success) {
                    AgentLogger.Screen.i("Pending MediaProjection initialized successfully")
                    updateScreenCaptureStatus(true)
                    sendScreenCapturePermissionResult(granted = true)
                } else {
                    AgentLogger.Screen.e("Pending MediaProjection initialization failed — token may be expired")
                }
            }
        }

        startForegroundService()

        observeScreenCapturePreference()

        observeUiStreamPreference()

        AgentLogger.UI.i("Waiting for MainActivity to configure backend URL...")

        if (BACKEND_URL != lastRegisteredUrl && !isRegistering.get()) {
            AgentLogger.Auto.i(
                "Service connected; performing deferred device registration",
                mapOf("url" to BACKEND_URL),
            )
            registerDeviceWithBackend()
        }
    }

    /**
     * UI-tree stream (2026-08-18) — off by default, user-toggled.
     *
     * Lives here rather than in the MCP layer because THIS class is the thing holding
     * the accessibility connection: a stream sourced anywhere else would be a poll
     * across a transport, which is exactly the cost the stream exists to remove.
     *
     * Deliberately NOT wired into the agent. It publishes a live tree plus an honest
     * idle verdict so that swap can be evaluated; `perceive_screen` is unchanged.
     */
    private fun observeUiStreamPreference() {
        com.aura.aura_ui.uistream.UiStreamStore.hydrate(this)
        serviceScope.launch {
            com.aura.aura_ui.uistream.UiStreamStore.enabled.collect { on ->
                if (on) {
                    com.aura.aura_ui.uistream.UiStreamServer.install(
                        com.aura.aura_ui.uistream.UiStreamServer(
                            readTree = { runCatching { uiTreeExtractor.getUITree() }.getOrNull() },
                        ),
                    )
                    com.aura.aura_ui.uistream.UiStreamServer.shared?.start()
                    AgentLogger.UI.i("UI stream enabled on 127.0.0.1:8127")
                } else {
                    com.aura.aura_ui.uistream.UiStreamServer.shutdown()
                }
            }
        }
    }

    private fun observeScreenCapturePreference() {
        serviceScope.launch {
            var previousValue: Boolean? = null
            ThemeManager.enableScreenCapture.collect { enabled ->
                if (previousValue != null && previousValue != enabled) {
                    if (!enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        AgentLogger.Screen.i("Screen capture disabled by user - cleaning up resources")
                        screenCaptureManager.disableScreenCapture()
                        // Notify backend that screen capture is no longer available
                        sendScreenCapturePermissionResult(granted = false, error = "User disabled screen capture")
                    }
                    AgentLogger.Screen.i("Screen capture preference changed: $enabled")
                }
                previousValue = enabled
            }
        }
    }

    private fun updateAllBackendUrls(url: String) {
        backendCommunicator.updateBackendUrl(url)
        // Command polling removed - all commands now use WebSocket
        AgentLogger.Auto.i("All components updated with new backend URL", mapOf("url" to url))
    }

    private fun startForegroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelId = "aura_accessibility"
                val channelName = "AURA Accessibility"
                val importance = android.app.NotificationManager.IMPORTANCE_LOW

                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val channel = android.app.NotificationChannel(channelId, channelName, importance)
                notificationManager.createNotificationChannel(channel)

                val notification =
                    android.app.Notification.Builder(this, channelId)
                        .setContentTitle("AURA Active")
                        .setContentText("Screen capture enabled")
                        .setSmallIcon(R.drawable.ic_notification)
                        .build()

                startForeground(1, notification)
                AgentLogger.UI.i("Started foreground service for MediaProjection")
            }
        } catch (e: Exception) {
            AgentLogger.UI.e("Failed to start foreground service", e)
        }
    }

    @SuppressLint("SwitchIntDef")
    /** Package name → human label. Window changes are frequent; PackageManager lookups are not free. */
    private val appLabelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Publish "which app is the user in, and what screen" to [com.aura.aura_ui.agent.state.AuraStateStore].
     *
     * Our own windows are reported as null rather than "AURA": the user does not think of the
     * assistant overlay as the app they are in, and naming it would make AURA describe itself.
     * [windowTitle] is the accessibility event's own text, which most apps set to the screen name —
     * when an app sets nothing, the screen stays null instead of being guessed.
     */
    private fun feedForegroundApp(packageName: String, windowTitle: String?) {
        runCatching {
            if (packageName.isBlank() || packageName == this.packageName) {
                com.aura.aura_ui.agent.state.AuraStateStore.onForegroundApp(null, null)
                return@runCatching
            }
            val label = appLabelCache.getOrPut(packageName) {
                runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0),
                    ).toString()
                }.getOrDefault(packageName)
            }
            com.aura.aura_ui.agent.state.AuraStateStore.onForegroundApp(
                label,
                windowTitle?.takeIf { it.isNotBlank() && it != label },
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            // Feed the on-device MCP server's watch_device_events buffer.
            // Non-blocking — bounded queue drops oldest on overflow.
            com.aura.aura_ui.mcp.bridge.AccessibilityEventBuffer.record(it)

            val eventPackageName = it.packageName?.toString() ?: ""
            val wasActive = isAuraAppActive

            // The comparison is against the RUNTIME package (applicationId), never a
            // literal. It used to be a hardcoded list of `com.aura.aura_ui[.debug]` —
            // the Gradle *namespace*, which no running process is ever called — so this
            // check answered "no" unconditionally and AURA treated its own overlay as a
            // human grabbing the phone. See [OwnAppDetector].
            var isAura = OwnAppDetector.isOwnApp(
                eventPackage = eventPackageName,
                activeWindowPackage = null,
                ownPackage = packageName,
            )

            if (!isAura && eventPackageName == OwnAppDetector.SYSTEM_UI) {
                try {
                    val rootNode = uiTreeExtractor.safeGetRootInActiveWindow()

                    if (rootNode != null) {
                        val actualPackageName = rootNode.packageName?.toString() ?: ""
                        isAura = OwnAppDetector.isOwnApp(
                            eventPackage = eventPackageName,
                            activeWindowPackage = actualPackageName,
                            ownPackage = packageName,
                        )
                        if (isAura) {
                            AgentLogger.Auto.d("Event said systemui, but actual window: $actualPackageName - AURA detected")
                        }
                        @Suppress("DEPRECATION")
                        rootNode.recycle()
                    }
                } catch (e: Exception) {
                    AgentLogger.Auto.e("Error checking actual window package", e)
                }
            }

            isAuraAppActive = isAura

            // Evidence that some app other than AURA repainted. Used by the tap path to
            // tell "the gesture landed" from "the OS played it and the target ignored it"
            // — a distinction dispatchGesture's own callback cannot make. See
            // [SemanticClicker] for the device measurements behind this.
            //
            // The PACKAGE is recorded with the timestamp, and both are published in one
            // write, because the tap path must not accept just any repaint as proof its
            // tap landed. Ambient traffic — a notification, a background refresh, the
            // status-bar clock — would otherwise mark every tap "reacted" and silently
            // skip the fallback. That failure mode is not hypothetical here: the same
            // ambient stream produced 13 false pauses in one session (see
            // [TouchWatchOverlay]) before it was scoped.
            if (!isAura && eventPackageName.isNotEmpty()) {
                val at = System.currentTimeMillis()
                lastForeignUiMutation = UiMutation(eventPackageName, it.eventType, at)
                lastEventAtByPackage[eventPackageName] = at
                recentMutations.add("$eventPackageName:${it.eventType}@$at")
                while (recentMutations.size > RECENT_MUTATION_LIMIT) recentMutations.poll()
            }

            // Spec 2026-07-31 — control lock. The human always wins: an interaction AURA
            // did not cause hands them the wheel, and every tool call (local agent AND
            // remote MCP client) is refused until they hand it back.
            //
            // Two filters stand between this and a pause storm, and both matter:
            // PauseTrigger rejects ambient events nobody caused (notifications, content
            // refreshes), and SelfActionWindow — inside the store — rejects the echo of
            // AURA's own gestures. Neither alone is sufficient.
            if (ControlLockDiagnostics.enabled) {
                val lock = ControlLockStore.shared
                val last = lock.lastDispatchForDiagnostics
                val now = System.currentTimeMillis()
                ControlLockDiagnostics.record(
                    event = it,
                    fromOwnApp = isAura,
                    isDriving = lock.isDriving,
                    selfCaused = SelfActionWindow.isSelfCaused(now, last),
                    sinceDispatchMs = last?.let { d -> now - d.atMs },
                    lastKind = last?.kind,
                )
            }

            // EVERY event goes to the lock, not just human signals (2026-08-05 device fix).
            // Non-human events are the repaint evidence that AURA's own tap is still
            // echoing, and the store needs them to extend its self-action window. Gating
            // this call on isHumanSignal starved that chain: measured on device, a tap on
            // WhatsApp's new-group button produced 16 CONTENT_CHANGED/WINDOWS_CHANGED
            // effects (largest gap 236ms) that never reached the store, then a SCROLLED at
            // 1359ms that paused the agent on its own button press.
            ControlLockStore.shared.onObservedEvent(
                isHumanSignal = PauseTrigger.isHumanSignal(it.eventType, fromOwnApp = isAura),
            )

            // Command polling removed - all commands now use WebSocket

            if (isAuraAppActive != wasActive) {
                AgentLogger.Auto.d("AURA state: $wasActive -> $isAuraAppActive (eventPkg: $eventPackageName)")
            }

            // Restricted-apps gate. Banking/UPI apps read AccessibilityManager in
            // their own onResume(), so this is a race we can only win by firing
            // EARLY — see RestrictedAppAccessibilityGate's KDoc. Two triggers,
            // earliest wins:
            //
            //  1. A tap on something labelled like a restricted app (its launcher
            //     icon) — fires before the target process has even spawned.
            //  2. The first event of ANY type carrying a restricted package —
            //     strictly earlier than waiting for TYPE_WINDOW_STATE_CHANGED,
            //     which lands about when their check runs, and lost every time.
            if (!isAura) {
                if (it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                    val label = it.text?.joinToString(" ")?.takeIf { t -> t.isNotBlank() }
                        ?: it.contentDescription?.toString()
                    RestrictedAppAccessibilityGate.onNodeClicked(this, label)
                }
                if (eventPackageName.isNotBlank()) {
                    RestrictedAppAccessibilityGate.onPackageSeen(this, eventPackageName)
                }
            }

            if (it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                AgentLogger.Auto.d("Window changed: $eventPackageName, isAuraAppActive: $isAuraAppActive")
                // Feed the shared state BEFORE the isAuraAppActive early-return below: which app the
                // user is looking at is exactly what the conversation plane needs, and it is most
                // interesting when that app is NOT ours.
                feedForegroundApp(eventPackageName, it.text.firstOrNull()?.toString())
            }

            if (!isAuraAppActive) {
                // Silent - don't spam logs when other apps are in foreground
                return
            }

            // UI data sending disabled - only send when explicitly requested via triggerScreenshotCapture()
            // This prevents continuous latency from automatic updates
        }
    }

    // ── Hardware shortcut: Vol Up + Vol Down together → start voice ──────────────
    // Stand-in for the wake word until a keyless engine lands. The power key is
    // system-reserved and never reaches apps; volume keys are the only
    // app-interceptable hardware buttons — and only because the service config sets
    // canRequestFilterKeyEvents + flagRequestFilterKeyEvents. Reuses the same
    // showAndListen() entry point the wake word path uses.
    private var volUpDown = false
    private var volDownDown = false
    private var lastShortcutTriggerMs = 0L

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return super.onKeyEvent(event)
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (code == KeyEvent.KEYCODE_VOLUME_UP) volUpDown = true
                if (code == KeyEvent.KEYCODE_VOLUME_DOWN) volDownDown = true

                if (volUpDown && volDownDown) {
                    val now = System.currentTimeMillis()
                    if (now - lastShortcutTriggerMs > SHORTCUT_COOLDOWN_MS) {
                        lastShortcutTriggerMs = now
                        AgentLogger.UI.i("Volume Up+Down combo → launching AURA voice")
                        try {
                            com.aura.aura_ui.overlay.AuraOverlayService.showAndListen(this, source = "volume_keys")
                        } catch (e: Exception) {
                            AgentLogger.UI.e("Failed to launch voice from volume combo", e)
                        }
                    }
                    // Consume the key that completed the combo (and its held repeats)
                    // so the volume UI/level doesn't move while the combo is held.
                    return true
                }
                // Single volume key → let the system handle normal volume change.
                return super.onKeyEvent(event)
            }
            KeyEvent.ACTION_UP -> {
                if (code == KeyEvent.KEYCODE_VOLUME_UP) volUpDown = false
                if (code == KeyEvent.KEYCODE_VOLUME_DOWN) volDownDown = false
                return super.onKeyEvent(event)
            }
            else -> return super.onKeyEvent(event)
        }
    }

    override fun onInterrupt() {
        AgentLogger.UI.w("Service interrupted")
        restoreKeyboard()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AgentLogger.UI.i("Service unbound")
        // Restore normal keyboard behaviour before the service disconnects
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            softKeyboardController.setShowMode(SHOW_MODE_AUTO)
        }
        releaseKeepScreenOn()
        touchWatchOverlay?.detach()
        touchWatchOverlay = null
        // Accessibility and the UI overlay have independent lifecycles. If the service is
        // disabled outside RestrictedAppAccessibilityGate, do not leave a full-screen overlay
        // alive above the next app.
        com.aura.aura_ui.overlay.AuraOverlayService.hideIfRunning()
        cleanup()
        return super.onUnbind(intent)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun initializeMediaProjection(
        resultCode: Int,
        data: Intent,
    ): Boolean {
        return screenCaptureManager.initializeMediaProjection(resultCode, data)
    }

    fun isMediaProjectionAvailable(): Boolean {
        return screenCaptureManager.isMediaProjectionAvailable()
    }

    fun executeGesture(gestureRequest: GestureRequest) {
        when (gestureRequest.action.lowercase()) {
            "tap", "click" -> {
                val x = gestureRequest.x ?: return
                val y = gestureRequest.y ?: return
                performTap(x, y, gestureRequest.command_id)
            }
            "long_press" -> {
                val x = gestureRequest.x ?: return
                val y = gestureRequest.y ?: return
                performLongPress(x, y, gestureRequest.duration, gestureRequest.command_id)
            }
            "swipe" -> {
                val x1 = gestureRequest.x ?: return
                val y1 = gestureRequest.y ?: return
                val x2 = gestureRequest.x2 ?: return
                val y2 = gestureRequest.y2 ?: return
                performSwipe(x1, y1, x2, y2, gestureRequest.duration, gestureRequest.command_id)
            }
            "scroll_up" -> performScroll("up", gestureRequest.command_id)
            "scroll_down" -> performScroll("down", gestureRequest.command_id)
            "back" -> {
                performBack()
                gestureRequest.command_id?.let { sendGestureAck(it, true, null) }
            }
            "home" -> {
                performHome()
                gestureRequest.command_id?.let { sendGestureAck(it, true, null) }
            }
            "recents", "recent_apps" -> {
                performRecents()
                gestureRequest.command_id?.let { sendGestureAck(it, true, null) }
            }
            "dismiss_keyboard" -> {
                dismissKeyboard()
                gestureRequest.command_id?.let { sendGestureAck(it, true, null) }
            }
            "restore_keyboard" -> {
                restoreKeyboard()
                gestureRequest.command_id?.let { sendGestureAck(it, true, null) }
            }
            "press_enter" -> {
                performEnterAction()
                gestureRequest.command_id?.let { sendGestureAck(it, true, null) }
            }
            "press_search" -> {
                pressKeyEvent(android.view.KeyEvent.KEYCODE_SEARCH)
                gestureRequest.command_id?.let { sendGestureAck(it, true, null) }
            }
            else -> executeSystemAction(gestureRequest.action, gestureRequest.command_id)
        }
    }

    private fun sendGestureAck(commandId: String, success: Boolean, error: String?) {
        val ack = JSONObject().apply {
            put("type", "gesture_ack")
            put("command_id", commandId)
            put("success", success)
            put("error", error ?: "")
            put("timestamp", System.currentTimeMillis())
        }
        val cm = connectionManager
        if (cm != null) {
            cm.send(ack.toString())
            AgentLogger.Auto.d("gesture_ack sent via WebSocket",
                mapOf("command_id" to commandId, "success" to success.toString()))
        } else {
            AgentLogger.Auto.w("gesture_ack dropped: ConnectionManager not set",
                mapOf("command_id" to commandId))
        }
    }

    private fun dispatchGestureCommand(
        gestureType: GestureType,
        target: GestureTarget,
        endTarget: GestureTarget.Coordinates? = null,
        options: GestureOptions = GestureOptions(),
        commandIdForAck: String? = null,
    ) {
        // A run that keeps tapping/swiping keeps the keyboard-suppression lease
        // alive so the IME can't pop back over the screen mid-automation.
        renewKeyboardSuppression()
        val commandId = commandIdForAck ?: "cmd_${UUID.randomUUID()}"
        val command = GestureCommand(
            commandId = commandId,
            gestureType = gestureType,
            target = target,
            endTarget = endTarget,
            options = options,
        )

        flashGestureTarget(target, endTarget)
        gestureInjector.execute(
            command,
            object : GestureCallback {
                override fun onSuccess(cmd: GestureCommand, executionTimeMs: Long) {
                    AgentLogger.Auto.i(
                        "Gesture succeeded",
                        mapOf("commandId" to cmd.commandId, "durationMs" to executionTimeMs),
                    )
                    commandIdForAck?.let { sendGestureAck(it, true, null) }
                }

                override fun onFailure(cmd: GestureCommand, error: GestureError, details: String?) {
                    AgentLogger.Auto.w(
                        "Gesture failed",
                        mapOf("commandId" to cmd.commandId, "error" to error.name, "details" to (details ?: "")),
                    )
                    commandIdForAck?.let { sendGestureAck(it, false, "$error${details?.let { d -> ": $d" } ?: ""}") }
                }

                override fun onCancelled(cmd: GestureCommand) {
                    AgentLogger.Auto.w("Gesture cancelled", mapOf("commandId" to cmd.commandId))
                    commandIdForAck?.let { sendGestureAck(it, false, "Gesture cancelled") }
                }
            },
        )
    }

    /**
     * Await variant of [dispatchGestureCommand]: dispatches the gesture and
     * **suspends until the OS reports the outcome**, returning `true` only on
     * [GestureResult.Success]. The fire-and-forget [dispatchGestureCommand]
     * (used by the WebSocket path with its own ack channel) reports nothing back
     * to in-process callers, so the on-device agent and its logger were blind to
     * whether a tap actually landed. These *Await methods give them the truth.
     */
    private suspend fun dispatchGestureAwait(
        gestureType: GestureType,
        target: GestureTarget,
        endTarget: GestureTarget.Coordinates? = null,
        options: GestureOptions = GestureOptions(),
    ): Boolean {
        val command = GestureCommand(
            commandId = "cmd_${UUID.randomUUID()}",
            gestureType = gestureType,
            target = target,
            endTarget = endTarget,
            options = options,
        )
        flashGestureTarget(target, endTarget)
        // AURA's run controls are a TOUCHABLE window. `dispatchGesture` injects real
        // MotionEvents, so they are delivered to the TOPMOST window at those coordinates —
        // meaning a target sitting under the bottom-centre Pause button would have had its
        // tap swallowed by AURA itself, and the agent would have seen a gesture that
        // "succeeded" while nothing happened. Worse, a tap landing on Cancel would have
        // ended the agent's own run. Taking the controls off the glass for the length of
        // the gesture is what keeps the coordinates the agent resolved meaningful.
        //
        // The status strip is FLAG_NOT_TOUCHABLE and never intercepted anything; it rides
        // along here only so the two windows are never half-hidden.
        //
        // Hiding on EVERY gesture would blink the Pause button out several times a minute
        // for the whole run — including the moment the user is reaching for it — so the
        // hide is narrowed to gestures that can actually reach the controls. Everything
        // this cannot resolve with certainty still hides; see [OverlayEvasion].
        return withAuraOverlaysHiddenIf(
            OverlayEvasion.decide(
                bounds = OverlayCaptureGate.hook?.controlsBounds(),
                points = gesturePoints(target, endTarget),
                padPx = GESTURE_EVASION_PAD_PX,
            ) == OverlayEvasion.Decision.HIDE,
        ) {
            when (val result = gestureInjector.executeAndAwait(command)) {
                is GestureResult.Success -> true
                else -> {
                    AgentLogger.Auto.w(
                        "Gesture did not succeed",
                        mapOf("type" to gestureType.name, "result" to result.toString()),
                    )
                    false
                }
            }
        }
    }

    /**
     * Grown around the controls before testing overlap.
     *
     * A gesture that lands a few pixels outside the button can still be delivered to it
     * once touch slop is applied, and this class only earns its keep if a LEAVE is
     * genuinely safe. 24 px is roughly a finger's slop at typical densities.
     */
    private val GESTURE_EVASION_PAD_PX: Int
        get() = (24 * resources.displayMetrics.density).toInt()

    /**
     * Timestamp of the last accessibility event from a package that is not AURA.
     * Written on the event thread, read by the tap path — [Volatile] rather than
     * synchronized because a stale read costs at worst one redundant semantic click.
     */
    /** A repaint by some package other than AURA: who, what kind, and when. */
    private data class UiMutation(val packageName: String, val eventType: Int, val atMs: Long)

    /**
     * Did the app we tapped emit anything after [dispatchedAtMs]?
     *
     * Three rules were measured on device before settling here:
     *
     *  - **Any event at all** lets ambient traffic (status bar, notifications, background
     *    refreshes) vouch for a tap that did nothing — the same noise that produced 13
     *    false pauses in one session (see [TouchWatchOverlay]).
     *  - **Any `TYPE_WINDOW_STATE_CHANGED`** was worse, not better: an interstitial ad
     *    opening its own window on Swiggy's home satisfied it, so a tap that never
     *    reached the app was reported verified and the fallback was skipped.
     *  - **Target package, single "latest event" slot** under-reported: sampling one
     *    shared field every 25ms let ambient events overwrite the target's before the
     *    poll saw it.
     *
     * Hence per-package timestamps: the question is only ever "did *this* app react",
     * and the answer cannot be clobbered by whatever else the device was doing.
     */
    private fun isReaction(targetPackage: String?, dispatchedAtMs: Long): Boolean {
        // No idea what we tapped — any foreign repaint is the best evidence available.
        val pkg = targetPackage
            ?: return (lastForeignUiMutation?.atMs ?: 0L) > dispatchedAtMs
        return (lastEventAtByPackage[pkg] ?: 0L) > dispatchedAtMs
    }

    /**
     * Last non-AURA repaint. A single immutable value rather than two fields so the tap
     * path can never read a package from one event and a timestamp from another.
     */
    @Volatile
    private var lastForeignUiMutation: UiMutation? = null

    /**
     * Last-event time per foreign package. A single "most recent event" slot loses the
     * one that matters: polling it every 25ms means a target-package event can be
     * overwritten by ambient traffic before the tap path ever samples it, which reads as
     * "the app never reacted". Keyed by package so the question "did the app I tapped
     * emit anything" survives whatever else the device was doing. Bounded by the number
     * of packages emitting events in a session — small, and cleared on unbind.
     */
    private val lastEventAtByPackage = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** DEBUG: package:type of recent foreign events, for diagnosing tap verification. */
    private val recentMutations = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /**
     * Which path satisfied the most recent [tapAwait] — "gesture", "semantic", or
     * "none". Exposed because the two are indistinguishable from the outside (both
     * end in a screen change) yet mean very different things about the target app,
     * and diagnosing a tap failure without it means guessing.
     */
    @Volatile
    var lastTapPath: String = "none"
        private set

    /**
     * Tap, and — unlike the raw gesture path — actually check whether the app reacted.
     *
     * Some apps (measured: Swiggy) discard touches carrying
     * `MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT`, which every `dispatchGesture` event
     * carries. The OS still plays the gesture back and still reports COMPLETED, so the
     * old implementation returned `true` for a tap that did nothing. When no repaint
     * follows, retry through [SemanticClicker], whose `ACTION_CLICK` emits no
     * MotionEvent and therefore cannot be filtered on that flag.
     */
    suspend fun tapAwait(x: Int, y: Int): Boolean {
        // Resolve the node BEFORE tapping: a successful tap often navigates away, and
        // the node we would want for the fallback is gone by the time we know we need it.
        val node = runCatching { SemanticClicker.clickableNodeAt(this, x, y) }.getOrNull()
        // Only THIS app's repaints count as the tap having landed.
        val targetPackage = runCatching {
            rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
        recentMutations.clear()
        val dispatchedAt = System.currentTimeMillis()

        val dispatched = dispatchGestureAwait(
            GestureType.TAP,
            GestureTarget.Coordinates(x.toFloat(), y.toFloat(), normalized = false),
            options = GestureOptions(durationMs = 100L),
        )

        // POLL for a reaction rather than sleeping a fixed slice, and stop at the first
        // sign of one. A fixed 220ms wait was measured wrong in both directions: an
        // in-place repaint answers in ~40ms (so most of the wait was wasted), while a
        // full activity transition in Settings had NOT emitted anything by 220ms — which
        // would have declared a perfectly good tap "ignored" and fired a duplicate
        // semantic click on top of it. Early exit keeps the working path fast; only a
        // genuinely dead tap pays the full budget.
        var mutated = false
        var waited = 0L
        while (waited < TAP_REACTION_WINDOW_MS) {
            if (isReaction(targetPackage, dispatchedAt)) {
                mutated = true
                break
            }
            delay(TAP_REACTION_POLL_MS)
            waited += TAP_REACTION_POLL_MS
        }

        if (!SemanticFallbackPolicy.shouldFallBack(mutated, node != null)) {
            lastTapPath =
                (if (mutated) "gesture" else "gesture-unverified") +
                    "(target=$targetPackage,saw=[${recentMutations.joinToString(",")}])"
            return dispatched
        }

        // Re-stamp the self-action window BEFORE clicking semantically.
        //
        // The control lock tells "AURA touched the screen" from "a human touched the
        // screen" purely by timing against ControlLockStore.noteDispatch(), which
        // GestureDispatcher stamps around each dispatchGesture. This fallback fires up to
        // TAP_REACTION_WINDOW_MS *after* that stamp and produces its own TYPE_VIEW_CLICKED
        // plus a screen change — indistinguishable from a person tapping unless it is
        // stamped too. Without this the lock reads AURA's own fallback as the user taking
        // the phone back and refuses the rest of the run with paused_by_user (observed on
        // device: a Swiggy run stalled at step 2 of 2 for exactly this reason).
        ControlLockStore.shared.noteDispatch(SelfActionWindow.ActionKind.TAP)
        val clicked = node?.let { SemanticClicker.click(it) } ?: false
        // Stamp again on the way out: the click's visible effects (navigation, relayout)
        // land after performAction returns, and the budget has to cover those too.
        if (clicked) ControlLockStore.shared.noteDispatch(SelfActionWindow.ActionKind.TAP)
        lastTapPath = if (clicked) "semantic" else "none"
        AgentLogger.Auto.i(
            "Gesture produced no UI reaction — semantic fallback",
            mapOf(
                "x" to x, "y" to y,
                "gestureDispatched" to dispatched,
                "semanticClick" to clicked,
            ),
        )
        // Report `clicked`, NOT `clicked || dispatched`. Or-ing the dispatch result back
        // in would restore the exact defect this method exists to remove: the gesture
        // "succeeded", nothing on screen moved, and the caller was told it worked. The
        // accepted cost is that a tap which genuinely changes nothing observable now
        // returns false; a caller that re-taps is strictly safer than an agent that
        // reports a step complete on no evidence (see X1–X3 in REVIEW_LOG.md).
        return clicked
    }

    suspend fun longPressAwait(x: Int, y: Int, duration: Long): Boolean =
        dispatchGestureAwait(
            GestureType.LONG_PRESS,
            GestureTarget.Coordinates(x.toFloat(), y.toFloat(), normalized = false),
            options = GestureOptions(durationMs = duration, holdMs = duration),
        )

    suspend fun swipeAwait(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long): Boolean =
        dispatchGestureAwait(
            GestureType.SWIPE,
            GestureTarget.Coordinates(x1.toFloat(), y1.toFloat(), normalized = false),
            endTarget = GestureTarget.Coordinates(x2.toFloat(), y2.toFloat(), normalized = false),
            options = GestureOptions(durationMs = duration),
        )

    suspend fun scrollAwait(direction: String): Boolean {
        val swipeDirection = when (direction.lowercase()) {
            "up" -> SwipeDirection.UP
            "down" -> SwipeDirection.DOWN
            "left" -> SwipeDirection.LEFT
            "right" -> SwipeDirection.RIGHT
            else -> return false
        }
        return dispatchGestureAwait(
            // SCROLL (not SWIPE) so GestureBuilder applies the decelerate-to-stop profile:
            // a directional scroll should settle crisply, not fling past the target and
            // leave inertia that invalidates the next perceive's som_ids.
            GestureType.SCROLL,
            GestureTarget.Direction(swipeDirection, distanceRatio = 0.5f),
            options = GestureOptions(durationMs = 500L),
        )
    }

    /**
     * Visual tap feedback at the exact dispatch point — fires for BOTH gesture
     * paths (WebSocket fire-and-forget and the on-device agent's await path),
     * so som_id-resolved taps flash too. Coordinates targets only; directional
     * scrolls have no meaningful point to mark.
     */
    private fun flashGestureTarget(target: GestureTarget, endTarget: GestureTarget.Coordinates?) {
        val coords = target as? GestureTarget.Coordinates ?: return
        if (coords.normalized) return // 0..1 space — not screen pixels; skip rather than mis-draw
        tapFlash.flash(coords.x, coords.y, endTarget?.takeIf { !it.normalized }?.x, endTarget?.takeIf { !it.normalized }?.y)
    }

    private val tapFlash by lazy { TapFlashOverlay(this) }

    /** Boolean variants of the global-action gestures (the OS call already returns success). */
    fun performHomeResult(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    fun performBackResult(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    fun performRecentsResult(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        } else {
            false
        }

    /**
     * Tap at full-resolution screen coordinates.
     *
     * ### Why this is a single plain stroke and not the builder chain
     *
     * A tap used to run through `GestureBuilder` → `HumanizedMotion`, which jittered both the
     * coordinate and the duration to look less machine-made. That indirection bought nothing an
     * app can actually observe and cost the one thing that matters: the point tapped was never
     * exactly the point resolved from the som_id. A tap now lands where it was told to, for a
     * fixed [TAP_DURATION_MS], the way the platform documents it.
     *
     * The control-lock stamp is kept deliberately. `dispatchGesture` injects at the input layer,
     * so AURA's own taps come back as ordinary touch events — without stamping them as ours, the
     * agent reads its own tap as the user grabbing the wheel and pauses itself mid-run.
     *
     * @return true when the OS accepted the gesture for dispatch. Note this says nothing about the
     *   app reacting: `dispatchGesture` reports success for taps some apps ignore entirely (see
     *   [com.aura.aura_ui.accessibility.gesture.SemanticClicker]).
     */
    fun performTap(
        x: Int,
        y: Int,
        @Suppress("UNUSED_PARAMETER") commandId: String? = null,
    ): Boolean = try {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        // Stamped BEFORE dispatch: the resulting touch events can reach the control lock before
        // dispatchGesture() returns, and an unstamped echo reads as the user taking over.
        ControlLockStore.shared.noteDispatch(SelfActionWindow.ActionKind.TAP)
        val dispatched = dispatchGesture(gesture, null, null)
        AgentLogger.Auto.d("Tap at ($x, $y) dispatched: $dispatched")
        dispatched
    } catch (e: Exception) {
        AgentLogger.Auto.d("Tap at ($x, $y) failed: ${e.message}")
        false
    }

    fun performLongPress(
        x: Int,
        y: Int,
        duration: Long = 1000L,
        commandId: String? = null,
    ) {
        dispatchGestureCommand(
            gestureType = GestureType.LONG_PRESS,
            target = GestureTarget.Coordinates(x.toFloat(), y.toFloat(), normalized = false),
            options = GestureOptions(durationMs = duration, holdMs = duration),
            commandIdForAck = commandId,
        )
    }

    fun performSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        duration: Long,
        commandId: String? = null,
    ) {
        dispatchGestureCommand(
            gestureType = GestureType.SWIPE,
            target = GestureTarget.Coordinates(x1.toFloat(), y1.toFloat(), normalized = false),
            endTarget = GestureTarget.Coordinates(x2.toFloat(), y2.toFloat(), normalized = false),
            options = GestureOptions(durationMs = duration.coerceAtLeast(100L)),
            commandIdForAck = commandId,
        )
    }

    fun performClick(
        x: Int,
        y: Int,
        commandId: String? = null,
    ) {
        performTap(x, y, commandId)
    }

    fun performScroll(direction: String, commandId: String? = null) {
        val swipeDirection = when (direction.lowercase()) {
            "up" -> SwipeDirection.UP
            "down" -> SwipeDirection.DOWN
            "left" -> SwipeDirection.LEFT
            "right" -> SwipeDirection.RIGHT
            else -> null
        }

        swipeDirection?.let {
            dispatchGestureCommand(
                // SCROLL (not SWIPE) → decelerate-to-stop profile in GestureBuilder, so the
                // list settles before the next perceive rather than flinging past the target.
                gestureType = GestureType.SCROLL,
                target = GestureTarget.Direction(it, distanceRatio = 0.5f),
                options = GestureOptions(durationMs = 500L),
                commandIdForAck = commandId,
            )
        }
    }

    fun performBack() {
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun dismissKeyboard() {
        // Suppress the soft keyboard via the official SoftKeyboardController API
        // so it doesn't obscure the screen during automated actions / screenshots.
        // SHOW_MODE_HIDDEN is sticky, device-global state owned by this service,
        // so suppression is a LEASE, not a latch: automation activity renews it
        // (renewKeyboardSuppression), and the watchdog flips back to AUTO after
        // KEYBOARD_LEASE_MS of inactivity. A run that dies without end_session
        // (client killed, transport drop, user stops mid-task) can therefore
        // never leave the user unable to type.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            softKeyboardController.setShowMode(SHOW_MODE_HIDDEN)
            keyboardSuppressed = true
            armKeyboardWatchdog()
            AgentLogger.Auto.d("Keyboard suppressed (SHOW_MODE_HIDDEN); ${KEYBOARD_LEASE_MS}ms lease armed")
        }
    }

    fun restoreKeyboard() {
        // Switch back to AUTO so the user (or the next manual interaction) can
        // bring up the keyboard normally.
        val wasSuppressed = keyboardSuppressed
        keyboardWatchdogHandler.removeCallbacks(keyboardRestoreRunnable)
        keyboardSuppressed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            softKeyboardController.setShowMode(SHOW_MODE_AUTO)
            if (wasSuppressed) AgentLogger.Auto.i("Keyboard restored (SHOW_MODE_AUTO)")
        }
    }

    /**
     * Renew the keyboard-suppression lease while automation is actively driving
     * the screen. No-op when the keyboard is not suppressed, so manual use of
     * the device is never affected.
     */
    fun renewKeyboardSuppression() {
        if (keyboardSuppressed) armKeyboardWatchdog()
    }

    private fun armKeyboardWatchdog() {
        keyboardWatchdogHandler.removeCallbacks(keyboardRestoreRunnable)
        keyboardWatchdogHandler.postDelayed(keyboardRestoreRunnable, KEYBOARD_LEASE_MS)
    }

    /**
     * Inject a key event via the `input` shell utility. Returns whether the command
     * actually exited cleanly (exit 0) — on many non-rooted devices `input` from an app
     * process is restricted, so callers must not treat a call as guaranteed success.
     */
    fun pressKeyEvent(keyCode: Int): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
            val exit = process.waitFor()
            AgentLogger.Auto.d("Key event sent", mapOf("keyCode" to keyCode.toString(), "exit" to exit.toString()))
            exit == 0
        } catch (e: Exception) {
            AgentLogger.Auto.w("pressKeyEvent failed", mapOf("keyCode" to keyCode.toString(), "error" to e.message.orEmpty()))
            false
        }
    }

    /**
     * Best-effort per-character text entry for fields that reject ACTION_SET_TEXT.
     * Types with small, slightly-randomized inter-key delays so the input registers
     * naturally (some editors drop bursts of instant keystrokes). Returns true only if
     * every character was injected cleanly; stops early on the first failure so the
     * caller sees an honest partial-failure rather than a masked success.
     */
    private fun typeCharByChar(text: String): Boolean {
        if (text.isEmpty()) return true
        for (ch in text) {
            val ok = when (ch) {
                ' ' -> pressKeyEvent(android.view.KeyEvent.KEYCODE_SPACE)
                '\n' -> pressKeyEvent(android.view.KeyEvent.KEYCODE_ENTER)
                '\t' -> pressKeyEvent(android.view.KeyEvent.KEYCODE_TAB)
                else -> injectTextChar(ch)
            }
            if (!ok) return false
            try {
                // Bounded, humanlike inter-key pause (no RNG dependency needed here).
                Thread.sleep(30L + (System.nanoTime() % 40))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    /**
     * Inject a single character via `input text`. The character is passed as a discrete
     * process argument (array form), so it is never interpreted by a shell. Returns
     * whether the command exited cleanly (exit 0).
     */
    private fun injectTextChar(ch: Char): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("input", "text", ch.toString()))
            process.waitFor() == 0
        } catch (e: Exception) {
            AgentLogger.Auto.w("injectTextChar failed", mapOf("char" to ch.toString(), "error" to e.message.orEmpty()))
            false
        }
    }

    /** The fully-qualified IME id of AURA's own keyboard (package/service-class). */
    private fun auraImeId(): String =
        ComponentName(this, AuraKeyboardService::class.java).flattenToString()

    /**
     * Whether AURA's keyboard is enabled in system settings. `switchToInputMethod` only
     * works for enabled IMEs, so this gates [typeViaIme] and drives the "enable the AURA
     * keyboard" setup hint surfaced to the agent/user.
     */
    fun isAuraImeEnabled(): Boolean {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val id = auraImeId()
            imm.enabledInputMethodList.any { it.id == id }
        } catch (e: Exception) {
            AgentLogger.Auto.w("isAuraImeEnabled failed", mapOf("error" to e.message.orEmpty()))
            false
        }
    }

    /** Outcome of an IME-channel text injection, so callers can tailor their response. */
    enum class ImeTypeOutcome { SUCCESS, FAILED, NOT_ENABLED, UNSUPPORTED }

    /**
     * Type [text] via AURA's own keyboard (the IME channel) — the ONLY app-level way to put
     * text into apps that expose no editable accessibility node (React Native / Flutter /
     * canvas / games, e.g. Rapido). Switches to the AURA keyboard, commits the text through
     * its InputConnection, and switches straight back to the user's keyboard so a mid-run
     * crash never strands them on AURA's invisible IME.
     *
     * Requires API 30+ (`switchToInputMethod`) and the AURA keyboard enabled in settings.
     * A field must already be focused (the agent taps it first).
     */
    suspend fun typeViaIme(text: String): ImeTypeOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ImeTypeOutcome.UNSUPPORTED
        if (!isAuraImeEnabled()) return ImeTypeOutcome.NOT_ENABLED

        val imeId = auraImeId()
        val previousImeId = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()

        return try {
            val controller = softKeyboardController
            controller.switchToInputMethod(imeId)

            // Binding is async: wait for the focused field to rebind to the AURA keyboard.
            var connected = false
            for (i in 0 until IME_BIND_POLLS) {
                delay(IME_BIND_POLL_MS)
                if (AuraKeyboardService.instance?.hasConnection() == true) {
                    connected = true
                    break
                }
            }

            val ok = connected && (AuraKeyboardService.instance?.injectText(text) ?: false)
            delay(80) // let the commit land before we swap the keyboard back
            AgentLogger.Auto.i(
                "typeViaIme",
                mapOf("connected" to connected.toString(), "ok" to ok.toString()),
            )
            if (ok) ImeTypeOutcome.SUCCESS else ImeTypeOutcome.FAILED
        } catch (e: Exception) {
            AgentLogger.Auto.w("typeViaIme failed", mapOf("error" to e.message.orEmpty()))
            ImeTypeOutcome.FAILED
        } finally {
            // Always hand the user's keyboard back.
            if (previousImeId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { softKeyboardController.switchToInputMethod(previousImeId) }
            }
        }
    }

    /**
     * Smart press_enter: tries ACTION_IME_ENTER on the focused input node first
     * (triggers the IME action like Search/Go/Send), falls back to raw keyevent 66.
     */
    fun performEnterAction(): Boolean {
        try {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                // Strategy 1: FOCUS_INPUT (standard focused text field)
                var focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

                // Strategy 2: Search for focused editable node
                if (focusedNode == null) {
                    focusedNode = findFocusedEditableNode(rootNode)
                }

                // Strategy 3: FOCUS_ACCESSIBILITY
                if (focusedNode == null) {
                    focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    if (focusedNode != null && !focusedNode.isEditable &&
                        focusedNode.className?.toString()?.contains("EditText") != true) {
                        @Suppress("DEPRECATION")
                        focusedNode.recycle()
                        focusedNode = null
                    }
                }

                if (focusedNode != null) {
                    // WebView returns true for ACTION_IME_ENTER but silently ignores it.
                    // Skip IME_ENTER entirely for WebView and fall through to tapKeyboardActionButton.
                    val nodeClass = focusedNode.className?.toString().orEmpty()
                    val isWebView = nodeClass.contains("WebView", ignoreCase = true)
                    val imeResult = if (!isWebView) {
                        focusedNode.performSelfAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
                            SelfActionWindow.ActionKind.TYPE,
                        )
                    } else false
                    if (imeResult) {
                        AgentLogger.Auto.i("press_enter: ACTION_IME_ENTER succeeded on focused node",
                            mapOf("class" to nodeClass))
                        @Suppress("DEPRECATION")
                        focusedNode.recycle()
                        @Suppress("DEPRECATION")
                        rootNode.recycle()
                        return true
                    }
                    AgentLogger.Auto.d("ACTION_IME_ENTER skipped/failed, trying keyboard button",
                        mapOf("class" to nodeClass, "isWebView" to isWebView.toString()))
                    @Suppress("DEPRECATION")
                    focusedNode.recycle()
                } else {
                    AgentLogger.Auto.d("No focused node found for IME_ENTER, falling back to keyevent")
                }
                @Suppress("DEPRECATION")
                rootNode.recycle()
            }
        } catch (e: Exception) {
            AgentLogger.Auto.w("performEnterAction IME strategy failed", mapOf("error" to e.message.orEmpty()))
        }

        // Strategy 2: tap the keyboard window's action button directly (works reliably with Gboard)
        if (tapKeyboardActionButton()) return true

        // Strategy 3: raw keyevent fallback. Report its REAL outcome — on devices where the
        // `input` shell is restricted this genuinely fails, and the agent must see that
        // rather than a masked "success" that makes it believe a form was submitted.
        AgentLogger.Auto.d("press_enter: using raw keyevent fallback")
        val keyOk = pressKeyEvent(android.view.KeyEvent.KEYCODE_ENTER)
        if (!keyOk) {
            AgentLogger.Auto.w("press_enter: all strategies failed (IME_ENTER, action button, keyevent)")
        }
        return keyOk
    }

    /**
     * Find and tap the action button (Search/Go/Done/Send) in the visible IME keyboard window.
     * Works with Gboard because it runs in a separate TYPE_INPUT_METHOD window accessible via
     * the `windows` property (requires flagRetrieveInteractiveWindows in service config).
     */
    private fun tapKeyboardActionButton(): Boolean {
        return try {
            val allWindows = windows ?: return false
            for (window in allWindows) {
                if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val root = window.root ?: continue

                // Try Gboard and AOSP/Samsung keyboard resource IDs
                for (resId in listOf(
                    "com.google.android.inputmethod.latin:id/key_pos_ime_action",
                    "com.android.inputmethod.latin:id/key_pos_ime_action"
                )) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(resId)
                    if (nodes.isNotEmpty()) {
                        val clicked = nodes[0].performSelfAction(AccessibilityNodeInfo.ACTION_CLICK, SelfActionWindow.ActionKind.TAP)
                        val keyName = resId.substringAfterLast("/")
                        AgentLogger.Auto.i(
                            "press_enter: action key tapped $keyName",
                            mapOf("success" to clicked.toString())
                        )
                        nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
                        @Suppress("DEPRECATION") root.recycle()
                        return clicked
                    }
                    nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
                }

                // Generic: any clickable node in the keyboard window with an action label
                for (label in listOf("Search", "Go", "Done", "Send", "Next")) {
                    val nodes = root.findAccessibilityNodeInfosByText(label)
                    val candidate = nodes.firstOrNull { it.isClickable && it.isEnabled }
                    if (candidate != null) {
                        val clicked = candidate.performSelfAction(AccessibilityNodeInfo.ACTION_CLICK, SelfActionWindow.ActionKind.TAP)
                        AgentLogger.Auto.i(
                            "press_enter: action key '$label' tapped",
                            mapOf("success" to clicked.toString())
                        )
                        nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
                        @Suppress("DEPRECATION") root.recycle()
                        return clicked
                    }
                    nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
                }

                AgentLogger.Auto.d("press_enter: no action button found in IME window")
                @Suppress("DEPRECATION") root.recycle()
            }
            false
        } catch (e: Exception) {
            AgentLogger.Auto.w("press_enter: tapKeyboardActionButton failed",
                mapOf("error" to e.message.orEmpty()))
            false
        }
    }

    fun performHome() {
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun performRecents() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
    }

    /**
     * @param x  Optional screen X (px) of the intended field's centre — from coordinator.
     * @param y  Optional screen Y (px) of the intended field's centre — from coordinator.
     *  When provided we do a bounds-hit-test to land on the right editable node and
     *  grant it accessibility-focus only (no ACTION_CLICK → keyboard never opens).
     */
    fun performTextInput(text: String, x: Int = -1, y: Int = -1): Boolean {
        return try {
            val rootNode =
                rootInActiveWindow ?: run {
                    AgentLogger.Auto.w("No active window for text input")
                    return false
                }

            // Strategy 0 (preferred): Coordinator supplied target coordinates.
            // Find the editable node whose bounds contain (x, y) and give it
            // accessibility focus — no tap, no keyboard.
            var focusedNode: AccessibilityNodeInfo? = null
            if (x >= 0 && y >= 0) {
                focusedNode = findEditableNodeAtPoint(rootNode, x, y)
                if (focusedNode != null) {
                    AgentLogger.Auto.d("Strategy 0: found editable node at ($x,$y), granting accessibility focus")
                    focusedNode.performSelfAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, SelfActionWindow.ActionKind.TAP)
                    Thread.sleep(50)
                }
            }

            // Strategy 1: Try system focus finder
            if (focusedNode == null) {
                focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                // WebView nodes don't support ACTION_SET_TEXT - skip them
                if (focusedNode != null && focusedNode.className?.toString()?.contains("WebView") == true) {
                    AgentLogger.Auto.d("Skipping WebView node from FOCUS_INPUT, WebView doesn't support SET_TEXT")
                    @Suppress("DEPRECATION")
                    focusedNode.recycle()
                    focusedNode = null
                }
            }

            // Strategy 2: Look for node with isFocused=true among editable fields
            if (focusedNode == null) {
                AgentLogger.Auto.d("FOCUS_INPUT returned null, searching for focused editable node")
                focusedNode = findFocusedEditableNode(rootNode)
            }
            
            // Strategy 3: Fall back to accessibility focus
            if (focusedNode == null) {
                AgentLogger.Auto.d("No focused editable found, trying FOCUS_ACCESSIBILITY")
                focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                // Only use if it's editable
                if (focusedNode != null && !focusedNode.isEditable && 
                    focusedNode.className?.toString()?.contains("EditText") != true &&
                    focusedNode.className?.toString()?.contains("AutoCompleteTextView") != true) {
                    @Suppress("DEPRECATION")
                    focusedNode.recycle()
                    focusedNode = null
                }
            }
            
            // Strategy 4: Last resort - find any editable (not ideal for multi-field forms)
            if (focusedNode == null) {
                AgentLogger.Auto.w("No focused node found, falling back to first editable - THIS MAY TYPE IN WRONG FIELD")
                focusedNode = findEditableNode(rootNode)

                // Give the node accessibility focus only — ACTION_CLICK would open the keyboard
                if (focusedNode != null) {
                    AgentLogger.Auto.d("Found editable node, granting accessibility focus (no click = no keyboard)")
                    focusedNode.performSelfAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, SelfActionWindow.ActionKind.TAP)
                    Thread.sleep(50)
                }
            }

            if (focusedNode == null) {
                AgentLogger.Auto.w("No focused or editable node found for text input")
                @Suppress("DEPRECATION")
                rootNode.recycle()
                return false
            }
            
            AgentLogger.Auto.d("Using node for text input: class=${focusedNode.className}, text='${focusedNode.text}'")

            val arguments =
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }

            var success = focusedNode.performSelfAction(AccessibilityNodeInfo.ACTION_SET_TEXT, SelfActionWindow.ActionKind.TYPE, arguments)

            // If SET_TEXT failed, try clearing first then setting
            if (!success) {
                AgentLogger.Auto.d("First SET_TEXT attempt failed, trying clear then set")
                val clearArgs = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                focusedNode.performSelfAction(AccessibilityNodeInfo.ACTION_SET_TEXT, SelfActionWindow.ActionKind.TYPE, clearArgs)
                Thread.sleep(50)
                success = focusedNode.performSelfAction(AccessibilityNodeInfo.ACTION_SET_TEXT, SelfActionWindow.ActionKind.TYPE, arguments)
            }

            // Final tier: fields that reject ACTION_SET_TEXT (canvas inputs, some WebView
            // editors, OTP boxes) can still accept synthesized keystrokes. Give the node
            // real input focus (ACTION_CLICK), then type character-by-character via the
            // `input` shell. Best-effort: the shell may be restricted, so we report its
            // real outcome rather than masking failure.
            if (!success) {
                AgentLogger.Auto.i("SET_TEXT rejected, trying per-character keystroke fallback")
                focusedNode.performSelfAction(AccessibilityNodeInfo.ACTION_CLICK, SelfActionWindow.ActionKind.TAP)
                Thread.sleep(80)
                success = typeCharByChar(text)
                if (success) {
                    AgentLogger.Auto.i("Per-character keystroke fallback succeeded")
                } else {
                    AgentLogger.Auto.w("Per-character fallback failed (input shell may be restricted)")
                }
            }

            if (success) {
                AgentLogger.Auto.i("Text input successful", mapOf("text" to text))
                // Some apps show the keyboard as a side-effect of receiving focus/text.
                // Dismiss it immediately so it never obscures the UI for the next action.
                dismissKeyboard()
            } else {
                AgentLogger.Auto.w("ACTION_SET_TEXT failed, node may not support direct text input")
            }

            @Suppress("DEPRECATION")
            focusedNode.recycle()
            @Suppress("DEPRECATION")
            rootNode.recycle()
            success
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error performing text input", e)
            false
        }
    }

    /**
     * Launch [packageName]'s main activity. Returns **true only if the launch was
     * actually issued** — `false` when the package has no launch intent (e.g. a
     * background-only or mistyped package) or `startActivity` throws. Callers rely
     * on this boolean to report success to the agent; a package with no launch
     * intent must NOT look like a successful open, or the agent can't recover.
     */
    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAsAura(intent)
                AgentLogger.Auto.d("Launched app", mapOf("packageName" to packageName))
                true
            } else {
                AgentLogger.Auto.w("No launch intent found for package", mapOf("packageName" to packageName))
                false
            }
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error launching app", e, mapOf("packageName" to packageName))
            false
        }
    }

    fun findNodesByText(text: String) = uiTreeExtractor.findNodesByText(text)

    fun executeSystemAction(action: String, commandId: String? = null) {
        when (action.lowercase()) {
            "control_torch", "control_flashlight", "toggle_flashlight", "flashlight_on", "flashlight_off" -> {
                toggleFlashlight()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "wifi_on" -> {
                toggleWifi(true)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "wifi_off" -> {
                toggleWifi(false)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "toggle_wifi" -> {
                toggleWifi(null)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "bluetooth_on" -> {
                toggleBluetooth(true)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "bluetooth_off" -> {
                toggleBluetooth(false)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "toggle_bluetooth" -> {
                toggleBluetooth(null)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "volume_up" -> {
                adjustVolume(AudioManager.ADJUST_RAISE)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "volume_down" -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "mute" -> {
                adjustVolume(AudioManager.ADJUST_MUTE)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "unmute" -> {
                adjustVolume(AudioManager.ADJUST_UNMUTE)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "brightness_up" -> {
                adjustBrightness(true)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "brightness_down" -> {
                adjustBrightness(false)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            // ===== NEW SYSTEM CONTROLS (Google Assistant/Siri parity) =====
            "dnd_on", "do_not_disturb_on" -> {
                setDoNotDisturb(true)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "dnd_off", "do_not_disturb_off" -> {
                setDoNotDisturb(false)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "toggle_dnd", "toggle_do_not_disturb" -> {
                toggleDoNotDisturb()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "airplane_mode_on" -> {
                openAirplaneModeSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "airplane_mode_off" -> {
                openAirplaneModeSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "rotation_on", "auto_rotate_on" -> {
                setAutoRotate(true)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "rotation_off", "auto_rotate_off" -> {
                setAutoRotate(false)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "toggle_rotation", "toggle_auto_rotate" -> {
                toggleAutoRotate()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "battery_saver_on", "power_saver_on" -> {
                openBatterySaverSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "battery_saver_off", "power_saver_off" -> {
                openBatterySaverSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "dark_mode_on" -> {
                openDisplaySettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "dark_mode_off" -> {
                openDisplaySettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "toggle_dark_mode" -> {
                openDisplaySettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "location_on" -> {
                openLocationSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "location_off" -> {
                openLocationSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "toggle_location" -> {
                openLocationSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "mobile_data_on", "data_on" -> {
                openMobileDataSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "mobile_data_off", "data_off" -> {
                openMobileDataSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "toggle_mobile_data", "toggle_data" -> {
                openMobileDataSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "hotspot_on" -> {
                openHotspotSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "hotspot_off" -> {
                openHotspotSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "toggle_hotspot" -> {
                openHotspotSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "nfc_on" -> {
                openNfcSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "nfc_off" -> {
                openNfcSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "toggle_nfc" -> {
                openNfcSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "open_settings" -> {
                openSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "open_wifi_settings" -> {
                openWifiSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "open_bluetooth_settings" -> {
                openBluetoothSettings()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            // Keyboard control actions
            "dismiss_keyboard" -> {
                dismissKeyboard()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "press_enter" -> {
                performEnterAction()
                commandId?.let { sendGestureAck(it, true, null) }
            }
            "press_search" -> {
                pressKeyEvent(android.view.KeyEvent.KEYCODE_SEARCH)
                commandId?.let { sendGestureAck(it, true, null) }
            }
            else -> {
                AgentLogger.Auto.w("Unknown system action", mapOf("action" to action))
                commandId?.let { sendGestureAck(it, false, "Unknown action: $action") }
            }
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        AccessibilityNodeSearcher.findEditableNode(node)

    private fun findEditableNodeAtPoint(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? =
        AccessibilityNodeSearcher.findEditableNodeAtPoint(node, x, y)

    private fun findFocusedEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        AccessibilityNodeSearcher.findFocusedEditableNode(node)

    private fun toggleWifi(enable: Boolean?) = systemControlsHelper.toggleWifi(enable)
    private fun toggleBluetooth(enable: Boolean?) = systemControlsHelper.toggleBluetooth(enable)
    private fun toggleFlashlight() = systemControlsHelper.toggleFlashlight()
    private fun adjustVolume(direction: Int) = systemControlsHelper.adjustVolume(direction)
    private fun adjustBrightness(increase: Boolean) = systemControlsHelper.adjustBrightness(increase)
    private fun setDoNotDisturb(enable: Boolean) = systemControlsHelper.setDoNotDisturb(enable)
    private fun toggleDoNotDisturb() = systemControlsHelper.toggleDoNotDisturb()
    private fun setAutoRotate(enable: Boolean) = systemControlsHelper.setAutoRotate(enable)
    private fun toggleAutoRotate() = systemControlsHelper.toggleAutoRotate()
    private fun openAirplaneModeSettings() = systemControlsHelper.openAirplaneModeSettings()
    private fun openBatterySaverSettings() = systemControlsHelper.openBatterySaverSettings()
    private fun openDisplaySettings() = systemControlsHelper.openDisplaySettings()
    private fun openLocationSettings() = systemControlsHelper.openLocationSettings()
    private fun openMobileDataSettings() = systemControlsHelper.openMobileDataSettings()
    private fun openHotspotSettings() = systemControlsHelper.openHotspotSettings()
    private fun openNfcSettings() = systemControlsHelper.openNfcSettings()
    private fun openSettings() = systemControlsHelper.openSettings()
    private fun openWifiSettings() = systemControlsHelper.openWifiSettings()
    private fun openBluetoothSettings() = systemControlsHelper.openBluetoothSettings()

    /**
     * Lazily-built accessibility capture path. One instance per service so its throttle
     * bookkeeping (last-capture timestamp) is shared by every caller — two callers with separate
     * instances would each think they were first and trip the framework's 333 ms limit.
     */
    private val accessibilityScreenshotSource by lazy { AccessibilityScreenshotSource(this) }

    /**
     * **The single screen-capture entry point.** Every caller goes through here.
     *
     * Never touches MediaProjection. The mirror ([ScreenCaptureManager]) is disabled — see
     * [AccessibilityCapturePolicy] for why holding a VirtualDisplay for perception was wrong and
     * why scoping it per-task is impossible on targetSdk 36. Below API 30 there is no capture at
     * all and callers degrade to UI-tree-only perception.
     */
    suspend fun captureScreenshot(): AccessibilityShot {
        // `false` is not a lookup — the mirror is disabled, so whether a projection happens to
        // exist is irrelevant to the decision. route() pins this shut; see its KDoc.
        return when (AccessibilityCapturePolicy.route(mediaProjectionAvailable = false)) {
            CaptureRoute.ACCESSIBILITY -> withAuraOverlaysHidden { accessibilityScreenshotSource.capture() }
            CaptureRoute.MEDIA_PROJECTION, CaptureRoute.NONE -> AccessibilityShot.Failed(
                "Screen capture needs Android 11 or newer; reading the UI tree instead",
                retryable = false,
            )
        }
    }

    /**
     * Take AURA's own overlays off the glass for the duration of [block].
     *
     * The status strip and the run controls are real windows over whatever the agent is
     * looking at. Left up, they land in every frame perception sees — and a white pill
     * carrying a Cancel button, present on every screen, is not merely noise: perception
     * would give it a `som_id` and the model could tap it and cancel its own run.
     *
     * **The wait is the load-bearing part.** Setting `visibility` does not reach the
     * compositor synchronously, so capturing straight after would still catch the overlay.
     * Two `Choreographer` frames is the cheap, correct way to say "after the frame that
     * removes them has actually been drawn" — a fixed `delay()` would be a guess that gets
     * it wrong on a slow frame.
     *
     * Restores in a `finally` under [NonCancellable]: a capture cancelled mid-flight must
     * not leave the user's only Pause button invisible.
     */
    /**
     * The display points a gesture will touch — empty when they cannot be known here.
     *
     * Empty is a meaningful answer, not a failure: [OverlayEvasion] reads it as "hide
     * anyway". Resolution for the other target kinds lives inside the injector (normalized
     * coordinates get scaled there; [GestureTarget.UIElement] is found by a tree search),
     * and duplicating that math here to save a blink would be a second implementation of
     * the thing that decides where AURA touches — the last place worth a shortcut.
     */
    private fun gesturePoints(
        target: GestureTarget,
        endTarget: GestureTarget.Coordinates?,
    ): List<Pair<Int, Int>> {
        val start = (target as? GestureTarget.Coordinates)?.takeIf { !it.normalized }
            ?: return emptyList()
        val points = mutableListOf(start.x.toInt() to start.y.toInt())
        endTarget?.let { end ->
            // A swipe whose END is normalized is only half-known, and half-known is unknown.
            if (end.normalized) return emptyList()
            points += end.x.toInt() to end.y.toInt()
        }
        return points
    }

    /** [withAuraOverlaysHidden] when [hide], otherwise straight through. */
    private suspend fun <T> withAuraOverlaysHiddenIf(hide: Boolean, block: suspend () -> T): T =
        if (hide) withAuraOverlaysHidden(block) else block()

    private suspend fun <T> withAuraOverlaysHidden(block: suspend () -> T): T {
        val gate = OverlayCaptureGate.hook ?: return block()

        // The legacy blocking bridge [captureScreen] wraps this in `runBlocking`. Called from
        // the main thread that pins the Looper: the hide would never be dispatched, the frame
        // would never be drawn, and this would hang instead of taking a screenshot. Nothing can
        // be hidden from a thread that is itself blocking the compositor, so the honest
        // behaviour is to capture with the overlays up rather than to deadlock.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AgentLogger.Screen.w("Capture on the main thread — AURA's overlays stay in the frame")
            return block()
        }

        withContext(Dispatchers.Main) {
            gate.hideForCapture()
            awaitFrame()
            awaitFrame()
        }
        return try {
            block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { gate.restoreAfterCapture() }
        }
    }

    /** Resume once the next frame has been drawn. */
    private suspend fun awaitFrame() = suspendCancellableCoroutine<Unit> { cont ->
        Choreographer.getInstance().postFrameCallback { if (cont.isActive) cont.resume(Unit) }
    }

    /**
     * Blocking bridge for the legacy WebSocket callers that cannot suspend. Prefer
     * [captureScreenshot] from coroutine code.
     */
    fun captureScreen(): String? = try {
        when (val shot = kotlinx.coroutines.runBlocking { captureScreenshot() }) {
            is AccessibilityShot.Ok -> {
                AgentLogger.Screen.i("Screenshot captured: ${shot.base64Jpeg.length} chars")
                shot.base64Jpeg
            }
            is AccessibilityShot.Failed -> {
                AgentLogger.Screen.w("Screenshot failed: ${shot.message}")
                null
            }
        }
    } catch (e: Exception) {
        AgentLogger.Screen.e("Error capturing screen", e)
        null
    }


    fun getUITree(): Map<String, Any>? {
        return uiTreeExtractor.getUITree()
    }

    /**
     * Get screen width from ScreenCaptureManager
     */
    fun getScreenWidth(): Int {
        return screenCaptureManager.screenWidth
    }

    /**
     * Get screen height from ScreenCaptureManager
     */
    fun getScreenHeight(): Int {
        return screenCaptureManager.screenHeight
    }

    private fun registerDeviceWithBackend() {
        if (!isRegistering.compareAndSet(false, true)) {
            AgentLogger.Auto.i("Registration already in progress, skipping")
            return
        }

        val targetUrl = BACKEND_URL

        backendCommunicator.registerDevice(
            screenCaptureManager.screenWidth,
            screenCaptureManager.screenHeight,
            resources.displayMetrics.densityDpi,
        ) { success ->
            isRegistering.set(false)
            if (success) {
                lastRegisteredUrl = targetUrl
                AgentLogger.Auto.i("Device registered - UI data will be sent only on explicit request")
                // Periodic updates disabled to prevent latency - use triggerScreenshotCapture() when needed
                // commandPollingManager.startPeriodicUpdates()
            } else {
                AgentLogger.Auto.w(
                    "Device registration failed; will retry on next URL update or service reconnect",
                    mapOf("url" to targetUrl),
                )
            }
        }
    }

    private fun cleanup() {
        gestureInjector.cancelAll()
        // Always restore keyboard show-mode before tearing down — dismissKeyboard()
        // is called inline after every type gesture to suppress the IME during
        // automation. If the session ends without an explicit restore_keyboard
        // command (app killed, WebSocket drop, etc.) the keyboard would stay
        // permanently hidden until the accessibility service is toggled.
        restoreKeyboard()
        // Command polling removed - all commands now use WebSocket
        com.aura.aura_ui.uistream.UiStreamServer.shutdown()
        serviceScope.cancel()
        screenCaptureManager.cleanup()
        backendCommunicator.cleanup()

        AgentLogger.UI.i("Service cleanup completed")
    }
}

/**
 * Perform a node action **and** tell the control lock that AURA is the one doing it.
 *
 * ### Why this exists (2026-08-03)
 *
 * `ControlLockStore.noteDispatch()` had exactly one call site — [GestureDispatcher] — so
 * only *gestures* were attributed to AURA. Everything AURA does through the accessibility
 * node API was unattributed, and therefore read as a human touching the phone:
 *
 * | AURA calls | The device emits | Read as |
 * |---|---|---|
 * | `ACTION_CLICK` | `TYPE_VIEW_CLICKED` | a human tapping |
 * | `ACTION_SET_TEXT` | `TYPE_VIEW_TEXT_CHANGED` | a human typing |
 * | `ACTION_IME_ENTER` | both, plus the screen it submits | a human, twice |
 *
 * That is a bigger hole than the gesture-window bug it was found next to, and it is why
 * admitting `TEXT_CHANGED` to [PauseTrigger] cost so much: node-driven typing — the AURA
 * IME path, the fallback for fields that reject gestures — paused the agent on its own
 * keystrokes.
 *
 * Routing through one wrapper rather than remembering a `noteDispatch()` line at each of
 * nine call sites is the point: the stamp is not something a future call site can forget,
 * because the only way to perform an action is to pass through here.
 */
private fun AccessibilityNodeInfo.performSelfAction(
    action: Int,
    kind: SelfActionWindow.ActionKind,
    arguments: Bundle? = null,
): Boolean {
    ControlLockStore.shared.noteDispatch(kind)
    return performAction(action, arguments)
}
