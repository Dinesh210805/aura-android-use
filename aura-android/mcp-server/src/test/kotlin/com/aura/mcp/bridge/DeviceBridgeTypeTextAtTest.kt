package com.aura.mcp.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for the coordinate-aware typing seam (spec 2026-07-20).
 *
 * `typeTextAt` is a DEFAULTED interface method so the many existing `FakeDeviceBridge`
 * test doubles that only implement `typeText` keep compiling. These tests pin that
 * default (delegates to `typeText`) and that a real override receives the coordinates.
 */
class DeviceBridgeTypeTextAtTest {

    /** Legacy-style double: implements only the required members, never overrides typeTextAt. */
    private open class LegacyBridge : DeviceBridge {
        var typedText: String? = null
        override fun performTap(x: Int, y: Int) = true
        override fun performHome() = true
        override fun performBack() = true
        override fun adjustVolume(direction: VolumeDirection) = true
        override fun getDeviceStatus() = DeviceStatus(true, 1080, 2400, 34, "test")
        override fun performDoubleTap(x: Int, y: Int) = true
        override fun performLongPress(x: Int, y: Int, durationMs: Long) = true
        override fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
        override fun performScroll(direction: ScrollDirection) = true
        override fun performRecents() = true
        override fun pressEnter() = true
        override fun typeText(text: String): Boolean { typedText = text; return true }
        override fun restoreKeyboard() = true
        override fun launchApp(packageName: String) = true
        override fun lookupApp(query: String) = AppLookupResult(false, "", "", emptyList(), null)
    }

    /** Real binding style: overrides typeTextAt to receive coordinates. */
    private class CoordBridge : LegacyBridge() {
        var captured: Triple<String, Int, Int>? = null
        override fun typeTextAt(text: String, x: Int, y: Int): Boolean {
            captured = Triple(text, x, y)
            return true
        }
    }

    @Test
    fun `default typeTextAt delegates to typeText`() {
        val bridge = LegacyBridge()
        val ok = bridge.typeTextAt("hello", x = 100, y = 200)
        assertTrue(ok)
        assertEquals("hello", bridge.typedText)
    }

    @Test
    fun `override receives the coordinates`() {
        val bridge = CoordBridge()
        assertNull(bridge.captured)
        bridge.typeTextAt("query", x = 540, y = 960)
        assertEquals(Triple("query", 540, 960), bridge.captured)
    }
}
