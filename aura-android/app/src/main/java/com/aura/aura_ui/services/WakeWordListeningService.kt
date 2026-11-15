package com.aura.aura_ui.services

import com.aura.aura_ui.compat.ForegroundServiceCompat
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aura.aura_ui.MainActivity
import com.aura.aura_ui.R
import com.aura.aura_ui.overlay.AuraOverlayService
import com.aura.aura_ui.voice.ListeningMode
import com.aura.aura_ui.voice.ListeningModeController
import com.aura.aura_ui.voice.StubWakeWordDetector
import com.aura.aura_ui.voice.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * WakeWordListeningService - Background service for always-on wake word detection.
 * 
 * This foreground service runs continuously in the background, listening for the
 * "Hey AURA" wake phrase. When detected, it triggers the voice assistant overlay.
 * 
 * Key features:
 * - Runs as foreground service with low-priority notification
 * - Uses openWakeWord ([WakeWordDetector.create]) for the wake word
 * - Coordinates with ListeningModeController to avoid audio conflicts
 * - Battery efficient (~1% CPU usage)
 * 
 * Lifecycle:
 * 1. Start via startService() when user enables wake word
 * 2. Runs in PASSIVE mode (wake word detection)
 * 3. On wake word → transition to ACTIVE → show overlay
 * 4. Overlay handles STT, then signals completion
 * 5. Returns to PASSIVE mode
 * 
 * Usage:
 * ```kotlin
 * WakeWordListeningService.start(context)  // Enable wake word
 * WakeWordListeningService.stop(context)   // Disable wake word
 * ```
 */
class WakeWordListeningService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var wakeWordDetector: WakeWordDetector? = null
    private var listeningModeController: ListeningModeController? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var isWakeWordEnabled = false
    // True once the microphone foreground service was actually accepted by the system.
    private var foregroundReady = false
    private var activeModeSince = 0L  // timestamp when mode became ACTIVE, 0 = not active
    private var watchdogJob: Job? = null

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "aura_wake_word_quiet_v2"
        private const val LEGACY_CHANNEL_ID = "aura_wake_word_channel"
        private const val PAUSED_CHANNEL_ID = "aura_wake_word_paused"
        private const val PAUSED_NOTIFICATION_ID = 2002
        
        const val ACTION_START = "com.aura.aura_ui.START_WAKE_WORD"
        const val ACTION_STOP = "com.aura.aura_ui.STOP_WAKE_WORD"
        const val ACTION_TOGGLE = "com.aura.aura_ui.TOGGLE_WAKE_WORD"
        
        @Volatile
        private var instance: WakeWordListeningService? = null
        
        /**
         * Start the wake word listening service.
         */
        fun start(context: Context) {
            // A blocked install must not keep the microphone open (see GateEnforcer).
            if (com.aura.aura_ui.remote.RemoteGateManager.isBlocked()) return
            val intent = Intent(context, WakeWordListeningService::class.java).apply {
                action = ACTION_START
            }
            // onCreate already degrades gracefully when the FGS is refused, but the
            // *caller* also has to survive: on Android 12+ startForegroundService()
            // itself throws from a background context (BootReceiver is exactly that).
            ForegroundServiceCompat.startSafely(context, intent)
        }
        
        /**
         * Stop the wake word listening service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, WakeWordListeningService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        /**
         * Toggle the wake word listening service.
         */
        fun toggle(context: Context) {
            val intent = Intent(context, WakeWordListeningService::class.java).apply {
                action = ACTION_TOGGLE
            }
            ForegroundServiceCompat.startSafely(context, intent)
        }
        
        /**
         * Check if wake word listening is currently active.
         */
        fun isActive(): Boolean = instance?.isWakeWordEnabled ?: false
        
        /**
         * Get current listening mode.
         */
        fun getCurrentMode(): ListeningMode = 
            instance?.listeningModeController?.currentMode?.value ?: ListeningMode.OFF
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()
        if (!startForegroundSafely()) {
            // Not fatal: the wake word simply stays off until something starts us from a
            // foreground-eligible state (opening the app, or the Settings toggle).
            Log.w(TAG, "wake word unavailable in this state — stopping without crashing")
            if (savedWakeWordEnabled()) postPausedNotification()
            instance = null
            stopSelf()
            return
        }
        foregroundReady = true
        getSystemService(NotificationManager::class.java)?.cancel(PAUSED_NOTIFICATION_ID)

        // Initialize components
        listeningModeController = ListeningModeController.getInstance(this)
        setupModeController()

        Log.i(TAG, "✨ WakeWordListeningService created")
    }

    /**
     * Android 14+ refuses a `microphone` foreground service unless the app is in a
     * foreground-eligible state, because RECORD_AUDIO is a while-in-use permission. Two callers
     * routinely sit outside that window: BootReceiver (BOOT_COMPLETED is always background) and a
     * cold app launch that races the activity reaching the foreground. Unguarded, the rejection
     * propagates out of onCreate and takes the whole process down — the app died on roughly every
     * other cold start. Degrade to "no wake word" instead.
     */
    private fun startForegroundSafely(): Boolean = try {
        startForeground(NOTIFICATION_ID, createNotification(false))
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground(microphone) rejected: ${e.message}")
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, isEnabled=$isWakeWordEnabled")

        // onCreate could not claim the microphone FGS, or the remote gate blocks AURA (a sticky
        // restart bypasses start()) — never touch the mic in either state.
        if (!foregroundReady || com.aura.aura_ui.remote.RemoteGateManager.isBlocked()) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> enableWakeWord()
            ACTION_STOP -> disableWakeWord()
            // The saved setting, not the in-memory flag: a process that was killed and restarted
            // starts with isWakeWordEnabled=false, so "Disable" would otherwise turn it back ON.
            ACTION_TOGGLE -> if (savedWakeWordEnabled()) disableWakeWord() else enableWakeWord()
            else -> {
                // Null intent = START_STICKY restart after the process died: restore the saved state.
                if (intent == null && !savedWakeWordEnabled()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                enableWakeWord()
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        
        disableWakeWord(persist = false)
        releaseWakeLock()
        serviceScope.cancel()
        instance = null
        
        Log.i(TAG, "WakeWordListeningService destroyed")
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        
        Log.i(TAG, "⚠️ Task removed (app swiped from recents) - scheduling wake word restart")
        if (isWakeWordEnabled) scheduleRestart()
        // Don't call stopSelf() - let the system restart us
    }

    /**
     * Re-start this service 1 s from now, for OEM skins that kill the process on a swipe.
     *
     * - Why foreground + exact: a plain `startService` from the background is refused on Android
     *   8+, and an exact alarm is one of the documented exemptions for starting a foreground
     *   service from the background. Without exact-alarm access it falls back to an inexact alarm.
     * - Fails: Android 14+ may still refuse the microphone type from this state; [onCreate] then
     *   stops cleanly and [com.aura.aura_ui.accessibility.AuraAccessibilityService] retries when the
     *   system rebinds it.
     */
    private fun scheduleRestart() {
        val restartIntent = Intent(applicationContext, WakeWordListeningService::class.java).apply {
            action = ACTION_START
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 1, restartIntent, flags)
        } else {
            PendingIntent.getService(this, 1, restartIntent, flags)
        }
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = android.os.SystemClock.elapsedRealtime() + 1000
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        runCatching {
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent)
            }
        }.onFailure { Log.w(TAG, "restart alarm refused: ${it.message}") }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory - wake word detection continues")
        // Don't stop wake word on low memory - it's critical functionality
    }
    
    private fun setupModeController() {
        listeningModeController?.setOnModeChanged { oldMode, newMode ->
            Log.i(TAG, "Mode changed: $oldMode → $newMode")
            
            when (newMode) {
                ListeningMode.PASSIVE -> {
                    activeModeSince = 0L
                    startWakeWordDetection()
                }
                ListeningMode.ACTIVE, ListeningMode.HITL -> {
                    activeModeSince = System.currentTimeMillis()
                    // Stop wake word - STT will take over
                    stopWakeWordDetection()
                    // Show overlay and let it handle STT
                    if (oldMode != newMode) showOverlayAndStartSTT()
                }
                ListeningMode.OFF -> {
                    activeModeSince = 0L
                    stopWakeWordDetection()
                }
            }
            
            // Update notification
            updateNotification(newMode == ListeningMode.PASSIVE)
        }
        
        listeningModeController?.setOnWakeWordDetected { keyword ->
            Log.i(TAG, "🔊 Wake word callback triggered: $keyword")
            // The mode change to ACTIVE will trigger overlay show
        }
        
        // Observe mode changes for cleanup
        serviceScope.launch {
            listeningModeController?.currentMode?.collectLatest { mode ->
                when (mode) {
                    ListeningMode.PASSIVE -> {
                        listeningModeController?.markWakeWordStarted()
                    }
                    else -> {
                        listeningModeController?.markWakeWordStopped()
                    }
                }
            }
        }
    }
    
    private fun enableWakeWord() {
        if (isWakeWordEnabled) {
            Log.d(TAG, "Wake word already enabled - ensuring detection is running")
            // Even if enabled, ensure detection is actually running
            ensureWakeWordDetectionRunning()
            return
        }
        
        Log.i(TAG, "🎤 Enabling wake word detection...")
        
        // Initialize detector if needed
        if (wakeWordDetector == null) {
            Log.i(TAG, "Creating wake word detector...")
            wakeWordDetector = WakeWordDetector.create(this)
            
            // Check if we got a real detector or stub
            val detectorType = wakeWordDetector?.javaClass?.simpleName ?: "null"
            Log.i(TAG, "Wake word detector created: $detectorType")
            
            if (wakeWordDetector is StubWakeWordDetector) {
                Log.w(TAG, "⚠️ Using stub detector - wake word will not work! The openWakeWord engine failed to load (is assets/wakeword/hey_aura.onnx present?).")
            }
            
            setupWakeWordDetector()
        }
        
        isWakeWordEnabled = true
        acquireWakeLock()
        startWatchdog()

        // Transition to PASSIVE mode (this triggers startWakeWordDetection via callback)
        val currentMode = listeningModeController?.currentMode?.value
        if (currentMode == ListeningMode.PASSIVE) {
            // Already in PASSIVE, manually start detection
            Log.i(TAG, "Already in PASSIVE mode, starting detection directly")
            startWakeWordDetection()
        } else {
            listeningModeController?.transitionToPassive()
        }

        updateNotification(true)
        saveWakeWordEnabled(true)

        Log.i(TAG, "✅ Wake word detection enabled (detector listening: ${wakeWordDetector?.isListening?.value})")
    }
    
    /**
     * Ensures wake word detection is running if it should be.
     * Called when service is already enabled but detection might have stopped.
     */
    private fun ensureWakeWordDetectionRunning() {
        if (wakeWordDetector == null) {
            Log.w(TAG, "⚠️ Wake word detector is null - recreating...")
            wakeWordDetector = WakeWordDetector.create(this)
            val detectorType = wakeWordDetector?.javaClass?.simpleName ?: "null"
            Log.i(TAG, "Wake word detector recreated: $detectorType")
            setupWakeWordDetector()
        }
        
        val isListening = wakeWordDetector?.isListening?.value ?: false
        val currentMode = listeningModeController?.currentMode?.value
        
        Log.d(TAG, "Ensuring wake word running: isListening=$isListening, mode=$currentMode, detector=${wakeWordDetector?.javaClass?.simpleName}")
        
        // Always try to start if in PASSIVE mode - start() will handle the "already listening" case
        if (currentMode == ListeningMode.PASSIVE) {
            Log.i(TAG, "In PASSIVE mode - starting/ensuring wake word detection...")
            startWakeWordDetection()
        } else if (currentMode == ListeningMode.OFF || currentMode == null) {
            Log.i(TAG, "Mode is OFF/null, transitioning to PASSIVE...")
            listeningModeController?.transitionToPassive()
        }
    }
    
    /**
     * Watchdog, every 15 s, only while the overlay is closed (an open overlay owns the mic, and
     * grabbing it back mid-conversation silences the assistant):
     * - stuck in ACTIVE for >40 s (command never completed) → force PASSIVE;
     * - PASSIVE but the detection loop died (mic read error, mic busy at start) → restart it.
     *   Without this a single failed start left the wake word dead until a manual toggle.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive && isWakeWordEnabled) {
                delay(15_000L)
                if (AuraOverlayService.isVisible()) continue
                val mode = listeningModeController?.currentMode?.value
                val stuck = activeModeSince > 0L &&
                    (System.currentTimeMillis() - activeModeSince) > 40_000L
                if (stuck) {
                    Log.w(TAG, "⚠️ Watchdog: stuck in ACTIVE for >40 s — forcing PASSIVE")
                    listeningModeController?.transitionToPassive()
                    activeModeSince = 0L
                } else if (mode == ListeningMode.PASSIVE && wakeWordDetector?.isListening?.value != true) {
                    Log.w(TAG, "⚠️ Watchdog: PASSIVE but detector not listening — restarting")
                    startWakeWordDetection()
                }
            }
        }
    }

    /**
     * @param persist false for teardown (onDestroy). Saving "off" there flipped the user's setting
     * off every time the system destroyed the service, so it stayed off after the next restart.
     */
    private fun disableWakeWord(persist: Boolean = true) {
        if (persist) saveWakeWordEnabled(false)
        if (!isWakeWordEnabled) {
            if (persist) stopSelf()
            return
        }

        Log.i(TAG, "🔇 Disabling wake word detection...")
        
        isWakeWordEnabled = false
        
        // Transition to OFF mode
        listeningModeController?.transitionToOff()
        
        // Release detector
        wakeWordDetector?.release()
        wakeWordDetector = null
        
        releaseWakeLock()
        updateNotification(false)

        // Stop service when disabled
        stopSelf()
        
        Log.i(TAG, "Wake word detection disabled")
    }
    
    private fun setupWakeWordDetector() {
        wakeWordDetector?.setOnWakeWordDetected { keyword ->
            Log.i(TAG, "🔊 Wake word detected: $keyword")
            
            // Notify mode controller (this stops wake word and triggers ACTIVE mode)
            listeningModeController?.onWakeWordTriggered(keyword)
        }
    }
    
    private fun startWakeWordDetection() {
        if (wakeWordDetector == null) {
            Log.e(TAG, "❌ Cannot start wake word detection - detector is null")
            return
        }
        
        if (wakeWordDetector?.isListening?.value == true) {
            Log.d(TAG, "Wake word detection already running")
            return
        }
        
        Log.i(TAG, "▶️ Starting wake word detection (detector: ${wakeWordDetector?.javaClass?.simpleName})...")
        wakeWordDetector?.start()
        
        // Verify it started
        serviceScope.launch {
            delay(100) // Small delay to allow start to complete
            val isListening = wakeWordDetector?.isListening?.value ?: false
            if (isListening) {
                Log.i(TAG, "✅ Wake word detection started successfully")
            } else {
                Log.e(TAG, "❌ Wake word detection failed to start - check logcat for errors")
            }
        }
    }
    
    private fun stopWakeWordDetection() {
        if (wakeWordDetector?.isListening?.value != true) {
            Log.d(TAG, "Wake word detection not running")
            return
        }
        
        Log.i(TAG, "⏹️ Stopping wake word detection...")
        wakeWordDetector?.stop()
    }
    
    private fun showOverlayAndStartSTT() {
        Log.i(TAG, "🎯 Showing overlay for STT...")
        
        // Check if device is locked - wake word should work on locked device but request unlock
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        val isDeviceLocked = keyguardManager.isKeyguardLocked
        val isScreenOn = powerManager.isInteractive
        
        Log.i(TAG, "Device state: locked=$isDeviceLocked, screenOn=$isScreenOn")
        
        if (isDeviceLocked) {
            Log.i(TAG, "🔒 Device is locked - requesting unlock first")
            requestUnlockAndShowOverlay()
        } else {
            // Device is unlocked, show overlay directly
            AuraOverlayService.showAndListen(this, source = "wake_word")
        }
    }
    
    /** Locked phone: light the screen and hand off to MainActivity, which asks for the unlock. */
    private fun requestUnlockAndShowOverlay() {
        wakeUpScreen()
        launchUnlockActivity()
    }

    /**
     * Wake up the screen using PowerManager.
     */
    private fun wakeUpScreen() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        if (!powerManager.isInteractive) {
            Log.i(TAG, "💡 Waking up screen...")
            
            // Use ACQUIRE_CAUSES_WAKEUP to turn on screen
            @Suppress("DEPRECATION")
            val screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or 
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "aura:screen_wake_lock"
            )
            screenWakeLock.acquire(3000) // 3 seconds to show unlock prompt
            
            // Release after a short delay
            serviceScope.launch {
                delay(3000)
                if (screenWakeLock.isHeld) {
                    screenWakeLock.release()
                }
            }
        }
    }
    
    /**
     * Opens MainActivity over the lock screen with the unlock extras.
     *
     * - Why this may start an activity from the background: AURA holds SYSTEM_ALERT_WINDOW, one
     *   of Android's background-activity-start exemptions.
     * - Change together: `MainActivity.handleWakeWordUnlockRequest`.
     */
    private fun launchUnlockActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("UNLOCK_AND_SHOW_OVERLAY", true)
            putExtra("AUTO_START_LISTENING", true)
        }
        startActivity(intent)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java) ?: return
            // The old channel was IMPORTANCE_DEFAULT + colorized, which ColorOS promotes to a Live
            // Alert pill that sat in the status bar all day. Importance of an existing channel can't
            // be lowered, hence a new id. Deleting first is safe: nothing is posted on it yet here.
            runCatching { notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wake word (background)",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Required by Android to keep listening for \"Hey AURA\" in the background"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Android requires a notification for a background microphone service, so this cannot be
     * removed while the wake word is on. It is kept as quiet as the platform allows: MIN importance
     * (no status-bar icon, folded at the bottom of the shade), no colour, no status category.
     * The visible "AURA is active" surface is the overlay's own chip, shown only while it works.
     */
    private fun createNotification(isListening: Boolean): Notification {
        val toggleIntent = Intent(this, WakeWordListeningService::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (wakeWordDetector is StubWakeWordDetector) {
            "Wake word model missing, not listening"
        } else if (isListening) {
            "Say \"Hey AURA\""
        } else {
            "Wake word paused"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AURA wake word")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(
                R.drawable.ic_mic,
                if (isListening) "Disable" else "Enable",
                togglePendingIntent
            )
            .build()
    }
    
    /**
     * Tells the user the wake word is off because Android refused the background microphone,
     * with one tap to turn it back on.
     *
     * - Why: Android 14+ refuses a `microphone` foreground service started from the background,
     *   which covers boot, a sticky restart after an OEM kill, and the swipe-away restart alarm.
     *   Every one of those ends in [onCreate]'s refusal branch, so this is the one place that
     *   keeps it from failing silently.
     * - Why the tap goes straight to the service: starting a foreground service "by interacting
     *   with a notification" is a documented exemption from the while-in-use restriction, so
     *   the tap succeeds even with the app closed.
     * - Fails: on Android 13+ without POST_NOTIFICATIONS nothing is shown; only logcat remains.
     */
    private fun postPausedNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(PAUSED_CHANNEL_ID, "Wake word paused", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Shown when Android stops the wake word in the background" }
            )
        }
        val resume = Intent(this, WakeWordListeningService::class.java).apply { action = ACTION_START }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 2, resume, flags)
        } else {
            PendingIntent.getService(this, 2, resume, flags)
        }
        val notification = NotificationCompat.Builder(this, PAUSED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("\"Hey AURA\" is paused")
            .setContentText("Android stopped the wake word in the background. Tap to turn it back on.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(PAUSED_NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "paused notification refused: ${it.message}") }
    }

    private fun updateNotification(isListening: Boolean) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification(isListening))
    }
    
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "aura:wake_word_lock"
            ).apply {
                setReferenceCounted(false)
            }
        }
        // Timed and acquired only in enableWakeWord(), so it lapses 30 min after the wake word is turned on.
        wakeLock?.acquire(30 * 60 * 1000L)
        Log.d(TAG, "Wake lock acquired")
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "Wake lock released")
            }
        }
    }
    
    private fun savedWakeWordEnabled(): Boolean =
        getSharedPreferences("aura_settings", Context.MODE_PRIVATE).getBoolean("wake_word_enabled", false)

    private fun saveWakeWordEnabled(enabled: Boolean) {
        val prefs = getSharedPreferences("aura_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("wake_word_enabled", enabled).apply()
    }
}
