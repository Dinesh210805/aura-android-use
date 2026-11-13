package com.aura.aura_ui.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AuraSplashTest {

    @Test
    fun `the splash opens on an empty black frame`() {
        val f = splashFrame(0f)
        assertEquals(0f, f.draw, 0f)
        assertEquals(0f, f.fill, 0f)
        assertEquals(0f, f.sparkAlpha, 0f)
        assertEquals(0f, f.haloAlpha, 0f)
        assertEquals(0f, f.word, 0f)
    }

    @Test
    fun `the splash ends on the solid mark at full size with every light effect gone`() {
        val f = splashFrame(SPLASH_END_S)
        assertEquals(1f, f.draw, 0f)
        assertEquals(1f, f.drawIn, 0f)
        assertEquals(1f, f.fill, 0f)
        assertEquals(1f, f.scale, 1e-3f)
        assertEquals(0f, f.traceAlpha, 0f)
        assertEquals(0f, f.sparkAlpha, 0f)
        assertEquals(0f, f.sheenAlpha, 0f)
        assertEquals(1f, f.word, 1e-3f)
    }

    @Test
    fun `the hole's light line starts after the outline's and both close together`() {
        assertEquals(0f, splashFrame(0.3f).drawIn, 0f)
        assert(splashFrame(0.3f).draw > 0f)
        assertEquals(1f, splashFrame(1.45f).draw, 0f)
        assertEquals(1f, splashFrame(1.45f).drawIn, 0f)
    }
}
