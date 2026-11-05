package com.aura.aura_ui.presentation.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScaleTest {

    @Test
    fun `tall phone grows the home, short phone shrinks it, both clamped`() {
        assertEquals(1f, homeScale(bodyHeightDp = 620f, widthDp = 360f), 0.001f)
        assertEquals(1.29f, homeScale(bodyHeightDp = 800f, widthDp = 360f), 0.01f)
        assertEquals(1.3f, homeScale(bodyHeightDp = 2000f, widthDp = 600f), 0.001f)
        assertEquals(0.8f, homeScale(bodyHeightDp = 300f, widthDp = 360f), 0.001f)
    }

    @Test
    fun `a narrow screen caps the scale so marquee icons never collide`() {
        assertEquals(1.12f, homeScale(bodyHeightDp = 900f, widthDp = 280f), 0.001f)
    }
}
