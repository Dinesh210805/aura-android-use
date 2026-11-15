package com.aura.aura_ui.voice

/**
 * openWakeWord's streaming feature pipeline: 16 kHz PCM in, a window of 96-d speech embeddings
 * out every 80 ms. Port of `AudioFeatures._streaming_features` in openWakeWord's `utils.py`.
 *
 * - Contract: [accept] calls `onWindow` once per completed 1280-sample chunk, with the newest
 *   [featureFrames] embeddings (oldest first), but only once that many real embeddings exist.
 *   Not thread-safe; one detection loop owns it.
 * - Why the 480-sample prefix: the reference computes each chunk's mel over the chunk plus the
 *   3 hops (160 × 3) before it, which yields exactly 8 mel frames per 1280 samples.
 * - Why the mel buffer starts as 76 rows of 1.0: the reference does the same, and trained
 *   classifiers expect the first embeddings to be padded that way.
 * - Why there is no warm-up noise: the reference seeds the embedding buffer with random noise and
 *   zeroes the first 5 scores. Waiting for [featureFrames] real embeddings (~1.3 s) does the same
 *   job without fake data.
 * - Change together: [OpenWakeWordDetector], which supplies `mel` and `embed`.
 *
 * @param mel PCM samples as int16-valued floats (NOT normalised to ±1) → mel frames of 32
 *   floats, with `x / 10 + 2` already applied.
 * @param embed exactly [MEL_WINDOW] mel frames → one 96-float embedding.
 */
internal class OwwFeatures(
    private val mel: (FloatArray) -> List<FloatArray>,
    private val embed: (List<FloatArray>) -> FloatArray,
    private val featureFrames: Int,
) {
    private val raw = FloatArray(CONTEXT + CHUNK) // previous 480 samples ++ current chunk
    private var filled = 0
    private val mels = ArrayDeque<FloatArray>()
    private val feats = ArrayDeque<FloatArray>()

    init {
        reset()
    }

    fun reset() {
        raw.fill(0f)
        filled = 0
        mels.clear()
        repeat(MEL_WINDOW) { mels.addLast(FloatArray(MEL_BINS) { 1f }) }
        feats.clear()
    }

    fun accept(pcm: ShortArray, n: Int, onWindow: (List<FloatArray>) -> Unit) {
        for (i in 0 until n) {
            raw[CONTEXT + filled++] = pcm[i].toFloat()
            if (filled == CHUNK) {
                processChunk()?.let(onWindow)
                raw.copyInto(raw, 0, CHUNK, CONTEXT + CHUNK) // last 480 become the next prefix
                filled = 0
            }
        }
    }

    private fun processChunk(): List<FloatArray>? {
        mel(raw.copyOf()).forEach { mels.addLast(it) }
        while (mels.size > MEL_WINDOW) mels.removeFirst()
        feats.addLast(embed(mels.toList()))
        while (feats.size > featureFrames) feats.removeFirst()
        return if (feats.size == featureFrames) feats.toList() else null
    }

    companion object {
        const val CHUNK = 1280 // 80 ms at 16 kHz
        const val CONTEXT = 480 // 160 × 3
        const val MEL_WINDOW = 76
        const val MEL_BINS = 32
    }
}
