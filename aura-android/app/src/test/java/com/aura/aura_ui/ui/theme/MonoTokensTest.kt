package com.aura.aura_ui.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class MonoTokensTest {
    @Test
    fun `spacing scale has expected values`() {
        assertEquals(4.dp, Mono.Space.xs)
        assertEquals(8.dp, Mono.Space.sm)
        assertEquals(12.dp, Mono.Space.md)
        assertEquals(16.dp, Mono.Space.lg)
        assertEquals(24.dp, Mono.Space.xl)
        assertEquals(32.dp, Mono.Space.xxl)
    }

    @Test
    fun `standard screen horizontal padding is 24dp`() {
        assertEquals(24.dp, Mono.ScreenH)
    }
}
