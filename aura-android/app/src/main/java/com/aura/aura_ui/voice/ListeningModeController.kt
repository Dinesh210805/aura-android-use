package com.aura.aura_ui.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ListeningModeController - Coordinates wake word detection and STT.
 * 
 * CRITICAL RULE: Wake word and STT must NEVER run simultaneously.
 * This controller ensures only one audio consumer is active at any time.
 * 
 * Flow:
 * 1. PASSIVE: Porcupine wake word detection active
 * 2. Wake word detected → transitionToActive()
 * 3. ACTIVE: STT streaming, wake word paused
 * 4. Command complete → transitionToPassive()
 * 
 * Usage:
 * ```kotlin
 * val controller = ListeningModeController.getInstance(context)
 * controller.setOnModeChanged { oldMode, newMode ->
 *     when (newMode) {
 *         ListeningMode.ACTIVE -> startSTT()
 *         ListeningMode.PASSIVE -> startWakeWord()
 *     }
 * }
 * controller.transitionToPassive() // Start listening
 * ```
 */
class ListeningModeController private constructor(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val _currentMode = MutableStateFlow(ListeningMode.OFF)
    val currentMode: StateFlow<ListeningMode> = _currentMode.asStateFlow()
    
    private val _config = MutableStateFlow(ListeningModeConfig())
    val config: StateFlow<ListeningModeConfig> = _config.asStateFlow()
    
    // Callbacks
    private var onModeChanged: ((old: ListeningMode, new: ListeningMode) -> Unit)? = null
    private var onWakeWordDetected: ((keyword: String) -> Unit)? = null
    private var onHitlRequested: ((prompt: String) -> Unit)? = null
    
    // Track what's currently active
    private var isWakeWordActive = false
    private var isSTTActive = false
    
    /**
     * Set callback for mode transitions.
     * Called AFTER mode has changed.
     */
    fun setOnModeChanged(callback: (old: ListeningMode, new: ListeningMode) -> Unit) {
        onModeChanged = callback
    }
    
    /**
     * Set callback for wake word detection.
     * Called when wake word triggers transition to ACTIVE.
     */
    fun setOnWakeWordDetected(callback: (keyword: String) -> Unit) {
        onWakeWordDetected = callback
    }
    
    /**
     * Set callback for HITL requests.
     * Called when backend requests clarification.
     */
    fun setOnHitlRequested(callback: (prompt: String) -> Unit) {
        onHitlRequested = callback
    }
    
    /**
     * Update listening mode configuration.
     */
    fun updateConfig(newConfig: ListeningModeConfig) {
        _config.value = newConfig
    }
    
    /**
     * Transition to PASSIVE mode (wake word detection).
     * Stops any active STT first.
     */
    fun transitionToPassive() {
        val oldMode = _currentMode.value
        if (oldMode == ListeningMode.PASSIVE) {
            // Already PASSIVE but detector may have died — re-notify so service can restart it
            Log.d(TAG, "Already in PASSIVE mode — re-arming detection")
            scope.launch {
                onModeChanged?.invoke(ListeningMode.PASSIVE, ListeningMode.PASSIVE)
            }
            return
        }

        Log.i(TAG, "⏸️ Transitioning $oldMode → PASSIVE")

        // Stop STT first (if running)
        stopSTT()

        // Update mode
        _currentMode.value = ListeningMode.PASSIVE

        // Notify listeners
        scope.launch {
            onModeChanged?.invoke(oldMode, ListeningMode.PASSIVE)
        }
    }
    
    /**
     * Transition to ACTIVE mode (full STT).
     * Called when wake word is detected.
     */
    fun transitionToActive(detectedKeyword: String? = null) {
        val oldMode = _currentMode.value
        if (oldMode == ListeningMode.ACTIVE) {
            Log.d(TAG, "Already in ACTIVE mode")
            return
        }
        
        Log.i(TAG, "🎤 Transitioning $oldMode → ACTIVE (keyword: $detectedKeyword)")
        
        // Stop wake word first (if running)
        stopWakeWord()
        
        // Update mode
        _currentMode.value = ListeningMode.ACTIVE
        
        // Notify listeners
        scope.launch {
            onModeChanged?.invoke(oldMode, ListeningMode.ACTIVE)
            if (detectedKeyword != null) {
                onWakeWordDetected?.invoke(detectedKeyword)
            }
        }
    }
    
    /**
     * Transition to HITL mode (clarification).
     * Called when backend requests more information.
     */
    fun transitionToHitl(prompt: String) {
        val oldMode = _currentMode.value
        
        Log.i(TAG, "❓ Transitioning $oldMode → HITL (prompt: $prompt)")
        
        // Stop wake word if somehow running
        stopWakeWord()
        
        // Update mode
        _currentMode.value = ListeningMode.HITL
        
        // Notify listeners
        scope.launch {
            onModeChanged?.invoke(oldMode, ListeningMode.HITL)
            onHitlRequested?.invoke(prompt)
        }
    }
    
    /**
     * Turn off all listening.
     * Used when user disables assistant or permissions missing.
     */
    fun transitionToOff() {
        val oldMode = _currentMode.value
        if (oldMode == ListeningMode.OFF) {
            Log.d(TAG, "Already in OFF mode")
            return
        }
        
        Log.i(TAG, "🔇 Transitioning $oldMode → OFF")
        
        // Stop everything
        stopWakeWord()
        stopSTT()
        
        // Update mode
        _currentMode.value = ListeningMode.OFF
        
        // Notify listeners
        scope.launch {
            onModeChanged?.invoke(oldMode, ListeningMode.OFF)
        }
    }
    
    /**
     * Called by wake word detector when detection occurs.
     * This is the ONLY entry point for wake word triggering ACTIVE mode.
     */
    fun onWakeWordTriggered(keyword: String) {
        if (_currentMode.value != ListeningMode.PASSIVE) {
            Log.w(TAG, "Wake word triggered but not in PASSIVE mode, ignoring")
            return
        }
        
        Log.i(TAG, "🔊 Wake word triggered: $keyword")
        transitionToActive(keyword)
    }
    
    /**
     * Called when STT command completes.
     * Returns to PASSIVE mode if auto-return enabled.
     */
    fun onCommandComplete() {
        if (_config.value.autoReturnToPassive) {
            Log.i(TAG, "✅ Command complete, returning to PASSIVE")
            transitionToPassive()
        }
    }
    
    /**
     * Called when HITL response received or times out.
     */
    fun onHitlComplete() {
        Log.i(TAG, "✅ HITL complete, returning to PASSIVE")
        transitionToPassive()
    }
    
    /**
     * Mark wake word detector as started.
     * Called by WakeWordListeningService.
     */
    internal fun markWakeWordStarted() {
        isWakeWordActive = true
        Log.d(TAG, "Wake word marked as started")
    }
    
    /**
     * Mark wake word detector as stopped.
     */
    internal fun markWakeWordStopped() {
        isWakeWordActive = false
        Log.d(TAG, "Wake word marked as stopped")
    }
    
    /**
     * Mark STT as started.
     * Called by VoiceCaptureController.
     */
    internal fun markSTTStarted() {
        isSTTActive = true
        Log.d(TAG, "STT marked as started")
    }
    
    /**
     * Mark STT as stopped.
     */
    internal fun markSTTStopped() {
        isSTTActive = false
        Log.d(TAG, "STT marked as stopped")
    }
    
    private fun stopWakeWord() {
        if (isWakeWordActive) {
            // The actual stop will be done by the mode change callback
            // This is just tracking
            isWakeWordActive = false
        }
    }
    
    private fun stopSTT() {
        if (isSTTActive) {
            // The actual stop will be done by the mode change callback
            isSTTActive = false
        }
    }
    
    /**
     * Check if a transition is safe (no resource conflicts).
     */
    fun canTransitionTo(targetMode: ListeningMode): Boolean {
        // OFF can always be entered
        if (targetMode == ListeningMode.OFF) return true
        
        // Can't transition to PASSIVE if STT is still active
        if (targetMode == ListeningMode.PASSIVE && isSTTActive) {
            Log.w(TAG, "Cannot transition to PASSIVE while STT is active")
            return false
        }
        
        // Can't transition to ACTIVE/HITL if wake word is still active
        if ((targetMode == ListeningMode.ACTIVE || targetMode == ListeningMode.HITL) && isWakeWordActive) {
            Log.w(TAG, "Cannot transition to $targetMode while wake word is active")
            return false
        }
        
        return true
    }
    
    companion object {
        private const val TAG = "ListeningModeController"
        
        @Volatile
        private var instance: ListeningModeController? = null
        
        fun getInstance(context: Context): ListeningModeController {
            return instance ?: synchronized(this) {
                instance ?: ListeningModeController(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
