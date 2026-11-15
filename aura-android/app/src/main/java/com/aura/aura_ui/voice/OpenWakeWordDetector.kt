package com.aura.aura_ui.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext

/**
 * "Hey AURA" wake word on openWakeWord models (Apache-2.0 code), run on the app's own
 * `onnxruntime-android`. Free, offline, no access key.
 *
 * - Pipeline: mic PCM → `wakeword/melspectrogram.onnx` → `wakeword/embedding_model.onnx` (Google's
 *   speech embedding, shared and frozen) → `wakeword/hey_aura.onnx` (a small classifier trained
 *   for this phrase only). See [OwwFeatures] for the streaming math and `assets/wakeword/README.md`
 *   for how to train the classifier.
 * - Contract: same as [WakeWordDetector]. On a hit it releases the mic first, then calls the
 *   callback on Main with [PHRASE].
 * - Fails: the constructor throws if any of the three assets is missing or unloadable;
 *   [WakeWordDetector.create] then falls back to the stub.
 * - Why one ORT thread with spinning off: this runs all day, and an idle spin-wait between the
 *   80 ms decodes costs more battery than the decode itself.
 * - Why the loop is joined before [release] closes the sessions: closing a session while `run()`
 *   is still inside it crashes the process natively.
 */
class OpenWakeWordDetector(
    private val context: Context,
    /** Classifier score 0..1 that counts as a hit. openWakeWord's own default is 0.5. */
    private val threshold: Float = 0.5f,
) : WakeWordDetector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listeningJob: Job? = null

    private val env = OrtEnvironment.getEnvironment()
    private val melSession: OrtSession
    private val embedSession: OrtSession
    private val wakeSession: OrtSession
    private val wakeInput: String
    private val featureFrames: Int

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    override val availableKeywords: List<String> = listOf(PHRASE)

    private var onWakeWordCallback: ((String) -> Unit)? = null

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }
        fun load(name: String) = env.createSession(
            context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }, opts,
        )
        melSession = load("melspectrogram.onnx")
        embedSession = load("embedding_model.onnx")
        wakeSession = load(CLASSIFIER)
        // The classifier decides how many embeddings it looks at ([1, 16, 96] for the default
        // openWakeWord recipe); read it instead of assuming.
        val info = wakeSession.inputInfo.entries.first()
        wakeInput = info.key
        featureFrames = ((info.value.info as TensorInfo).shape[1]).toInt()
        Log.i(TAG, "openWakeWord ready: $CLASSIFIER takes $featureFrames embeddings, threshold $threshold")
    }

    override fun start() {
        if (_isListening.value && listeningJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Cannot start: RECORD_AUDIO not granted")
            return
        }
        _isListening.value = true
        // A stopped loop can still be inside read()/run() for ~80 ms; wait so two loops never share the mic.
        val previous = listeningJob
        listeningJob = scope.launch {
            previous?.join()
            try {
                runLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Detection loop failed: ${e.message}", e)
            } finally {
                withContext(Dispatchers.Main) { _isListening.value = false }
            }
        }
    }

    private suspend fun runLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            Log.e(TAG, "Bad AudioRecord buffer size: $minBuffer")
            return
        }
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING,
            maxOf(minBuffer, OwwFeatures.CHUNK * 2) * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise (mic busy?)")
            record.release()
            return
        }
        val features = OwwFeatures(::mel, ::embed, featureFrames)
        val buf = ShortArray(OwwFeatures.CHUNK)
        var hit = false
        try {
            record.startRecording()
            Log.i(TAG, "Listening for \"$PHRASE\"")
            while (coroutineContext.isActive && _isListening.value && !hit) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) {
                    Log.e(TAG, "AudioRecord read error: $n")
                    return
                }
                features.accept(buf, n) { window ->
                    val score = classify(window)
                    if (score >= threshold) {
                        Log.i(TAG, "Wake word hit, score %.2f".format(score))
                        hit = true
                    }
                }
            }
        } finally {
            record.stop()
            record.release()
        }
        if (hit) {
            // Release the mic BEFORE STT opens it (single audio consumer).
            withContext(Dispatchers.Main) {
                _isListening.value = false
                onWakeWordCallback?.invoke(PHRASE)
            }
        }
    }

    private fun mel(samples: FloatArray): List<FloatArray> =
        tensor(samples, longArrayOf(1, samples.size.toLong())).use { x ->
            melSession.run(mapOf("input" to x)).use { out ->
                val fb = (out.get(0) as OnnxTensor).floatBuffer
                List(fb.remaining() / OwwFeatures.MEL_BINS) {
                    FloatArray(OwwFeatures.MEL_BINS) { fb.get() / 10f + 2f }
                }
            }
        }

    private fun embed(window: List<FloatArray>): FloatArray {
        val flat = FloatArray(OwwFeatures.MEL_WINDOW * OwwFeatures.MEL_BINS)
        window.forEachIndexed { i, row -> row.copyInto(flat, i * OwwFeatures.MEL_BINS) }
        val shape = longArrayOf(1, OwwFeatures.MEL_WINDOW.toLong(), OwwFeatures.MEL_BINS.toLong(), 1)
        return tensor(flat, shape).use { x ->
            embedSession.run(mapOf("input_1" to x)).use { out ->
                val fb = (out.get(0) as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }

    private fun classify(window: List<FloatArray>): Float {
        val dim = window.first().size
        val flat = FloatArray(window.size * dim)
        window.forEachIndexed { i, e -> e.copyInto(flat, i * dim) }
        return tensor(flat, longArrayOf(1, window.size.toLong(), dim.toLong())).use { x ->
            wakeSession.run(mapOf(wakeInput to x)).use { out ->
                (out.get(0) as OnnxTensor).floatBuffer.get(0)
            }
        }
    }

    private fun tensor(data: FloatArray, shape: LongArray) =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    override fun stop() {
        _isListening.value = false
        // Cancel only: the mic is released in the loop's finally, and release() still joins the job.
        listeningJob?.cancel()
    }

    override fun setOnWakeWordDetected(callback: (String) -> Unit) {
        onWakeWordCallback = callback
    }

    override fun release() {
        stop()
        onWakeWordCallback = null
        val loop = listeningJob
        scope.launch {
            val finished = loop == null || withTimeoutOrNull(RELEASE_WAIT_MS) { loop.join() } != null
            if (finished) {
                listOf(melSession, embedSession, wakeSession).forEach { runCatching { it.close() } }
            } else {
                Log.w(TAG, "Loop still running after ${RELEASE_WAIT_MS} ms; leaking sessions instead of crashing")
            }
        }
    }

    companion object {
        private const val TAG = "OpenWakeWord"
        const val PHRASE = "Hey AURA"
        private const val ASSET_DIR = "wakeword"
        private const val CLASSIFIER = "hey_aura.onnx"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val RELEASE_WAIT_MS = 2_000L
    }
}
