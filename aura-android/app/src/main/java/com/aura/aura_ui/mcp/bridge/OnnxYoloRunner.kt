package com.aura.aura_ui.mcp.bridge

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.aura.mcp.bridge.BBox
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Runs Microsoft OmniParser `icon_detect` (YOLOv8) via ONNX Runtime.
 *
 * Model: 19.6 MB INT8-quantized ONNX shipped as an Android asset
 * (`assets/omniparser_icon_detect.onnx`). Output tensor shape (1, 5, 8400)
 * is the standard YOLOv8 detect head — 8400 anchors × (cx, cy, w, h, conf).
 *
 * The runner does:
 *   1. Letterbox the source Bitmap into 640×640 keeping aspect ratio
 *   2. CHW float32 normalization to [0, 1]
 *   3. Inference on the **per-device calibrated** execution provider (see
 *      [ensurePrepared]: XNNPACK / NNAPI / CPU, whichever benchmarked fastest)
 *   4. Confidence filter (default 0.25) + class-agnostic NMS (IoU 0.5)
 *   5. Inverse letterbox: scale boxes back to the source image coordinate space
 *
 * Class names come from `assets/omniparser_classes.json` — currently a single
 * class `"icon"`, but the parsing is generic.
 *
 * **Execution provider selection.** Rather than hardcode NNAPI (which silently
 * fell back to a slow CPU path for this INT8 model — ~2.4 s inference), the
 * serving [OrtSession] is built only after [ensurePrepared] either reads a
 * persisted per-device choice or benchmarks the candidates and persists the
 * winner. Call [ensurePrepared] at startup to pay session build + graph
 * compilation off the user-facing critical path; [detect] also calls it so the
 * in-process path works without a separate warm-up.
 *
 * Thread-safe: the serving [OrtSession] is built once behind a [Mutex]; ORT's
 * `Session.run` is itself thread-safe so concurrent [detect] calls are fine.
 */
class OnnxYoloRunner(
    private val appContext: Context,
    private val modelAssetName: String = "omniparser_icon_detect.onnx",
    private val classesAssetName: String = "omniparser_classes.json",
    private val inputSize: Int = 640,
    private val confThreshold: Float = 0.25f,
    private val iouThreshold: Float = 0.5f,
    private val maxDetections: Int = 50,
    private val providerStore: ExecutionProviderStore = ExecutionProviderStore(appContext),
) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /** Model bytes read once and reused across every candidate/serving session. */
    private val modelBytes: ByteArray by lazy {
        appContext.assets.open(modelAssetName).use { it.readBytes() }
            .also { Log.i(TAG, "Loaded ONNX model ($modelAssetName, ${it.size / 1024} KB)") }
    }

    // ── Prepared serving session (built once via ensurePrepared) ──────────────
    @Volatile private var activeSession: OrtSession? = null

    /** The execution provider currently serving inference; null until prepared. */
    @Volatile var activeProvider: ExecutionProvider? = null
        private set

    private val prepareMutex = Mutex()
    @Volatile private var prepared = false

    private val classNames: Map<Int, String> by lazy {
        val text = appContext.assets.open(classesAssetName).use { it.bufferedReader().readText() }
        // Trivial parser — file is `{"0": "icon", "1": ...}`. Avoid pulling in
        // a full JSON dep for one read at startup.
        val entries = text.trim().removePrefix("{").removeSuffix("}").trim()
        entries.split(",").mapNotNull { kv ->
            val parts = kv.split(":")
            if (parts.size != 2) return@mapNotNull null
            val k = parts[0].trim().trim('"').toIntOrNull() ?: return@mapNotNull null
            val v = parts[1].trim().trim('"').trim(',').trim()
            k to v
        }.toMap().also {
            Log.i(TAG, "Loaded ${it.size} class names: $it")
        }
    }

    // ── Execution-provider preparation & calibration ─────────────────────────

    /**
     * Build the serving session exactly once. Idempotent and concurrency-safe.
     *
     * Fast path: a persisted choice matching the current [calibrationSignature]
     * exists → build that EP's session (with a warm-up run) and return.
     *
     * Slow path (first launch / app+OS+model change): [calibrate] each candidate
     * EP, [chooseBestProvider], persist the winner, then build it. The per-EP
     * latency table is logged — it is the empirical proof of which provider
     * actually accelerates on this device.
     *
     * Safe to call from startup (warm-up) and from [detect]; later calls no-op.
     */
    suspend fun ensurePrepared() {
        if (prepared) return
        prepareMutex.withLock {
            if (prepared) return
            val signature = calibrationSignature()
            val chosen = providerStore.getChoice(signature) ?: run {
                val measured = calibrate()
                val winner = chooseBestProvider(measured)
                providerStore.setChoice(winner, signature)
                Log.i(TAG, "Calibration result (median ms): ${formatTable(measured)} → chosen=$winner")
                winner
            }
            val (session, provider) = runCatching { buildSession(chosen) to chosen }
                .getOrElse { t ->
                    // A persisted/chosen EP can fail to build if the device changed
                    // underneath us in a way the signature didn't catch. CPU is the
                    // always-available floor.
                    Log.w(TAG, "EP $chosen failed to build; falling back to CPU", t)
                    buildSession(ExecutionProvider.CPU) to ExecutionProvider.CPU
                }
            activeSession = session
            activeProvider = provider
            prepared = true
            Log.i(TAG, "Perception ready — serving on $provider")
        }
    }

    /** Benchmark every candidate EP; failures are excluded from the result map. */
    private fun calibrate(): Map<ExecutionProvider, Long> {
        val candidates = listOf(
            ExecutionProvider.XNNPACK,
            ExecutionProvider.NNAPI,
            ExecutionProvider.CPU,
        )
        val results = LinkedHashMap<ExecutionProvider, Long>()
        for (ep in candidates) {
            runCatching {
                buildSessionOptions(ep).use { opts ->
                    env.createSession(modelBytes, opts).use { s -> benchmarkSession(s) }
                }
            }.onSuccess { results[ep] = it }
                .onFailure { Log.w(TAG, "EP $ep unavailable/failed during calibration", it) }
        }
        return results
    }

    /** 1 warm-up run (pays this EP's graph compile) + median of 3 timed runs (ms). */
    private fun benchmarkSession(session: OrtSession): Long {
        fun once(): Long {
            val input = syntheticInput()
            OnnxTensor.createTensor(env, input, inputShape).use { tensor ->
                val start = System.currentTimeMillis()
                session.run(mapOf(session.inputNames.iterator().next() to tensor)).use { }
                return System.currentTimeMillis() - start
            }
        }
        once() // warm-up
        val samples = longArrayOf(once(), once(), once())
        samples.sort()
        return samples[1] // median of 3
    }

    /** Build the serving session for [ep] and warm it up (pays graph compile now). */
    private fun buildSession(ep: ExecutionProvider): OrtSession {
        val session = buildSessionOptions(ep).use { opts -> env.createSession(modelBytes, opts) }
        runCatching {
            val input = syntheticInput()
            OnnxTensor.createTensor(env, input, inputShape).use { tensor ->
                session.run(mapOf(session.inputNames.iterator().next() to tensor)).use { }
            }
        }.onFailure { Log.w(TAG, "Warm-up run failed for $ep (non-fatal)", it) }
        return session
    }

    /** Per-EP [OrtSession.SessionOptions]. The single place EP→config lives. */
    private fun buildSessionOptions(ep: ExecutionProvider): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        when (ep) {
            ExecutionProvider.XNNPACK -> {
                // ORT guidance: with XNNPACK, keep session intra-op threads at 1 and
                // let XNNPACK own its threadpool via the provider option.
                opts.setIntraOpNumThreads(1)
                opts.addXnnpack(mapOf("intra_op_num_threads" to perfThreadCount().toString()))
            }
            ExecutionProvider.NNAPI -> {
                opts.setIntraOpNumThreads(2)
                opts.addNnapi()
            }
            ExecutionProvider.CPU -> {
                opts.setIntraOpNumThreads(perfThreadCount())
            }
        }
        return opts
    }

    /** A neutral mid-gray CHW input. Inference time is content/size independent
     * (the real path letterboxes to [inputSize]²), so this is representative for
     * benchmarking AND needs no screen-capture permission at startup. */
    private fun syntheticInput(): FloatBuffer {
        val buf = FloatBuffer.allocate(3 * inputSize * inputSize)
        while (buf.hasRemaining()) buf.put(0.5f)
        buf.rewind()
        return buf
    }

    private val inputShape: LongArray
        get() = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())

    private fun perfThreadCount(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /** Invalidates a persisted choice on app update, OS update (NNAPI driver
     * change), or model/input-size change — the things that flip the winner. */
    private fun calibrationSignature(): String {
        val versionCode = runCatching {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION") info.versionCode.toString()
            }
        }.getOrDefault("0")
        return "$versionCode|${Build.FINGERPRINT}|$modelAssetName|$inputSize"
    }

    private fun formatTable(results: Map<ExecutionProvider, Long>): String =
        if (results.isEmpty()) "(all candidates failed)"
        else results.entries.joinToString(", ") { "${it.key}=${it.value}" }

    data class RawDetection(val bbox: BBox, val classId: Int, val confidence: Float)

    /**
     * Step-0 latency breakdown for one [detect] call (all milliseconds).
     *
     * The headline "yolo" number splits into stages we report separately:
     *
     *  - [sessionInitMs] — kept for payload-key stability. Session build + graph
     *    compilation now happen in [ensurePrepared] (at startup), so this is 0 in
     *    steady state rather than charging the first [detect] call.
     *  - [preprocessMs]  — letterbox + the scalar CHW float normalisation loop.
     *  - [inferenceMs]   — `session.run` only, on the calibrated execution
     *    provider. This is the dominant cost and the lever EP selection targets.
     *  - [postprocessMs] — pulling the raw tensor out of ORT (`result[0].value`,
     *    the boxed-array marshalling) + decode + NMS.
     *
     * [coldStart] — kept for key stability; always false now (cold cost is paid
     * in [ensurePrepared], not in [detect]).
     */
    data class DetectTimings(
        val sessionInitMs: Long,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val postprocessMs: Long,
        val coldStart: Boolean,
    )

    /** Detections plus the [DetectTimings] breakdown for this call. */
    data class DetectResult(
        val detections: List<RawDetection>,
        val timings: DetectTimings,
    )

    suspend fun detect(bitmap: Bitmap): DetectResult {
        val srcW = bitmap.width
        val srcH = bitmap.height

        // ── 0. Ensure the serving session exists. Session build + per-EP graph
        // compilation now happen in ensurePrepared (ideally at startup), so by
        // here they are paid: sessionInit/coldStart stay 0/false in steady state.
        // The timing fields are kept for payload-key stability. ──
        ensurePrepared()
        val session = activeSession ?: error("perception session not prepared")
        val sessionInitMs = 0L
        val coldStart = false

        // ── 1. Preprocess: letterbox into inputSize × inputSize, then CHW
        // float32 normalise to [0,1]. ──
        val preStart = System.currentTimeMillis()
        val scale = min(inputSize.toFloat() / srcW, inputSize.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()
        val padX = (inputSize - scaledW) / 2
        val padY = (inputSize - scaledH) / 2

        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        Canvas(letterboxed).apply {
            drawColor(Color.argb(255, 114, 114, 114))  // YOLO gray
            val src = Rect(0, 0, srcW, srcH)
            val dst = Rect(padX, padY, padX + scaledW, padY + scaledH)
            drawBitmap(bitmap, src, dst, null)
        }

        val input = FloatBuffer.allocate(3 * inputSize * inputSize)
        val pixels = IntArray(inputSize * inputSize)
        letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // R, G, B planes separately (CHW order)
        for (c in 0..2) {
            for (i in pixels.indices) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> (p shr 16) and 0xFF      // R
                    1 -> (p shr 8) and 0xFF       // G
                    else -> p and 0xFF            // B
                }
                input.put(v / 255f)
            }
        }
        input.rewind()
        val preprocessMs = System.currentTimeMillis() - preStart

        // ── 2. Inference (session.run) — timed apart from tensor marshalling so
        // a slow ORT-Java `.value` extraction can't masquerade as slow math. ──
        val inputName = session.inputNames.iterator().next()
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        var inferenceMs = 0L
        var postprocessMs = 0L
        val kept: List<RawDetection> = OnnxTensor.createTensor(env, input, shape).use { tensor ->
            val infStart = System.currentTimeMillis()
            session.run(mapOf(inputName to tensor)).use { result ->
                inferenceMs = System.currentTimeMillis() - infStart

                // ── 3. Postprocess: marshal raw tensor + decode + NMS ──
                val postStart = System.currentTimeMillis()
                val raw = result[0].value as Array<Array<FloatArray>>   // [1][5][8400]
                val rawDetections = decode(raw[0], padX, padY, scale, srcW, srcH)
                val nmsKept = nms(rawDetections.sortedByDescending { it.confidence }).take(maxDetections)
                postprocessMs = System.currentTimeMillis() - postStart
                nmsKept
            }
        }

        val timings = DetectTimings(
            sessionInitMs = sessionInitMs,
            preprocessMs = preprocessMs,
            inferenceMs = inferenceMs,
            postprocessMs = postprocessMs,
            coldStart = coldStart,
        )
        Log.i(
            TAG,
            "detect breakdown (ms): provider=$activeProvider preprocess=$preprocessMs " +
                "inference=$inferenceMs postprocess=$postprocessMs kept=${kept.size}",
        )
        return DetectResult(detections = kept, timings = timings)
    }

    /** Decode YOLOv8 raw output: [5][8400] → list of detections in source coords. */
    private fun decode(
        raw: Array<FloatArray>,
        padX: Int,
        padY: Int,
        scale: Float,
        srcW: Int,
        srcH: Int,
    ): List<RawDetection> {
        // raw[0]=cx, raw[1]=cy, raw[2]=w, raw[3]=h, raw[4..]=class confidences
        val numAnchors = raw[0].size
        val numClasses = raw.size - 4
        val out = mutableListOf<RawDetection>()

        for (i in 0 until numAnchors) {
            // Find best class
            var bestClassId = 0
            var bestConf = 0f
            for (c in 0 until numClasses) {
                val conf = raw[4 + c][i]
                if (conf > bestConf) {
                    bestConf = conf
                    bestClassId = c
                }
            }
            if (bestConf < confThreshold) continue

            val cx = raw[0][i]
            val cy = raw[1][i]
            val w = raw[2][i]
            val h = raw[3][i]
            val x1 = cx - w / 2
            val y1 = cy - h / 2
            val x2 = cx + w / 2
            val y2 = cy + h / 2

            // Inverse letterbox
            val srcX1 = ((x1 - padX) / scale).toInt().coerceIn(0, srcW - 1)
            val srcY1 = ((y1 - padY) / scale).toInt().coerceIn(0, srcH - 1)
            val srcX2 = ((x2 - padX) / scale).toInt().coerceIn(0, srcW - 1)
            val srcY2 = ((y2 - padY) / scale).toInt().coerceIn(0, srcH - 1)
            if (srcX2 <= srcX1 || srcY2 <= srcY1) continue

            out.add(RawDetection(BBox(srcX1, srcY1, srcX2, srcY2), bestClassId, bestConf))
        }
        return out
    }

    /** Class-agnostic NMS. */
    private fun nms(sorted: List<RawDetection>): List<RawDetection> {
        val kept = mutableListOf<RawDetection>()
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            kept.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (iou(sorted[i].bbox, sorted[j].bbox) > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return kept
    }

    private fun iou(a: BBox, b: BBox): Float {
        val interX1 = max(a.x1, b.x1)
        val interY1 = max(a.y1, b.y1)
        val interX2 = min(a.x2, b.x2)
        val interY2 = min(a.y2, b.y2)
        if (interX2 <= interX1 || interY2 <= interY1) return 0f
        val inter = (interX2 - interX1) * (interY2 - interY1)
        val union = a.area + b.area - inter
        return inter.toFloat() / union
    }

    fun classNameOf(classId: Int): String = classNames[classId] ?: "element"

    /** Use during shutdown; not strictly needed since ORT cleans up on process exit. */
    fun close() {
        runCatching { activeSession?.close() }
    }

    companion object {
        private const val TAG = "OnnxYoloRunner"
    }
}
