package com.aura.aura_ui.uistream

import com.aura.mcp.cache.ScreenActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenIdleTest {

    @Before
    fun reset() = ScreenIdle.resetForTest()

    @Test
    fun `ambient events keep the screen busy`() {
        // A spinner emits only AMBIENT events. ScreenActivity deliberately ignores
        // those ("nothing CHANGED"), but the screen is plainly still moving, so idle
        // must say no — this is the distinction the two classes exist to keep apart.
        ScreenIdle.onEvent(ScreenActivity.Class.AMBIENT, 1_000)
        assertFalse(ScreenIdle.isIdle(1_100, quietMs = 350))
        assertTrue(ScreenIdle.isIdle(1_400, quietMs = 350))
    }

    @Test
    fun `meaningful events also stamp the meaningful clock`() {
        ScreenIdle.onEvent(ScreenActivity.Class.AMBIENT, 1_000)
        assertEquals(0L, ScreenIdle.lastMeaningfulMs)
        ScreenIdle.onEvent(ScreenActivity.Class.STRUCTURAL, 2_000)
        assertEquals(2_000L, ScreenIdle.lastMeaningfulMs)
        assertEquals(2_000L, ScreenIdle.lastEventMs)
    }

    @Test
    fun `quiet time is measured from the newest event of any class`() {
        ScreenIdle.onEvent(ScreenActivity.Class.STRUCTURAL, 1_000)
        ScreenIdle.onEvent(ScreenActivity.Class.AMBIENT, 1_500)
        assertEquals(200L, ScreenIdle.quietForMs(1_700))
    }

    @Test
    fun `idle exactly at the threshold counts as idle`() {
        ScreenIdle.onEvent(ScreenActivity.Class.AMBIENT, 1_000)
        assertTrue(ScreenIdle.isIdle(1_350, quietMs = 350))
    }
}

class UiStreamFrameTest {

    private fun element(
        l: Int, t: Int, r: Int, b: Int,
        text: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        enabled: Boolean = true,
    ) = mapOf(
        "text" to text,
        "contentDescription" to "",
        "className" to "android.widget.TextView",
        "bounds" to mapOf("left" to l, "top" to t, "right" to r, "bottom" to b),
        "isClickable" to clickable,
        "isScrollable" to false,
        "isEditable" to editable,
        "isCheckable" to checkable,
        "isChecked" to checked,
        "isLongClickable" to false,
        "isEnabled" to enabled,
    )

    private fun tree(vararg els: Map<String, Any>) = mapOf(
        "package_name" to "com.example",
        "screen_width_px" to 1240,
        "screen_height_px" to 2772,
        "truncated" to false,
        "elements" to els.toList(),
    )

    @Test
    fun `frame carries idle, geometry and a positional element array`() {
        val json = JSONObject(
            UiStreamFrame.of(
                tree(element(10, 20, 110, 60, text = "Send", clickable = true)),
                seq = 7, nowMs = 1_234, idle = true, quietMs = 900, changed = true,
            )!!,
        )
        assertEquals("frame", json.getString("type"))
        assertEquals(7, json.getInt("seq"))
        assertTrue(json.getBoolean("idle"))
        assertEquals(900, json.getInt("quiet_ms"))
        assertEquals("com.example", json.getString("pkg"))
        assertEquals(1240, json.getInt("w"))
        assertEquals(1, json.getInt("n"))

        val e = json.getJSONArray("e").getJSONArray(0)
        assertEquals(10, e.getInt(0))
        assertEquals(20, e.getInt(1))
        assertEquals(110, e.getInt(2))
        assertEquals(60, e.getInt(3))
        assertEquals("*", e.getString(4))
        assertEquals("Send", e.getString(5))
        assertEquals("TextView", e.getString(6))
    }

    @Test
    fun `som_id is the array index so nothing may be filtered downstream`() {
        val json = JSONObject(
            UiStreamFrame.of(
                tree(
                    element(0, 0, 10, 10, text = "first"),
                    element(0, 20, 10, 30, text = "second"),
                ),
                seq = 1, nowMs = 0, idle = false, quietMs = 0, changed = true,
            )!!,
        )
        val e = json.getJSONArray("e")
        assertEquals("first", e.getJSONArray(0).getString(5))   // som_id 1
        assertEquals("second", e.getJSONArray(1).getString(5))  // som_id 2
    }

    @Test
    fun `degenerate bounds are dropped, and dropping them renumbers`() {
        val json = JSONObject(
            UiStreamFrame.of(
                tree(
                    element(5, 5, 5, 5, text = "collapsed"),   // zero area
                    element(0, 0, 10, 10, text = "real"),
                ),
                seq = 1, nowMs = 0, idle = true, quietMs = 0, changed = false,
            )!!,
        )
        assertEquals(1, json.getInt("n"))
        assertEquals("real", json.getJSONArray("e").getJSONArray(0).getString(5))
    }

    @Test
    fun `flags encode state, and checked beats unchecked`() {
        fun flagsOf(e: Map<String, Any>) = JSONObject(
            UiStreamFrame.of(tree(e), 1, 0, true, 0, true)!!,
        ).getJSONArray("e").getJSONArray(0).getString(4)

        assertEquals("e", flagsOf(element(0, 0, 9, 9, text = "x", editable = true)))
        assertEquals("c", flagsOf(element(0, 0, 9, 9, text = "x", checkable = true, checked = true)))
        assertEquals("o", flagsOf(element(0, 0, 9, 9, text = "x", checkable = true)))
        assertEquals("d", flagsOf(element(0, 0, 9, 9, text = "x", enabled = false)))
        assertEquals("", flagsOf(element(0, 0, 9, 9, text = "x")))
    }

    @Test
    fun `a tree with no elements key yields null, not an empty frame`() {
        // The caller must be able to tell "could not look" from "nothing there";
        // a silent empty frame would read as an empty screen.
        assertNull(UiStreamFrame.of(mapOf("package_name" to "x"), 1, 0, true, 0, true))
    }

    @Test
    fun `stale frame states why`() {
        val json = JSONObject(UiStreamFrame.stale(3, 99, idle = false, quietMs = 10, reason = "boom"))
        assertEquals("stale", json.getString("type"))
        assertEquals("boom", json.getString("reason"))
        assertEquals(3, json.getInt("seq"))
        assertFalse(json.getBoolean("idle"))
    }

    @Test
    fun `hello describes the schema so a consumer can self-configure`() {
        val json = JSONObject(UiStreamFrame.hello(quietMs = 350, intervalMs = 250))
        assertEquals("hello", json.getString("type"))
        assertEquals(UiStreamFrame.VERSION, json.getInt("v"))
        assertEquals(350, json.getInt("quiet_ms_threshold"))
        assertEquals(7, json.getJSONArray("element_shape").length())
    }
}
