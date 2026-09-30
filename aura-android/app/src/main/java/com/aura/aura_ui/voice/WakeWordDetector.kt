package com.aura.aura_ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WakeWordDetector - Interface for wake word detection.
 * 
 * Implementations:
 * - PorcupineWakeWordDetector: Production implementation using Picovoice Porcupine
 * - StubWakeWordDetector: Development/testing stub
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
         *
         * @param accessKey retained for source compatibility only; ignored now that
         *   the Picovoice-free sherpa-onnx engine is the default.
         */
        fun create(context: Context, accessKey: String? = null): WakeWordDetector {
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
 * Picovoice Porcupine implementation for wake word detection.
 * 
 * Features:
 * - Fully offline processing
 * - Low CPU/battery usage (~1% CPU on most devices)
 * - Custom wake word support via .ppn files
 * - Built-in "Hey Siri" style keywords available
 * 
 * Requirements:
 * - Picovoice access key (free tier available)
 * - RECORD_AUDIO permission
 * - Custom keyword file for "Hey AURA" (optional, uses built-in for testing)
 */
class PorcupineWakeWordDetector(
    private val context: Context,
    private val accessKey: String,
    private val sensitivity: Float = 0.5f
) : WakeWordDetector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listeningJob: Job? = null
    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // Using built-in "JARVIS" as placeholder until custom "Hey AURA" model is created
    // Custom keywords can be created at https://console.picovoice.ai/
    override val availableKeywords: List<String> = listOf("Hey AURA")

    private var onWakeWordCallback: ((String) -> Unit)? = null
    
    // Audio configuration matching Porcupine requirements
    private val sampleRate: Int get() = porcupine?.sampleRate ?: 16000
    private val frameLength: Int get() = porcupine?.frameLength ?: 512
    
    private var initError: Exception? = null

    init {
        initializePorcupine()
    }
    
    private fun initializePorcupine() {
        try {
            // Check for custom keyword file first
            val keywordPath = getCustomKeywordPath()
            
            Log.i(TAG, "🔧 Initializing Porcupine (keywordPath=$keywordPath, accessKey=${accessKey.take(10)}...)")
            
            porcupine = if (keywordPath != null) {
                // Use custom "Hey AURA" keyword
                Log.i(TAG, "Using custom keyword file: $keywordPath")
                Porcupine.Builder()
                    .setAccessKey(accessKey)
                    .setKeywordPath(keywordPath)
                    .setSensitivity(sensitivity)
                    .build(context)
            } else {
                // Fallback to built-in keyword for testing
                Log.w(TAG, "⚠️ No custom keyword found, using built-in JARVIS for testing")
                Porcupine.Builder()
                    .setAccessKey(accessKey)
                    .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                    .setSensitivity(sensitivity)
                    .build(context)
            }
            
            initError = null
            Log.i(TAG, "✅ Porcupine initialized successfully (sampleRate=${porcupine?.sampleRate}, frameLength=${porcupine?.frameLength})")
        } catch (e: PorcupineException) {
            initError = e
            Log.e(TAG, "❌ Failed to initialize Porcupine: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            initError = e
            Log.e(TAG, "❌ Unexpected error initializing Porcupine: ${e.message}", e)
            throw e
        }
    }
    
    private fun getCustomKeywordPath(): String? {
        // Check for custom keyword file in assets or app files
        // Keyword files are created at https://console.picovoice.ai/
        val assetPath = "Hey-Aura_en_android_v4_0_0.ppn"
        val keywordFile = context.filesDir.resolve(assetPath)
        
        // If already copied to files dir, use it
        if (keywordFile.exists()) {
            Log.d(TAG, "Using cached keyword file: ${keywordFile.absolutePath}")
            return keywordFile.absolutePath
        }
        
        // Copy from assets
        return try {
            Log.d(TAG, "Copying keyword file from assets...")
            context.assets.open(assetPath).use { input ->
                keywordFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "✅ Keyword file copied to: ${keywordFile.absolutePath}")
            keywordFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to copy keyword file from assets: ${e.message}", e)
            null
        }
    }

    override fun start() {
        // Check if we think we're listening but the job isn't running
        if (_isListening.value) {
            val jobActive = listeningJob?.isActive == true
            if (!jobActive) {
                Log.w(TAG, "⚠️ isListening=true but job not active - resetting state")
                _isListening.value = false
            } else {
                Log.d(TAG, "Already listening (job active)")
                return
            }
        }

        if (porcupine == null) {
            Log.e(TAG, "❌ Cannot start - Porcupine not initialized (initError: ${initError?.message})")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ Cannot start - RECORD_AUDIO permission not granted")
            return
        }

        Log.i(TAG, "🎤 Starting wake word detection (sampleRate=$sampleRate, frameLength=$frameLength)...")
        _isListening.value = true

        listeningJob = scope.launch {
            try {
                startAudioCapture()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in wake word detection loop: ${e.message}", e)
            } finally {
                // Always reset listening state when coroutine completes
                withContext(Dispatchers.Main) {
                    _isListening.value = false
                    Log.d(TAG, "Listening job completed, isListening=false")
                }
            }
        }
    }
    
    private suspend fun CoroutineScope.startAudioCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        Log.d(TAG, "AudioRecord buffer size: $bufferSize (sampleRate=$sampleRate)")
        
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "❌ Invalid buffer size: $bufferSize")
            withContext(Dispatchers.Main) { _isListening.value = false }
            return
        }
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ AudioRecord failed to initialize (state=${audioRecord?.state})")
            audioRecord?.release()
            audioRecord = null
            withContext(Dispatchers.Main) { _isListening.value = false }
            return
        }
        
        val buffer = ShortArray(frameLength)
        
        try {
            audioRecord?.startRecording()
            Log.i(TAG, "✅ Wake word detection active - listening for \"Hey AURA\"...")
            
            while (isActive && _isListening.value) {
                val readResult = audioRecord?.read(buffer, 0, frameLength) ?: -1
                
                if (readResult == frameLength) {
                    val keywordIndex = porcupine?.process(buffer) ?: -1
                    
                    if (keywordIndex >= 0) {
                        Log.i(TAG, "🔊 Wake word detected! (index=$keywordIndex)")
                        
                        // Stop listening before callback to release AudioRecord
                        withContext(Dispatchers.Main) {
                            stopInternal()
                            onWakeWordCallback?.invoke(availableKeywords.getOrElse(keywordIndex) { "Hey AURA" })
                        }
                        break
                    }
                } else if (readResult < 0) {
                    Log.e(TAG, "AudioRecord read error: $readResult")
                    break
                }
            }
        } finally {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "AudioRecord released")
        }
    }

    override fun stop() {
        if (!_isListening.value) return
        
        Log.i(TAG, "⏹️ Stopping wake word detection...")
        stopInternal()
    }
    
    private fun stopInternal() {
        _isListening.value = false
        listeningJob?.cancel()
        listeningJob = null
        
        // AudioRecord cleanup happens in finally block of startAudioCapture
    }

    override fun setOnWakeWordDetected(callback: (String) -> Unit) {
        onWakeWordCallback = callback
    }

    override fun release() {
        Log.i(TAG, "Releasing PorcupineWakeWordDetector")
        stop()
        
        try {
            porcupine?.delete()
            porcupine = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Porcupine", e)
        }
        
        onWakeWordCallback = null
    }

    companion object {
        private const val TAG = "PorcupineWakeWord"
    }
}
/**
 * Stub implementation for development/testing.
 * Does not perform actual wake word detection.
 * Use when Picovoice access key is not available.
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

/**
 * Wake word detection configuration
 */
data class WakeWordConfig(
    /**
     * Sensitivity (0.0 - 1.0)
     * Higher values = more sensitive but more false positives
     */
    val sensitivity: Float = 0.5f,

    /**
     * Keywords to listen for
     */
    val keywords: List<String> = listOf("Hey AURA"),

    /**
     * Picovoice access key (required for real detection)
     */
    val accessKey: String? = null
)
