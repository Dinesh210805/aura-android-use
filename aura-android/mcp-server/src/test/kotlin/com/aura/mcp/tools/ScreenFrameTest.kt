package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `read_screen` wire format.
 *
 * Two invariants carry the whole feature and are pinned here: elements are
 * POSITIONAL (the token saving IS the absence of repeated key names), and a
 * som_id is nothing but the 1-based index into `e` — the same contract
 * `perceive_screen` publishes, so the two producers agree about what "12" means.
 */
class ScreenFrameTest {

    private fun element(
        somId: Int,
        label: String = "",
        type: String = "TextView",
        x1: Int = 0,
        y1: Int = 0,
        x2: Int = 100,
        y2: Int = 50,
        interactive: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        longClickable: Boolean = false,
        enabled: Boolean = true,
        checked: Boolean? = null,
    ) = DetectedElement(
        somId = somId,
        bbox = BBox(x1, y1, x2, y2),
        elementType = type,
        label = label,
        confidence = 1.0f,
        checked = checked,
        enabled = enabled,
        interactive = interactive,
        editable = editable,
        scrollable = scrollable,
        longClickable = longClickable,
    )

    private val meta = ScreenFrame.Meta(pkg = "com.example", widthPx = 1240, heightPx = 2772)

    private fun decode(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun elements(json: String): JsonArray = decode(json)["e"]!!.jsonArray

    private fun field(e: JsonArray, i: Int, at: Int) =
        e[i].jsonArray[at].jsonPrimitive.contentOrNull

    @Test
    fun `element is a positional array of bounds, label, flags, class`() {
        val frame = ScreenFrame.encode(
            elements = listOf(element(1, label = "Continue", type = "Button", x1 = 10, y1 = 20, x2 = 110, y2 = 70)),
            meta = meta,
            idle = true,
            settled = true,
        )
        val row = elements(frame)[0].jsonArray

        assertEquals(7, row.size)
        assertEquals(10, row[0].jsonPrimitive.intOrNull)
        assertEquals(20, row[1].jsonPrimitive.intOrNull)
        assertEquals(110, row[2].jsonPrimitive.intOrNull)
        assertEquals(70, row[3].jsonPrimitive.intOrNull)
        assertEquals("Continue", row[4].jsonPrimitive.contentOrNull)
        assertEquals("Button", row[6].jsonPrimitive.contentOrNull)
    }

    /**
     * The ordering that matters most. `perceive_screen`'s `e` is
     * [cx, cy, name, flags] — label BEFORE flags. Two positional orders in one
     * agent's context is a live misread risk, so this one matches it.
     */
    @Test
    fun `label comes before flags, matching perceive_screen`() {
        val frame = ScreenFrame.encode(
            elements = listOf(element(1, label = "Send", interactive = true)),
            meta = meta,
            idle = true,
            settled = true,
        )
        val e = elements(frame)
        assertEquals("Send", field(e, 0, 4))
        assertEquals("*", field(e, 0, 5))
    }

    @Test
    fun `flags encode every actionable property`() {
        val frame = ScreenFrame.encode(
            elements = listOf(
                element(1, interactive = true),
                element(2, editable = true),
                element(3, scrollable = true),
                element(4, checked = true),
                element(5, checked = false),
                element(6, enabled = false),
                element(7, longClickable = true),
                element(8),
            ),
            meta = meta,
            idle = true,
            settled = true,
        )
        val e = elements(frame)
        assertEquals("*", field(e, 0, 5))
        assertEquals("e", field(e, 1, 5))
        assertEquals("S", field(e, 2, 5))
        assertEquals("c", field(e, 3, 5))
        assertEquals("o", field(e, 4, 5))
        assertEquals("d", field(e, 5, 5))
        assertEquals("l", field(e, 6, 5))
        assertEquals("", field(e, 7, 5))
    }

    @Test
    fun `n matches the number of encoded elements`() {
        val frame = ScreenFrame.encode(
            elements = (1..5).map { element(it) },
            meta = meta,
            idle = true,
            settled = true,
        )
        assertEquals(5, decode(frame)["n"]!!.jsonPrimitive.intOrNull)
        assertEquals(5, elements(frame).size)
    }

    /**
     * som_id IS the index. A caller that renumbers, filters or reorders `e`
     * silently desynchronises every subsequent tap, so the encoder must emit
     * elements in somId order and must not drop any.
     */
    @Test
    fun `elements are emitted in som_id order so index equals som_id`() {
        val frame = ScreenFrame.encode(
            elements = listOf(element(3, label = "third"), element(1, label = "first"), element(2, label = "second")),
            meta = meta,
            idle = true,
            settled = true,
        )
        val e = elements(frame)
        assertEquals("first", field(e, 0, 4))
        assertEquals("second", field(e, 1, 4))
        assertEquals("third", field(e, 2, 4))
    }

    @Test
    fun `screen metadata rides on the frame`() {
        val frame = decode(
            ScreenFrame.encode(elements = listOf(element(1)), meta = meta, idle = false, settled = false),
        )
        assertEquals("com.example", frame["pkg"]!!.jsonPrimitive.contentOrNull)
        assertEquals(1240, frame["w"]!!.jsonPrimitive.intOrNull)
        assertEquals(2772, frame["h"]!!.jsonPrimitive.intOrNull)
        assertEquals(false, frame["idle"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, frame["settled"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `no escalate field on a normal screen`() {
        val frame = decode(
            ScreenFrame.encode(elements = (1..20).map { element(it) }, meta = meta, idle = true, settled = true),
        )
        assertNull(frame["escalate"])
        assertNull(frame["escalate_reason"])
    }

    /**
     * The signal `UiStreamFrame` has no way to express: the tree read fine and
     * described almost nothing. That is a WebView / Canvas / game surface, and
     * it is exactly when vision is required — without this the agent gets a thin
     * list with no indication it is looking at nothing.
     */
    @Test
    fun `escalate names perceive_screen with a reason when the tree is thin`() {
        val frame = decode(
            ScreenFrame.encode(
                elements = listOf(element(1)),
                meta = meta,
                idle = true,
                settled = true,
                escalateReason = "tree describes 1 element",
            ),
        )
        assertEquals("perceive_screen", frame["escalate"]!!.jsonPrimitive.contentOrNull)
        assertTrue(frame["escalate_reason"]!!.jsonPrimitive.contentOrNull!!.contains("1 element"))
    }

    @Test
    fun `metaOf reads package and screen size off the tree payload`() {
        val meta = ScreenFrame.metaOf(
            """{"package_name":"com.swiggy","screen_width_px":1080,"screen_height_px":2400,"elements":[]}""",
        )
        assertEquals("com.swiggy", meta.pkg)
        assertEquals(1080, meta.widthPx)
        assertEquals(2400, meta.heightPx)
    }

    /** A malformed payload must degrade, not throw — the tool still owes an answer. */
    @Test
    fun `metaOf degrades on unparseable payload`() {
        val meta = ScreenFrame.metaOf("not json at all")
        assertEquals("", meta.pkg)
        assertEquals(0, meta.widthPx)
        assertEquals(0, meta.heightPx)
    }

    @Test
    fun `whitespace in labels is collapsed so one element stays one line`() {
        val frame = ScreenFrame.encode(
            elements = listOf(element(1, label = "  Add   to\n cart  ")),
            meta = meta,
            idle = true,
            settled = true,
        )
        assertEquals("Add to cart", field(elements(frame), 0, 4))
    }

    @Test
    fun `frame carries a format version`() {
        val frame = decode(ScreenFrame.encode(listOf(element(1)), meta, idle = true, settled = true))
        assertEquals(ScreenFrame.VERSION, frame["v"]!!.jsonPrimitive.intOrNull)
    }

    /**
     * The token claim this whole tool exists for. The fat `get_ui_tree` shape
     * repeats sixteen key names per element; this repeats none. Pinned as a
     * ratio rather than an absolute so it survives formatting changes.
     */
    @Test
    fun `positional encoding stays far smaller than one key name per field`() {
        val els = (1..100).map { element(it, label = "Item $it", interactive = true) }
        val frame = ScreenFrame.encode(els, meta, idle = true, settled = true)
        val perElement = frame.length / 100
        assertTrue(perElement < 45, "positional element cost $perElement chars, expected < 45")
        assertFalse(frame.contains("\"bounds\""))
        assertFalse(frame.contains("\"className\""))
    }

    /**
     * ActionGuard's loop detection reads `ui_tree_count` to find the deterministic
     * prefix of a grounding result, and returns null without it — falling back to
     * hashing the whole payload, where `idle` flipping between two looks at ONE screen
     * reads as two different screens and no loop is ever detected. read_screen runs no
     * CV pass, so every element is a tree element and the count is simply n.
     */
    @Test
    fun `ui_tree_count is emitted and equals n so loop detection can key on it`() {
        val frame = decode(
            ScreenFrame.encode((1..7).map { element(it) }, meta, idle = true, settled = true),
        )
        assertEquals(7, frame["ui_tree_count"]!!.jsonPrimitive.intOrNull)
        assertEquals(
            frame["n"]!!.jsonPrimitive.intOrNull,
            frame["ui_tree_count"]!!.jsonPrimitive.intOrNull,
        )
    }

    /**
     * The post-scroll hint sends the agent to read_screen to decide whether it has
     * reached the end of a list. That question cannot be answered from the visible
     * elements alone, so offscreen text has to ride along.
     */
    @Test
    fun `offscreen text rides on the frame when present`() {
        val frame = decode(
            ScreenFrame.encode(
                listOf(element(1)), meta, idle = true, settled = true,
                offscreen = listOf("Biryani", "Desserts"),
            ),
        )
        val off = frame["offscreen"]!!.jsonArray
        assertEquals(2, off.size)
        assertEquals("Biryani", off[0].jsonPrimitive.contentOrNull)
    }

    /** Absent rather than empty: an empty array is noise on every ordinary screen. */
    @Test
    fun `offscreen is omitted when there is none`() {
        val frame = decode(ScreenFrame.encode(listOf(element(1)), meta, idle = true, settled = true))
        assertNull(frame["offscreen"])
    }
}
