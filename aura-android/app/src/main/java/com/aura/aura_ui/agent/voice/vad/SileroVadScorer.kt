package com.aura.aura_ui.agent.voice.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 (assets/silero_vad.onnx, MIT) — raw 512-sample 16 kHz PCM in,
 * speech probability out, recurrent state threaded between calls.
 *
 * Shares the process-wide [OrtEnvironment] with OnnxYoloRunner (multi-session
 * by design) but pins its own session to CPU with ONE intra-op thread so it
 * can never contend with the YOLO provider chosen by calibration. Never touches
 * ExecutionProviderStore.
 */
class SileroVadScorer(context: Context) : FrameScorer, AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private var state = FloatArray(STATE_SIZE) // [2,1,128] flattened, zeros = fresh

    init {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        session = env.createSession(bytes, opts)
        Log.i(TAG, "Silero VAD session ready (${bytes.size} bytes, CPU/1-thread)")
    }

    override fun score(frame: FloatArray): Float {
        require(frame.size == SpeechSegmenter.FRAME_SAMPLES) { "expected 512 samples" }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), longArrayOf(1, 512)).use { input ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { st ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf()).use { sr ->
                    session.run(mapOf("input" to input, "state" to st, "sr" to sr)).use { result ->
                        // Access outputs by name — never trust graph ordering.
                        val prob = (result.get("output").get() as OnnxTensor).floatBuffer[0]
                        (result.get("stateN").get() as OnnxTensor).floatBuffer.get(state)
                        return prob
                    }
                }
            }
        }
    }

    override fun reset() {
        state = FloatArray(STATE_SIZE)
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "SileroVad"
        private const val MODEL_ASSET = "silero_vad.onnx"
        private const val STATE_SIZE = 2 * 1 * 128
    }
}
