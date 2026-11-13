package com.aura.aura_ui.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text must stay readable in both themes, on every sky the clock can produce. */
class AuraThemeTest {

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun `classic text keeps 4_5 to 1 on its canvas and cards`() {
        for (dark in listOf(false, true)) {
            val c = auraColors(dark, AccentChoice.DEFAULT, StyleChoice.CLASSIC)
            for (ground in listOf(c.canvas, c.surface)) {
                for (text in listOf(c.label, c.secondary, c.onSky, c.onSkyMuted)) {
                    assertTrue("dark=$dark $text on $ground", contrast(text, ground) >= 4.5f)
                }
            }
        }
    }

    @Test
    fun `sky header text is large-text readable on the top of every sky`() {
        for (sky in SkyPalette.ALL) {
            val ratio = contrast(sky.header, sky.stops.first())
            assertTrue("${sky.header} on ${sky.stops.first()} is $ratio:1", ratio >= 3f)
        }
    }

    @Test
    fun `sky card text keeps 4_5 to 1 on glass over the darkest sky`() {
        val c = auraColors(true, AccentChoice.DEFAULT, StyleChoice.SKY, SkyPalette.at(12.0, dark = true))
        val card = c.surface.compositeOver(Color(0xFF03050A))
        for (text in listOf(c.label, c.secondary)) assertTrue("$text on $card", contrast(text, card) >= 4.5f)
    }

    @Test
    fun `the sky follows the clock and holds steady inside a keyframe span`() {
        assertEquals(SkyPalette.at(10.0, false), SkyPalette.at(15.0, false))
        assertTrue(SkyPalette.at(7.0, false).darkText) // dawn: ink header
        assertTrue(!SkyPalette.at(12.0, false).darkText) // day: white header
    }

    @Test
    fun `every accent keeps its text readable`() {
        for (dark in listOf(false, true)) {
            for (accent in AccentChoice.entries) {
                val ratio = contrast(accent.on(dark), accent.color(dark))
                assertTrue("${accent.name} (dark=$dark) is $ratio:1", ratio >= 4.5f)
            }
        }
    }
}
