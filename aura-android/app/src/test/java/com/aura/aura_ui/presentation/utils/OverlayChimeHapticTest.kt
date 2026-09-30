package com.aura.aura_ui.presentation.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the overlay-open chime haptic profile. No Vibrator, no Android —
 * plain JVM (`./gradlew app:test`).
 *
 * These lock the *felt shape* of the cue against the notification waveform:
 * a sharp double-attack up front, then a strictly decaying tail.
 */
class OverlayChimeHapticTest {

    @Test
    fun `profile starts at full strength`() {
        val steps = buildOverlayChimeSteps()
        assertTrue("expected at least a double-attack + tail", steps.size >= 3)
        assertEquals("first hit must be full strength", 1.0f, steps.first().amplitude, 0.0001f)
        assertEquals("first hit has no lead-in gap", 0, steps.first().gapMs)
    }

    @Test
    fun `second hit follows quickly — the double-attack`() {
        val second = buildOverlayChimeSteps()[1]
        assertTrue(
            "second attack must land within ~50ms to feel like one double-hit (was ${second.gapMs}ms)",
            second.gapMs in 1..50,
        )
        assertTrue("second attack still strong", second.amplitude >= 0.6f)
    }

    @Test
    fun `strength strictly decays across the profile — the fading tail`() {
        val amps = buildOverlayChimeSteps().map { it.amplitude }
        for (i in 1 until amps.size) {
            assertTrue(
                "amplitude must strictly decay: ${amps[i]} !< ${amps[i - 1]}",
                amps[i] < amps[i - 1],
            )
        }
    }

    @Test
    fun `waveform fallback alternates silence and pulses, amplitudes in range`() {
        val (timings, amplitudes) = buildOverlayChimeWaveform()
        assertEquals("timings and amplitudes must be paired", timings.size, amplitudes.size)
        assertEquals("two entries (silence + pulse) per step", buildOverlayChimeSteps().size * 2, timings.size)

        // Even indices are the silence gaps (amplitude 0); odd indices are the pulses.
        for (i in amplitudes.indices) {
            if (i % 2 == 0) {
                assertEquals("silence entry $i must be zero amplitude", 0, amplitudes[i])
            } else {
                assertTrue("pulse entry $i must be a real amplitude", amplitudes[i] in 1..255)
            }
        }
    }

    @Test
    fun `waveform pulse amplitudes decay like the profile`() {
        val (_, amplitudes) = buildOverlayChimeWaveform()
        val pulses = amplitudes.filterIndexed { index, _ -> index % 2 == 1 }
        for (i in 1 until pulses.size) {
            assertTrue("pulse amplitudes must decay: ${pulses[i]} !< ${pulses[i - 1]}", pulses[i] < pulses[i - 1])
        }
    }
}
