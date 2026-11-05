package com.aura.aura_ui.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/**
 * On-device speech-to-text (Moonshine via sherpa-onnx): one finished utterance of PCM in, text out.
 *
 * - Contract: blocking; call off the main thread. One process-wide recognizer, loaded by [preload]
 *   or the first [transcribe] and kept. Thread-safe (calls are serialized).
 * - Fails: throws when the model isn't downloaded or fails to load.
 * - Reads: `LocalVoiceModels.MOONSHINE` files.
 */
object MoonshineStt {
    private const val TAG = "MoonshineStt"
    private const val SAMPLE_RATE = 16000

    private var recognizer: OfflineRecognizer? = null

    fun isAvailable(context: Context): Boolean = LocalVoiceModels.isReady(context, LocalVoiceModels.MOONSHINE)

    /** Load the model ahead of the first utterance (~3 s). Blocking; call off the main thread. */
    @Synchronized
    fun preload(context: Context) {
        if (recognizer != null || !isAvailable(context)) return
        runCatching { recognizer = load(context) }.onFailure { Log.e(TAG, "Moonshine preload failed", it) }
    }

    /** Transcribe little-endian 16-bit mono PCM at 16 kHz. */
    @Synchronized
    fun transcribe(context: Context, pcm16: ByteArray): String {
        val rec = recognizer ?: load(context).also { recognizer = it }
        val samples = FloatArray(pcm16.size / 2) { i ->
            ((pcm16[2 * i].toInt() and 0xff) or (pcm16[2 * i + 1].toInt() shl 8)) / 32768f
        }
        val started = System.currentTimeMillis()
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            Log.i(TAG, "${samples.size / SAMPLE_RATE.toFloat()}s audio → ${System.currentTimeMillis() - started} ms: ${text.take(80)}")
            return text
        } finally {
            stream.release()
        }
    }

    private fun load(context: Context): OfflineRecognizer {
        check(isAvailable(context)) { "Speech model not downloaded — get it in Settings › Voice." }
        val d = LocalVoiceModels.dir(context, LocalVoiceModels.MOONSHINE).absolutePath
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = "$d/preprocess.onnx",
                    encoder = "$d/encode.int8.onnx",
                    uncachedDecoder = "$d/uncached_decode.int8.onnx",
                    cachedDecoder = "$d/cached_decode.int8.onnx",
                ),
                tokens = "$d/tokens.txt",
                numThreads = 4,
            ),
        )
        return OfflineRecognizer(assetManager = null, config = config)
    }
}
