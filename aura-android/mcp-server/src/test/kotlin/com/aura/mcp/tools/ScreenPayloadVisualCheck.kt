package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import kotlin.test.Test

/** Temporary: eyeball the payload. Deleted after inspection. */
class ScreenPayloadVisualCheck {
    private var n = 0
    private fun el(
        x1: Int, y1: Int, x2: Int, y2: Int, label: String,
        click: Boolean = true, scroll: Boolean = false, edit: Boolean = false,
    ) = DetectedElement(
        somId = ++n, bbox = BBox(x1, y1, x2, y2), elementType = "View",
        label = label, confidence = 1f, interactive = click, scrollable = scroll, editable = edit,
    )

    @Test
    fun show() {
        val els = listOf(
            el(0, 0, 1240, 2772, "", click = false),           // root frame
            el(0, 0, 1240, 120, "", click = false),            // top bar wrapper
            el(30, 30, 150, 100, "Back"),
            el(1080, 20, 1220, 100, "Cart"),
            el(60, 160, 1180, 280, "Search for restaurants", edit = true),
            el(0, 320, 1240, 400, "Offers near you", click = false),
            el(40, 420, 600, 900, "Chicken Biryani 249"),
            el(40, 420, 600, 900, ""),
            el(640, 420, 1200, 900, "Paneer Tikka 219"),
            el(40, 940, 1200, 1400, "", scroll = true, click = false),
            el(80, 980, 700, 1060, "Top rated near you", click = false),
            el(80, 1100, 560, 1360, "Biryani House 4.5"),
            el(600, 1100, 1160, 1360, "Pizza Point 4.2"),
            el(0, 2600, 1240, 2772, "", click = false),
            el(60, 2630, 300, 2750, "Home"),
            el(500, 2630, 740, 2750, "Search"),
            el(940, 2630, 1180, 2750, "Account"),
        )
        println("\n" + ScreenPayload.render(els, "in.swiggy.android", idle = true, settled = true)!!.text + "\n")
    }
}
