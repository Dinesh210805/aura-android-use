package com.aura.aura_ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * WakeWordDetector - Interface for wake word detection.
 * 
 * Implementations:
 * - SherpaKwsWakeWordDetector: the engine, sherpa-onnx keyword spotting
 * - StubWakeWordDetector: no-op fallback when that engine cannot load
 * 
 * CRITICAL: Only ONE audio consumer can be active at a time.
 * Wake word detection must stop before STT starts.
 */
interface WakeWordDetector {

    /**
     * Current detection state
     */
    val isListening: StateFlow<Boolean>

    /**
     * Available wake words
     */
    val availableKeywords: List<String>

    /**
     * Start listening for wake words.
     * Will fail if AudioRecord is in use by another component.
     */
    fun start()

    /**
     * Stop listening for wake words.
     * Releases AudioRecord for other components.
     */
    fun stop()

    /**
     * Set callback for when wake word is detected
     */
    fun setOnWakeWordDetected(callback: (String) -> Unit)

    /**
     * Release all resources
     */
    fun release()

    companion object {
        /**
         * Create a WakeWordDetector instance.
         *
         * Primary engine is [SherpaKwsWakeWordDetector] — free, offline, no access
         * key. Falls back to [StubWakeWordDetector] (no-op) if the engine cannot
         * initialize, so the rest of the voice pipeline never crashes on a bad load.
         */
        fun create(context: Context): WakeWordDetector {
            return try {
                val detector = SherpaKwsWakeWordDetector(context)
                Log.i("WakeWordDetector", "✅ SherpaKwsWakeWordDetector created (free, offline)")
                detector
            } catch (e: Exception) {
                Log.e("WakeWordDetector", "❌ Failed to create sherpa-onnx detector: ${e.message}", e)
                Log.e("WakeWordDetector", "⚠️ Using StubWakeWordDetector — wake word will NOT work!")
                StubWakeWordDetector()
            }
        }

        /**
         * Check if wake word detection is available on this device.
         * sherpa-onnx needs no access key — only microphone permission.
         */
        fun isAvailable(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}

/**
 * Stub implementation for development/testing.
 * Does not perform actual wake word detection.
 * Used when the sherpa-onnx engine cannot load.
 */
class StubWakeWordDetector : WakeWordDetector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listeningJob: Job? = null

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    override val availableKeywords: List<String> = listOf(
        "Hey AURA",
        "OK AURA",
        "AURA"
    )

    private var onWakeWordCallback: ((String) -> Unit)? = null

    override fun start() {
        if (_isListening.value) return

        Log.i(TAG, "🎤 Wake word detection started (STUB MODE - no actual detection)")
        _isListening.value = true

        listeningJob = scope.launch {
            // Stub - no actual detection
            Log.d(TAG, "Stub wake word detector running")
        }
    }

    override fun stop() {
        listeningJob?.cancel()
        listeningJob = null
        _isListening.value = false
        Log.i(TAG, "Wake word detection stopped (stub)")
    }

    override fun setOnWakeWordDetected(callback: (String) -> Unit) {
        onWakeWordCallback = callback
    }

    override fun release() {
        stop()
        onWakeWordCallback = null
        Log.i(TAG, "Stub wake word detector released")
    }

    /**
     * Simulate wake word detection (for testing).
     * Call this to trigger the callback as if a wake word was detected.
     */
    fun simulateWakeWord(keyword: String = "Hey AURA") {
        Log.i(TAG, "🔊 Simulated wake word: $keyword")
        onWakeWordCallback?.invoke(keyword)
    }

    companion object {
        private const val TAG = "StubWakeWordDetector"
    }
}
