package com.aura.aura_ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Free, fully-offline wake-word detection powered by sherpa-onnx keyword spotting
 * (Apache-2.0). Replaces the Picovoice Porcupine detector, whose free tier now
 * requires an organizational account.
 *
 * How it differs from Porcupine under the same [WakeWordDetector] interface:
 * - The engine is a tiny streaming zipformer transducer ("open-vocabulary KWS"),
 *   so the wake phrase is just lines of BPE tokens in `assets/kws/keywords.txt` — no
 *   cloud console, no per-device `.ppn`, no access key.
 * - Wake phrase is "Hello AURA", NOT "Hey AURA". Measured on a real recording: this
 *   GigaSpeech model cannot hear the rare word "aura" after "hey" (~1/5 recall), but
 *   "hello" is a strong common-word anchor and the "aura" tail is caught by the
 *   ▁OR spellings (`HELLO ORAH` = `▁HE LL O ▁OR A H`). See keywords.txt for variants.
 * - ONNX Runtime is statically linked inside `libsherpa-onnx-jni.so`, so this does
 *   NOT touch OmniParser's onnxruntime-android at runtime.
 *
 * Audio contract is identical to the old detector: 16 kHz mono 16-bit PCM from
 * [AudioRecord]. The only wire-level difference is that sherpa consumes normalized
 * `FloatArray` samples, so each PCM frame is divided by 32768 before being fed in.
 *
 * CRITICAL (unchanged): only ONE audio consumer can hold the mic at a time. Wake
 * word detection must [stop] before STT starts.
 */
class SherpaKwsWakeWordDetector(
    private val context: Context,
    /** Per-keyword boosting score; higher = easier to trigger. */
    private val keywordsScore: Float = 1.5f,
    /** Trigger threshold 0..1; lower = easier trigger. 0.20 balances recall vs false-accepts. */
    private val keywordsThreshold: Float = 0.20f,
) : WakeWordDetector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listeningJob: Job? = null
    private var spotter: KeywordSpotter? = null

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // Display names only: getResult() returns the matched phrase itself (e.g. "WAKE UP ORAH").
    override val availableKeywords: List<String> = listOf("Hello AURA", "Wake up AURA")

    private var onWakeWordCallback: ((String) -> Unit)? = null
    private var initError: Exception? = null

    init {
        initializeSpotter()
    }

    private fun initializeSpotter() {
        try {
            Log.i(TAG, "🔧 Initializing sherpa-onnx keyword spotter (assets/$ASSET_DIR)…")
            // sherpa's own reference config for the gigaspeech-3.3M KWS model
            // (modelType = "zipformer2"). We ship the int8 variants to keep the
            // bundle ~5 MB; sherpa reads them straight from the APK's assets.
            val modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$ASSET_DIR/encoder.int8.onnx",
                    decoder = "$ASSET_DIR/decoder.int8.onnx",
                    joiner = "$ASSET_DIR/joiner.int8.onnx",
                ),
                tokens = "$ASSET_DIR/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer2",
            )
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                keywordsFile = "$ASSET_DIR/keywords.txt",
                keywordsScore = keywordsScore,
                keywordsThreshold = keywordsThreshold,
            )
            // Pre-flight: sherpa's native newFromAsset() calls abort() (SIGABRT —
            // NOT a catchable Java exception) if any keyword token is absent from
            // the model vocab. Validate in Kotlin first so a bad keywords.txt
            // degrades to the Stub detector instead of crashing the whole app.
            validateKeywordAssets()

            // assetManager != null → model paths are resolved from APK assets.
            spotter = KeywordSpotter(assetManager = context.assets, config = config)
            initError = null
            Log.i(TAG, "✅ sherpa-onnx keyword spotter initialized (listening phrases: \"Hello AURA\", \"Wake up AURA\")")
        } catch (e: Exception) {
            initError = e
            Log.e(TAG, "❌ Failed to initialize sherpa-onnx keyword spotter: ${e.message}", e)
            throw e
        }
    }

    /**
     * Verify every keyword token exists in the model vocabulary BEFORE handing the
     * config to native code. sherpa's `EncodeBase` aborts the process on a missing
     * token (as happened with a space-bearing `@Hey AURA` display phrase). Throwing
     * here is recoverable — [WakeWordDetector.create] falls back to the Stub.
     */
    private fun validateKeywordAssets() {
        val vocab: HashSet<String> = context.assets.open("$ASSET_DIR/tokens.txt")
            .bufferedReader().useLines { lines ->
                lines.mapNotNull { it.trim().substringBefore(' ').takeIf(String::isNotEmpty) }
                    .toHashSet()
            }
        context.assets.open("$ASSET_DIR/keywords.txt").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                // Keep only the acoustic-token columns; drop ":score", "#threshold", "@phrase".
                val tokens = line.substringBefore(" :").substringBefore(" #").substringBefore(" @")
                    .trim().split(Regex("\\s+"))
                val missing = tokens.filter { it.isNotEmpty() && it !in vocab }
                check(missing.isEmpty()) {
                    "keywords.txt token(s) $missing not in tokens.txt (line: \"$line\"). " +
                        "sherpa would SIGABRT on this — refusing to initialize."
                }
            }
        }
    }

    override fun start() {
        // Reconcile a stale "listening" flag whose job already died.
        if (_isListening.value) {
            if (listeningJob?.isActive == true) {
                Log.d(TAG, "Already listening (job active)")
                return
            }
            Log.w(TAG, "⚠️ isListening=true but job not active — resetting state")
            _isListening.value = false
        }

        val activeSpotter = spotter
        if (activeSpotter == null) {
            Log.e(TAG, "❌ Cannot start — spotter not initialized (initError: ${initError?.message})")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "❌ Cannot start — RECORD_AUDIO permission not granted")
            return
        }

        Log.i(TAG, "🎤 Starting wake word detection…")
        _isListening.value = true

        // A stopped loop can still be inside read()/decode() for ~100 ms; wait it out so two
        // loops never share the mic.
        val previous = listeningJob
        listeningJob = scope.launch {
            previous?.join()
            try {
                runDetectionLoop(activeSpotter)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in wake word detection loop: ${e.message}", e)
            } finally {
                withContext(Dispatchers.Main) {
                    _isListening.value = false
                    Log.d(TAG, "Listening job completed, isListening=false")
                }
            }
        }
    }

    private suspend fun runDetectionLoop(spotter: KeywordSpotter) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "❌ Invalid AudioRecord buffer size: $minBuffer")
            withContext(Dispatchers.Main) { _isListening.value = false }
            return
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK_SAMPLES * 2) * 2,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ AudioRecord failed to initialize (state=${audioRecord.state})")
            audioRecord.release()
            withContext(Dispatchers.Main) { _isListening.value = false }
            return
        }

        val stream: OnlineStream = spotter.createStream()
        val shortBuf = ShortArray(CHUNK_SAMPLES)
        val floatBuf = FloatArray(CHUNK_SAMPLES)

        try {
            audioRecord.startRecording()
            Log.i(TAG, "✅ Wake word detection active — listening for \"Hello AURA\" or \"Wake up AURA\"…")

            while (coroutineContext.isActive && _isListening.value) {
                val n = audioRecord.read(shortBuf, 0, CHUNK_SAMPLES)
                if (n <= 0) {
                    if (n < 0) {
                        Log.e(TAG, "AudioRecord read error: $n")
                        break
                    }
                    continue
                }

                // PCM16 → normalized float [-1, 1], which sherpa expects.
                for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32768.0f
                stream.acceptWaveform(if (n == CHUNK_SAMPLES) floatBuf else floatBuf.copyOf(n), SAMPLE_RATE)

                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val keyword = spotter.getResult(stream).keyword
                    if (keyword.isNotBlank()) {
                        Log.i(TAG, "🔊 Wake word detected: \"$keyword\"")
                        spotter.reset(stream)
                        // Release the mic BEFORE handing off to STT (single audio consumer).
                        withContext(Dispatchers.Main) {
                            stopInternal()
                            onWakeWordCallback?.invoke(keyword)
                        }
                        return
                    }
                }
            }
        } finally {
            stream.release()
            audioRecord.stop()
            audioRecord.release()
            Log.d(TAG, "AudioRecord + stream released")
        }
    }

    override fun stop() {
        if (!_isListening.value) return
        Log.i(TAG, "⏹️ Stopping wake word detection…")
        stopInternal()
    }

    private fun stopInternal() {
        _isListening.value = false
        // Cancel only: release() and the next start() must still be able to join this job.
        listeningJob?.cancel()
        // AudioRecord + stream cleanup happens in the loop's finally block.
    }

    override fun setOnWakeWordDetected(callback: (String) -> Unit) {
        onWakeWordCallback = callback
    }

    override fun release() {
        Log.i(TAG, "Releasing SherpaKwsWakeWordDetector")
        stop()
        onWakeWordCallback = null
        val loop = listeningJob
        val native = spotter ?: return
        spotter = null
        // Cancellation is cooperative: the loop may still be inside decode(). Freeing the spotter
        // under it was a null-deref SIGSEGV that killed the whole app (2026-09-14). Free it only
        // once the loop, and its finally that releases the stream, has finished.
        scope.launch {
            val finished = loop == null || withTimeoutOrNull(RELEASE_WAIT_MS) { loop.join() } != null
            if (finished) {
                runCatching { native.release() }.onFailure { Log.e(TAG, "Error releasing spotter", it) }
            } else {
                Log.w(TAG, "Detection loop still running after ${RELEASE_WAIT_MS}ms, leaking the spotter instead of crashing")
            }
        }
    }

    companion object {
        private const val TAG = "SherpaKwsWakeWord"
        private const val ASSET_DIR = "kws"
        private const val SAMPLE_RATE = 16000

        // ~100 ms of audio per read — low latency without over-polling the mic.
        private const val CHUNK_SAMPLES = 1600
        private const val RELEASE_WAIT_MS = 2_000L
    }
}
