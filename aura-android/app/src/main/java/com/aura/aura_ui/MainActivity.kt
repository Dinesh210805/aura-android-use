package com.aura.aura_ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.agent.mcpbridge.client.McpOAuthCoordinator
import com.aura.aura_ui.data.preferences.OnboardingPreferences
import com.aura.aura_ui.data.preferences.ThemeManager
import com.aura.aura_ui.mcp.bridge.AppBrowserBridge
import com.aura.aura_ui.presentation.navigation.AuraAppShell
import com.aura.aura_ui.presentation.navigation.AuraRoutes
import com.aura.aura_ui.presentation.screens.*
import com.aura.aura_ui.presentation.screens.onboarding.OnboardingScreen
import com.aura.aura_ui.services.AssistantForegroundService
import com.aura.aura_ui.services.WakeWordListeningService
import com.aura.aura_ui.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val EXTRA_REQUEST_SCREEN_CAPTURE = "REQUEST_SCREEN_CAPTURE"

        /**
         * Bring the app forward on the eval cockpit. Sent by [com.aura.aura_ui.eval.EvalController]
         * when a suite task finishes and the runner is waiting for a verdict — the cockpit is
         * buried behind whatever app the agent was driving, so without this the "score me" state is
         * invisible and the sweep silently stalls.
         */
        const val EXTRA_NAVIGATE_TO_EVAL = "NAVIGATE_TO_EVAL"
        private const val EXTRA_REQUEST_SOURCE = "REQUEST_SOURCE"
        private const val EXTRA_FINISH_AFTER_PERMISSION = "FINISH_AFTER_PERMISSION"
        private const val REQUEST_SOURCE_SETTINGS = "settings"
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            Log.d(TAG, "Permission results: $permissions")
            val allGranted = permissions.values.all { it }
            Log.d(TAG, "All permissions granted: $allGranted")

            if (!allGranted) {
                Log.w(TAG, "Some runtime permissions were denied")
            }
        }

    // Screen capture permission launcher
    private val screenCaptureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val service = AuraAccessibilityService.instance
                if (service != null) {
                    try {
                        val success = service.initializeMediaProjection(result.resultCode, result.data!!)
                        
                        if (success) {
                            Log.d(TAG, "Screen capture permission granted and initialized")
                            
                            // Update StateFlow with actual availability status
                            val isAvailable = service.isMediaProjectionAvailable()
                            AuraAccessibilityService.updateScreenCaptureStatus(isAvailable)
                            
                            // Mark as granted in preferences
                            getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("screen_capture_granted", true)
                                .apply()
                            
                            // Notify backend that permission was granted
                            AuraAccessibilityService.sendScreenCapturePermissionResult(granted = true)
                            
                            android.widget.Toast.makeText(
                                this,
                                "Screen capture enabled - AURA can now capture screenshots",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            Log.e(TAG, "MediaProjection initialization returned false")
                            AuraAccessibilityService.updateScreenCaptureStatus(false)
                            AuraAccessibilityService.sendScreenCapturePermissionResult(
                                granted = false,
                                error = "MediaProjection initialization failed"
                            )
                            android.widget.Toast.makeText(
                                this,
                                "Failed to initialize screen capture",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize media projection", e)
                        AuraAccessibilityService.updateScreenCaptureStatus(false)
                        
                        // Notify backend of failure
                        AuraAccessibilityService.sendScreenCapturePermissionResult(
                            granted = false, 
                            error = "Initialization failed: ${e.message}"
                        )
                        
                        android.widget.Toast.makeText(
                            this,
                            "Failed to enable screen capture: ${e.message}",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                } else {
                    // Service not running yet — stash the result so onServiceConnected() can apply it
                    Log.w(TAG, "Accessibility Service not running — storing permission for deferred init")
                    AuraAccessibilityService.pendingMediaProjectionResultCode = result.resultCode
                    AuraAccessibilityService.pendingMediaProjectionData = result.data
                    android.widget.Toast.makeText(
                        this,
                        "Screen capture permission stored — will activate when service connects",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            } else {
                Log.w(TAG, "Screen capture permission denied - UI-only mode will be used")
                
                // Notify backend that permission was denied
                AuraAccessibilityService.sendScreenCapturePermissionResult(
                    granted = false, 
                    error = "User denied permission"
                )
                
                android.widget.Toast.makeText(
                    this,
                    "Screen capture disabled. AURA will use UI-only mode (no screenshots).",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            
            // If this was a background request from overlay, move to back instead of finishing.
            // finish() destroys the Activity which invalidates the MediaProjection token on
            // Android 14+ — causing the permission to appear "not retained" immediately after
            // the user taps Allow. moveTaskToBack keeps the token alive in the background.
            if (pendingScreenCaptureRequest) {
                pendingScreenCaptureRequest = false
                setMainWindowTouchPassthrough(true)
                moveTaskToBack(true)
            }
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            Log.d(TAG, "Overlay permission result")
            // Check if permission was granted and notify user
            if (Settings.canDrawOverlays(this)) {
                Log.i(TAG, "Overlay permission granted")
                android.widget.Toast.makeText(
                    this,
                    "Overlay permission granted! You can now use the floating assistant.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } else {
                Log.w(TAG, "Overlay permission denied")
                android.widget.Toast.makeText(
                    this,
                    "Overlay permission is required for floating assistant",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }

    // Add job tracking for refresh operations
    private var refreshJob: Job? = null
    
    // Track if we're showing settings (to prevent auto-overlay launch)
    private var showingSettings = false
    
    // Track if we need to show overlay after unlock
    private var pendingOverlayAfterUnlock = false
    private var pendingAutoStartListening = false
    
    // Track if we're only here to request screen capture (finish after result)
    private var pendingScreenCaptureRequest = false

    /**
     * This Activity uses a translucent, full-screen window. While it is being
     * backgrounded, Android can briefly leave that window in the input channel
     * even after another app is visible. Make it explicitly pass-through while
     * paused so it cannot retain a touch sequence from the app underneath.
     */
    private fun setMainWindowTouchPassthrough(enabled: Boolean) {
        val currentlyPassThrough =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        if (currentlyPassThrough == enabled) return

        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
        Log.d(TAG, "MainActivity touch passthrough=$enabled")
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash (Theme.Aura.Splash): mono field + ink badge; must install before super.
        val splashScreen = installSplashScreen()
        
        var keepSplashScreen = true
        splashScreen.setKeepOnScreenCondition { keepSplashScreen }
        
        lifecycleScope.launch {
            delay(750) // 0.75 seconds delay
            keepSplashScreen = false
        }

        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate: Starting AURA UI")
        
        // Initialize ThemeManager with saved preferences
        ThemeManager.initialize(this)
        
        // Initialize onboarding preferences
        OnboardingPreferences.init(this)

        // Check and initialize accessibility service
        checkAccessibilityService()
        
        // Start visual feedback overlay service
        startVisualFeedbackService()

        // Phase 8: ALWAYS start the foreground service when the user opens the
        // app so the on-device MCP server boots and begins listening on 8765 —
        // regardless of keep-alive (which only governs whether it survives the
        // app being closed). Without this, a new install would never start the
        // server until the user manually toggled keep-alive ON, leading to
        // confusing "/mcp connection refused" failures.
        AssistantForegroundService.ensureMcpServerRunning(this)

        // OAuth redirect from a third-party MCP server's sign-in (Sub-project #2). If this launch
        // is the aura://mcp/oauth callback, capture the code and stop — no UI flow to resume.
        if (handleMcpOAuthRedirect(intent)) return

        // Handle wake word unlock request - this activity shows on lock screen
        val unlockAndShowOverlay = intent?.getBooleanExtra("UNLOCK_AND_SHOW_OVERLAY", false) ?: false
        val autoStartListening = intent?.getBooleanExtra("AUTO_START_LISTENING", false) ?: false
        
        if (unlockAndShowOverlay) {
            Log.i(TAG, "Wake word triggered - showing on lock screen for unlock")
            handleWakeWordUnlockRequest(autoStartListening)
            return
        }
        
        // Handle screen capture permission request from overlay/backend
        val requestScreenCapture = intent?.getBooleanExtra(EXTRA_REQUEST_SCREEN_CAPTURE, false) ?: false
        if (requestScreenCapture) {
            Log.i(TAG, "Screen capture permission request from overlay")
            val requestSource = intent?.getStringExtra(EXTRA_REQUEST_SOURCE)
            val finishAfterPermission =
                intent?.getBooleanExtra(EXTRA_FINISH_AFTER_PERMISSION, requestSource != REQUEST_SOURCE_SETTINGS)
                    ?: (requestSource != REQUEST_SOURCE_SETTINGS)
            pendingScreenCaptureRequest = finishAfterPermission
            // Request screen capture - the result callback will finish the activity
            if (checkAccessibilityService()) {
                requestScreenCapturePermission()
            } else {
                Log.w(TAG, "Accessibility service not enabled, cannot request screen capture")
                android.widget.Toast.makeText(
                    this,
                    "Please enable Accessibility Service first",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                finish()
            }
            return
        }

        // Brought forward by the eval cockpit after a task finishes, so the verdict is entered
        // while the evidence is still on screen. Debug-only: the route is not in a release graph,
        // so this would land on a destination that does not exist.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_NAVIGATE_TO_EVAL, false) == true) {
            Log.d(TAG, "Opening the eval cockpit to score the task that just finished")
            showAppShell(startDestination = AuraRoutes.EVAL_SUITE)
            return
        }

        // Check if we should navigate to settings (from overlay settings button)
        val navigateToSettings = intent?.getBooleanExtra("NAVIGATE_TO_SETTINGS", false) ?: false
        
        if (navigateToSettings) {
            Log.d(TAG, "Opening settings screen from overlay")
            showingSettings = true
            showSettingsUI()
            return
        }

        // First launch: onboarding wizard (welcome → disclosure → core permissions).
        if (!OnboardingPreferences.isOnboardingCompleted()) {
            showOnboarding()
            return
        }

        // Home-first entry: request the basic runtime permissions if missing, but ALWAYS
        // land on the Home screen — the overlay opens only from "Start chatting". The
        // Home health banner surfaces anything still broken with a one-tap fix.
        if (!checkPermissions()) {
            Log.d(TAG, "Basic permissions not granted - requesting")
            requestPermissions()
        }
        showAppShell()
    }

    /** First-launch onboarding; hands off to the app shell when completed. */
    private fun showOnboarding() {
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AuraUITheme {
                OnboardingScreen(
                    onDone = {
                        OnboardingPreferences.completeOnboarding()
                        showAppShell()
                    },
                )
            }
        }
    }
    
    /**
     * Handle wake word unlock request when device is locked.
     * Shows activity on lock screen and requests unlock.
     */
    private fun handleWakeWordUnlockRequest(autoStartListening: Boolean) {
        // Set flags to show activity on lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        
        pendingOverlayAfterUnlock = true
        pendingAutoStartListening = autoStartListening
        
        // Request keyguard dismiss
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        Log.i(TAG, "Keyguard dismissed successfully")
                        lifecycleScope.launch {
                            delay(300) // Let unlock animation complete
                            showOverlayAfterUnlock()
                        }
                    }
                    
                    override fun onDismissCancelled() {
                        Log.i(TAG, "Unlock cancelled by user")
                        pendingOverlayAfterUnlock = false
                        pendingAutoStartListening = false
                        finish()
                    }
                    
                    override fun onDismissError() {
                        Log.e(TAG, "Error dismissing keyguard")
                        pendingOverlayAfterUnlock = false
                        pendingAutoStartListening = false
                        finish()
                    }
                }
            )
        } else {
            // For older versions, the FLAG_DISMISS_KEYGUARD should trigger unlock
            // Show a minimal UI indicating unlock is needed
            showUnlockPromptUI()
        }
    }
    
    /**
     * Show overlay after device is unlocked via wake word.
     */
    private fun showOverlayAfterUnlock() {
        if (!pendingOverlayAfterUnlock) return
        
        pendingOverlayAfterUnlock = false
        val autoStart = pendingAutoStartListening
        pendingAutoStartListening = false
        
        Log.i(TAG, "Device unlocked, showing overlay (autoStart=$autoStart)")
        
        if (autoStart) {
            com.aura.aura_ui.overlay.AuraOverlayService.showAndListen(this)
        } else {
            com.aura.aura_ui.overlay.AuraOverlayService.show(this)
        }
        
        // Finish this activity
        finish()
    }
    
    /**
     * Show minimal UI prompting user to unlock (for older Android versions).
     */
    private fun showUnlockPromptUI() {
        setContent {
            AuraUITheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.9f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Unlock to use AURA",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Wake word detected!\nPlease unlock your device to continue.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        
        // Monitor unlock state
        lifecycleScope.launch {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            while (keyguardManager.isKeyguardLocked) {
                delay(500)
            }
            // Device unlocked
            showOverlayAfterUnlock()
        }
    }

    /**
     * Open the chat overlay from an explicit user action ("Start chatting" / a
     * capability card). Home-first UX: this is the ONLY path that shows the overlay;
     * the activity stays alive behind it (no finish()) so returning is instant.
     *
     * [seedCommand] is reserved for pre-filling the chat with a capability-card
     * example; the overlay does not support seeding yet.
     */
    private fun openChatOverlay(seedCommand: String? = null) {
        if (!Settings.canDrawOverlays(this)) {
            fixFirstBrokenPermission()
            return
        }
        Log.i(TAG, "Opening chat overlay (seeded=${seedCommand != null})")
        // Disable this translucent Activity before the task transition. Without
        // this, a touch that starts during the transition can remain targeted
        // at Aura even after the underlying app (for example Swiggy) is shown.
        setMainWindowTouchPassthrough(true)
        com.aura.aura_ui.overlay.AuraOverlayService.show(this)

        // Only start wake word service if user has previously enabled it
        val prefs = getSharedPreferences("aura_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("wake_word_enabled", false)) {
            WakeWordListeningService.start(this)
        }

        // Move the app behind the overlay so it shows over the previous app.
        moveTaskToBack(true)
    }

    /**
     * Route the user to whatever system screen fixes the FIRST broken core
     * permission (accessibility → overlay → microphone) — the Home health
     * banner's one-tap fix.
     */
    private fun fixFirstBrokenPermission() {
        when {
            !checkAccessibilityService() -> {
                Toast.makeText(this, "Enable AURA in Accessibility settings", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open accessibility settings", e)
                }
            }
            !Settings.canDrawOverlays(this) -> {
                overlayPermissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                )
            }
            else -> requestPermissions()
        }
    }

    /**
     * The single-activity app shell (Home · Agent · Activity · Settings) that replaced
     * both the auto-overlay entry and the old hand-rolled settings state machine.
     */
    private fun showAppShell(startDestination: String = AuraRoutes.HOME) {
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AuraUITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AuraAppShell(
                        onStartChat = { seed -> openChatOverlay(seed) },
                        onOpenBrowser = { openAuraBrowser() },
                        onRequestScreenCapture = {
                            pendingScreenCaptureRequest = false
                            requestScreenCapturePermission()
                        },
                        startDestination = startDestination,
                    )
                }
            }
        }
    }

    /** Open AURA's own interactive WebView browser over the home screen. */
    private fun openAuraBrowser() {
        if (!Settings.canDrawOverlays(this)) {
            fixFirstBrokenPermission()
            return
        }
        // The browser is an overlay over the still-visible Home Activity. Unlike the
        // chat path, we do not move this Activity behind another task, so making the
        // Activity FLAG_NOT_TOUCHABLE would leave Home's buttons dead after Browser
        // closes. Clear any stale flag left by a previous task transition instead.
        setMainWindowTouchPassthrough(false)
        AppBrowserBridge.shared(this).showUserBrowser()
    }

    /**
     * Show the app opened on the Settings tab (used when the overlay's settings
     * button launches the activity). The overlay is temporarily hidden while the
     * full app is in front.
     */
    private fun showSettingsUI() {
        com.aura.aura_ui.overlay.AuraOverlayService.temporarilyHide(this)
        showAppShell(startDestination = AuraRoutes.SETTINGS)
    }


    /**
     * If [intent] is the `aura://mcp/oauth` OAuth callback, hand it to [McpOAuthCoordinator] and
     * return true (caller should stop). The coordinator validates `state` against the in-flight
     * PKCE handshake; the code→token exchange + secret persistence are the remaining glue.
     */
    private fun handleMcpOAuthRedirect(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != "aura" || data.host != "mcp" || data.path != "/oauth") return false
        val result = McpOAuthCoordinator.completeRedirect(data.toString())
        Log.i(
            TAG,
            if (result.isSuccess) "MCP OAuth redirect captured for server '${result.getOrNull()?.serverId}'"
            else "MCP OAuth redirect rejected: ${result.exceptionOrNull()?.message}",
        )
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Store the new intent

        // OAuth redirect from a third-party MCP server's sign-in (Sub-project #2). MainActivity is
        // singleTask, so the aura://mcp/oauth callback usually arrives here, not in a fresh onCreate.
        if (handleMcpOAuthRedirect(intent)) return

        // Handle wake word unlock request
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_NAVIGATE_TO_EVAL, false)) {
            // The usual path: the activity is alive but buried behind whatever app the agent was
            // driving, so onCreate never runs again.
            showAppShell(startDestination = AuraRoutes.EVAL_SUITE)
            return
        }
        if (intent.getBooleanExtra("UNLOCK_AND_SHOW_OVERLAY", false)) {
            Log.d(TAG, "Wake word unlock request via onNewIntent")
            val autoStartListening = intent.getBooleanExtra("AUTO_START_LISTENING", false)
            handleWakeWordUnlockRequest(autoStartListening)
            return
        }
        
        // Handle manual screen capture request from Settings
        if (intent.getBooleanExtra(EXTRA_REQUEST_SCREEN_CAPTURE, false)) {
            val requestSource = intent.getStringExtra(EXTRA_REQUEST_SOURCE)
            val finishAfterPermission =
                intent.getBooleanExtra(EXTRA_FINISH_AFTER_PERMISSION, requestSource != REQUEST_SOURCE_SETTINGS)
            Log.d(TAG, "Screen capture request via onNewIntent. source=$requestSource finishAfter=$finishAfterPermission")
            pendingScreenCaptureRequest = finishAfterPermission
            requestScreenCapturePermission()
            return
        }
        // Handle navigate to settings
        if (intent.getBooleanExtra("NAVIGATE_TO_SETTINGS", false)) {
            Log.d(TAG, "Navigate to settings requested via onNewIntent")
            showingSettings = true
            showSettingsUI()
            return
        }

        // Plain relaunch (no recognized extra) — e.g. the user tapped the Live Alert
        // pill. If we were left flagged as "in settings" (user opened Settings then
        // pressed Home instead of Back, so onNavigateBack never cleared the flag),
        // onResume's `if (showingSettings) return` would swallow this and the overlay
        // would never reappear — the reported "pill won't open" bug. Clear the stale
        // flag so onResume re-launches the overlay (AuraOverlayService.showOverlay()
        // then restores it from its minimized state).
        if (showingSettings) {
            Log.d(TAG, "Plain relaunch while flagged in-settings — clearing stale flag to restore overlay")
            showingSettings = false
        }
    }

    override fun onStart() {
        super.onStart()
        // Pill lifecycle: the status chip renders only while the app is on
        // screen (or an MCP client is connected / a flow is live) — see
        // ChipVisibilityPolicy. Swiping the app away flips this false and the
        // pill disappears while the keep-alive service lives on silently.
        com.aura.aura_ui.services.AppVisibilityRegistry.set(true)
    }

    override fun onStop() {
        super.onStop()
        com.aura.aura_ui.services.AppVisibilityRegistry.set(false)
    }

    override fun onResume() {
        super.onResume()
        // Restore normal interaction when the user brings Aura itself back.
        setMainWindowTouchPassthrough(false)
        // Home-first UX: the overlay opens only via "Start chatting" — never on resume.
        // (The old auto-launch here was what made the app impossible to open normally.)
    }

    override fun onPause() {
        // A translucent Activity remains a valid full-screen input window unless
        // this is explicit. Apply it before the system completes the task switch.
        setMainWindowTouchPassthrough(true)
        super.onPause()
    }

    private fun isScreenCaptureGranted(): Boolean {
        return AuraAccessibilityService.instance?.isMediaProjectionAvailable() ?: false
    }

    /**
     * Request screen capture permission for VLM-based screen analysis.
     * Falls back to UI-only mode if permission is denied.
     */
    private fun requestScreenCapturePermission() {
        try {
            Log.d(TAG, "Screen capture request initiated")

            // Check if accessibility service is running first
            val isAccessibilityEnabled = checkAccessibilityService()
            Log.d(TAG, "Accessibility service status: $isAccessibilityEnabled")

            if (!isAccessibilityEnabled) {
                Log.w(TAG, "Accessibility service not enabled - cannot request screen capture")
                android.widget.Toast.makeText(
                    this,
                    "Please enable Accessibility Service first:\nSettings → Accessibility → AURA",
                    android.widget.Toast.LENGTH_LONG,
                ).show()

                // Open accessibility settings to help user
                try {
                    val intent =
                        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open accessibility settings", e)
                }
                return
            }

            // Check if already granted
            if (isScreenCaptureGranted()) {
                Log.d(TAG, "Screen capture already granted")
                android.widget.Toast.makeText(
                    this,
                    "Screen capture is already enabled",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                if (pendingScreenCaptureRequest) {
                    pendingScreenCaptureRequest = false
                    finish()
                }
                return
            }

            val mediaProjectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE,
                ) as android.media.projection.MediaProjectionManager
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()

            Log.d(TAG, "Launching screen capture permission dialog...")
            screenCaptureLauncher.launch(captureIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request screen capture permission", e)
            android.widget.Toast.makeText(
                this,
                "Failed to request screen capture: ${e.message}",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Check if AURA accessibility service is enabled
     */
    private fun checkAccessibilityService(): Boolean {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

        Log.d(TAG, "Checking accessibility service status...")
        Log.d(TAG, "Package name: $packageName")
        Log.d(TAG, "Enabled services count: ${enabledServices.size}")

        for (service in enabledServices) {
            val serviceName = service.resolveInfo.serviceInfo.name
            val servicePackage = service.resolveInfo.serviceInfo.packageName
            Log.d(TAG, "Found service: $servicePackage/$serviceName")

            // Check for both possible service paths
            if (servicePackage == packageName &&
                (
                    serviceName == "com.aura.aura_ui.accessibility.AuraAccessibilityService" ||
                        serviceName.endsWith("AuraAccessibilityService")
                )
            ) {
                Log.i(TAG, "AURA Accessibility Service is enabled")
                return true
            }
        }

        Log.w(TAG, "AURA Accessibility Service is not enabled")
        return false
    }

    /**
     * Start visual feedback overlay service
     */
    private fun startVisualFeedbackService() {
        if (com.aura.aura_ui.overlay.VisualFeedbackOverlayService.canDrawOverlays(this)) {
            startService(Intent(this, com.aura.aura_ui.overlay.VisualFeedbackOverlayService::class.java))
            Log.i(TAG, "Visual feedback overlay service started")
        } else {
            Log.w(TAG, "Overlay permission not granted, visual feedback disabled")
        }
    }

    private fun checkPermissions(): Boolean {
        val audioPermission =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val internetPermission =
            checkSelfPermission(Manifest.permission.INTERNET) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val overlayPermission = Settings.canDrawOverlays(this)

        Log.d(TAG, "Permissions - Audio: $audioPermission, Internet: $internetPermission, Overlay: $overlayPermission")

        return audioPermission && internetPermission && overlayPermission
    }

    private fun requestPermissions() {
        Log.d(TAG, "Requesting permissions")
        
        // Build list of permissions based on Android version
        val permissionsList = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        // Add POST_NOTIFICATIONS for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsList.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Add POST_PROMOTED_NOTIFICATIONS for Android 16+ Live Update / Fluid Cloud
        if (Build.VERSION.SDK_INT >= 36) {
            permissionsList.add("android.permission.POST_PROMOTED_NOTIFICATIONS")
        }
        
        permissionLauncher.launch(permissionsList.toTypedArray())

        if (!Settings.canDrawOverlays(this)) {
            val intent =
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            overlayPermissionLauncher.launch(intent)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        refreshJob?.cancel()
        Log.d(TAG, "onDestroy: Activity destroyed")
    }
}
