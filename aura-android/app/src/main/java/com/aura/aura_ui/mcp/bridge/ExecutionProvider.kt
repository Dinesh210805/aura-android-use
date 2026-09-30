package com.aura.aura_ui.mcp.bridge

/**
 * ONNX Runtime execution providers we calibrate among on-device.
 *
 * The order of declaration is also the **tie-break preference** used by
 * [chooseBestProvider] (earlier = preferred when latencies are equal): try a
 * real accelerator path first, keep plain CPU as the last resort.
 *
 * All three are compiled into the standard `onnxruntime-android` package (no extra
 * dependency). Order is the tie-break preference (earlier = preferred): try a real
 * accelerator path first, keep plain CPU as the last resort.
 *
 * QNN (Qualcomm Hexagon NPU) was evaluated and intentionally left out: it needs the
 * `onnxruntime-android-qnn` artifact, which DROPS NNAPI + XNNPACK; and on a
 * Snapdragon 7+ Gen 3 the NPU only tied CPU (~400 ms) for this small INT8 model — no
 * speedup to justify the cost. To revisit, add a `QNN` case + a `buildSessionOptions`
 * branch (`opts.addQnn(mapOf("backend_path" to "libQnnHtp.so", ...))`) and swap the
 * dependency. See docs/perceive-bench/REPORT.md §11.
 *
 * This file is deliberately **free of any `ai.onnxruntime` import** so the pure
 * [chooseBestProvider] logic is unit-testable on the JVM, where ORT's native
 * library cannot load.
 */
enum class ExecutionProvider {
    XNNPACK,
    NNAPI,
    CPU,
}

/**
 * Pick the fastest execution provider from a map of measured median inference
 * latencies (ms). The map only contains EPs that **successfully** built a
 * session and ran — an EP that threw (unsupported on this device, rejected the
 * quantized graph, etc.) is simply absent, and thus never chosen.
 *
 * Rules:
 *  - empty map (every candidate failed) → [ExecutionProvider.CPU], the always-
 *    available baseline the default ORT session provides.
 *  - otherwise the minimum latency wins.
 *  - on an exact tie, the [ExecutionProvider] declared earlier wins (CPU last),
 *    so we never prefer plain CPU over an accelerator that matched it.
 */
fun chooseBestProvider(results: Map<ExecutionProvider, Long>): ExecutionProvider {
    if (results.isEmpty()) return ExecutionProvider.CPU
    val best = results.values.min()
    // Among everything tied at the minimum, prefer the earliest-declared EP.
    return results.entries
        .filter { it.value == best }
        .minByOrNull { it.key.ordinal }!!
        .key
}
