package com.aura.aura_ui.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Streaming math of [OwwFeatures], with fake models: a wrong window or offset makes a trained classifier silently deaf. */
class OwwFeaturesTest {

    private val melInputs = mutableListOf<FloatArray>()
    private val embedInputs = mutableListOf<List<FloatArray>>()
    private var embedCount = 0f

    private fun features(frames: Int = 3) = OwwFeatures(
        mel = { samples -> melInputs += samples; List(8) { FloatArray(OwwFeatures.MEL_BINS) { 5f } } },
        embed = { window -> embedInputs += window; FloatArray(96) { ++embedCount } },
        featureFrames = frames,
    )

    private fun pcm(n: Int, start: Int = 0) = ShortArray(n) { (start + it).toShort() }

    @Test
    fun `mel sees 480 samples of context plus the 1280-sample chunk, as raw int16 values`() {
        val f = features()
        f.accept(pcm(1280), 1280) {}
        f.accept(pcm(1280, 1280), 1280) {}
        assertEquals(2, melInputs.size)
        assertEquals(1760, melInputs[0].size)
        assertEquals(0f, melInputs[0][479]) // no history before the first chunk
        assertEquals(1279f, melInputs[0][1759])
        assertEquals(800f, melInputs[1][0]) // previous chunk's last 480 samples (800..1279)
        assertEquals(1280f, melInputs[1][480])
    }

    @Test
    fun `embedding window is the newest 76 mel frames, padded with ones at start`() {
        val f = features()
        f.accept(pcm(1280), 1280) {}
        val w = embedInputs.single()
        assertEquals(OwwFeatures.MEL_WINDOW, w.size)
        assertEquals(1f, w[0][0])
        assertEquals(5f, w[75][0])
        assertEquals(1f, w[67][0]) // 68 ones then 8 new frames
    }

    @Test
    fun `emits only after featureFrames embeddings, then once per chunk, oldest first`() {
        val f = features(frames = 3)
        val windows = mutableListOf<List<FloatArray>>()
        // Odd read sizes: chunking must not depend on how the mic splits audio.
        val audio = pcm(1280 * 5)
        var i = 0
        while (i < audio.size) {
            val n = minOf(700, audio.size - i)
            f.accept(audio.copyOfRange(i, i + n), n) { windows += it }
            i += n
        }
        assertEquals(3, windows.size) // chunks 3, 4, 5
        assertTrue(windows.all { it.size == 3 })
        assertEquals(listOf(1f, 97f, 193f), windows[0].map { it[0] })
        assertEquals(listOf(97f, 193f, 289f), windows[1].map { it[0] })
    }

    @Test
    fun `reset restarts warm-up`() {
        val f = features(frames = 2)
        var emitted = 0
        f.accept(pcm(1280 * 2), 1280 * 2) { emitted++ }
        assertEquals(1, emitted)
        f.reset()
        f.accept(pcm(1280), 1280) { emitted++ }
        assertEquals(1, emitted)
    }
}
