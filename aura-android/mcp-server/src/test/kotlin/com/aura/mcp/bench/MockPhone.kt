package com.aura.mcp.bench

import com.aura.mcp.bridge.AppCandidate
import com.aura.mcp.bridge.AppLookupResult
import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.bridge.DeviceEvent
import com.aura.mcp.bridge.DeviceStatus
import com.aura.mcp.bridge.ScrollDirection
import com.aura.mcp.bridge.TapVia
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.bridge.UiTreeSnapshot
import com.aura.mcp.bridge.VolumeDirection
import com.aura.mcp.cache.ScreenActivity
import com.aura.mcp.tools.TouchTarget
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One view in the mock phone. [box] is in screen pixels with every ancestor scrolled to 0.
 *
 * Behaviour hooks run when the view is clicked, long-pressed or receives Enter. A view with
 * [ignoresGestures] discards injected touches (the Swiggy case) but still answers an
 * accessibility click; [ignoresSetText] refuses accessibility SET_TEXT (React Native fields) but
 * accepts the AURA keyboard.
 */
internal class MockNode(
    val id: String,
    var box: BBox,
    var cls: String = "android.view.View",
    var text: String = "",
    var desc: String = "",
    var viewId: String = "",
    var clickable: Boolean = false,
    var longClickable: Boolean = false,
    var scrollable: Boolean = false,
    var editable: Boolean = false,
    var checkable: Boolean = false,
    var checked: Boolean = false,
    var enabled: Boolean = true,
    var drawingOrder: Int = 0,
    var ignoresGestures: Boolean = false,
    var ignoresSetText: Boolean = false,
    var maxLength: Int? = null,
    /** The keyboard path adds to what is there instead of replacing it. */
    var imeAppends: Boolean = false,
    /** A password field: reads back as dots. */
    var password: Boolean = false,
    /** Runs after text lands in this field: a search box that grows a clear button, say. */
    var onText: (MockPhone.() -> Unit)? = null,
    var onTap: (MockPhone.() -> Unit)? = null,
    var onLongPress: (MockPhone.() -> Unit)? = null,
    var onEnter: (MockPhone.() -> Unit)? = null,
) {
    val children = mutableListOf<MockNode>()
    var parent: MockNode? = null
    var scrollX = 0
    var scrollY = 0

    fun add(vararg kids: MockNode): MockNode {
        kids.forEach { it.parent = this; children += it }
        return this
    }

    /** Children in the order Android paints them: drawing order, then child index. */
    fun paintOrder(): List<MockNode> =
        children.withIndex().sortedWith(compareBy({ it.value.drawingOrder }, { it.index })).map { it.value }

    fun subtree(): Sequence<MockNode> = sequenceOf(this) + children.asSequence().flatMap { it.subtree() }
}

/** A window: an app screen, a dialog over it, or one of AURA's own overlays ([own]). */
internal class MockWindow(val pkg: String, val layer: Int, val root: MockNode, val own: Boolean = false)

/** One touch the phone received, and what actually got it. */
internal data class TouchRecord(val kind: String, val x: Int, val y: Int, val hit: String?, val via: String?)

/**
 * A phone with no emulator: a window list of [MockNode] trees that behaves like Android where
 * the tools can tell the difference.
 *
 * What it gets right on purpose, because these are where tools break:
 * - Touches go to the topmost window under the point, then to the topmost child that handles
 *   touches (reverse paint order), the way `ViewGroup.dispatchTouchEvent` does. Nothing else.
 * - `getBoundsInScreen` is clipped to every ancestor, so scrolled-away rows collapse.
 * - A tap with no visible reaction falls back to an accessibility click on the node
 *   [TouchTarget] picks, mirroring `AuraAccessibilityService.tapAwait`.
 * - Every change records a structural screen event, so the staleness counters move.
 *
 * - Change together: the payload shape mirrors `UITreeExtractor.getUITree` + `toTreeMap` in
 *   `:app`; a field added there and read by `:mcp-server` must be emitted here too.
 */
internal class MockPhone(val width: Int = 1080, val height: Int = 2400) : DeviceBridge, UiTreeBridge {

    val windows = mutableListOf<MockWindow>()
    private val history = ArrayDeque<List<MockWindow>>()
    private val apps = LinkedHashMap<String, Pair<String, () -> List<MockWindow>>>()
    var focused: MockNode? = null
    val touches = mutableListOf<TouchRecord>()
    /** Node ids in the order their click behaviour ran. */
    val clicks = mutableListOf<String>()
    val typed = mutableListOf<Pair<String, String>>()
    private val events = ConcurrentLinkedQueue<DeviceEvent>()
    private var lastVia: TapVia? = null
    /** Changes that happen by themselves after that many more tree reads: a slow load. */
    private val pending = mutableListOf<Pair<Int, MockPhone.() -> Unit>>()
    var clipboard: String? = null
    /** Android 10+ in the background: the clipboard cannot be read. */
    var clipboardReadable = true

    fun later(afterReads: Int, change: MockPhone.() -> Unit) {
        pending += afterReads to change
    }

    // ── scene control (used by scenarios and behaviours) ──

    fun show(vararg w: MockWindow) {
        windows.clear()
        windows += w
        focused = null
        changed("show ${w.joinToString { it.pkg }}")
    }

    fun navigate(vararg w: MockWindow) {
        history.addLast(windows.toList())
        show(*w)
    }

    fun open(w: MockWindow) {
        history.addLast(windows.toList())
        windows += w
        changed("open ${w.pkg}@${w.layer}")
    }

    fun install(pkg: String, name: String, screen: () -> List<MockWindow>) {
        apps[pkg] = name to screen
    }

    fun node(id: String): MockNode =
        windows.asSequence().flatMap { it.root.subtree() }.firstOrNull { it.id == id }
            ?: error("no node '$id' on screen")

    fun nodeOrNull(id: String): MockNode? = windows.asSequence().flatMap { it.root.subtree() }.firstOrNull { it.id == id }

    /** Something on screen changed: the counters a real accessibility event would move. */
    fun changed(what: String) {
        ScreenActivity.record(ScreenActivity.Class.STRUCTURAL)
        events += DeviceEvent(
            type = "TYPE_WINDOW_CONTENT_CHANGED",
            packageName = foregroundPkg(),
            timestampMs = System.currentTimeMillis(),
            description = what,
            changeClass = ScreenActivity.Class.STRUCTURAL,
        )
    }

    fun foregroundPkg(): String = windows.filterNot { it.own }.maxByOrNull { it.layer }?.pkg.orEmpty()

    // ── geometry ──

    fun screenBox(n: MockNode): BBox {
        var dx = 0
        var dy = 0
        var p = n.parent
        while (p != null) {
            dx -= p.scrollX
            dy -= p.scrollY
            p = p.parent
        }
        return BBox(n.box.x1 + dx, n.box.y1 + dy, n.box.x2 + dx, n.box.y2 + dy)
    }

    /** What `getBoundsInScreen` reports: clipped to the screen and to every ancestor. */
    fun visibleBox(n: MockNode): BBox? {
        var clip: BBox? = BBox(0, 0, width, height)
        var cur: MockNode? = n
        while (cur != null && clip != null) {
            clip = intersect(clip, screenBox(cur))
            cur = cur.parent
        }
        return clip
    }

    private fun intersect(a: BBox, b: BBox): BBox? {
        val r = BBox(maxOf(a.x1, b.x1), maxOf(a.y1, b.y1), minOf(a.x2, b.x2), minOf(a.y2, b.y2))
        return if (r.x2 > r.x1 && r.y2 > r.y1) r else null
    }

    private fun BBox.has(x: Int, y: Int) = x in x1 until x2 && y in y1 until y2

    // ── the tree payload, as UITreeExtractor writes it ──

    fun payload(): String {
        val elements = mutableListOf<JsonObject>()
        val offscreen = LinkedHashSet<String>()
        for (w in windows.filterNot { it.own }.sortedByDescending { it.layer }) {
            var paint = w.layer.toLong() * 1_000_000L
            fun walk(n: MockNode) {
                val z = paint++
                val vis = visibleBox(n)
                val hasText = n.text.isNotBlank() || n.desc.isNotBlank()
                val include = vis != null && vis.width > 5 && vis.height > 5
                if (!include && hasText) offscreen += n.text.ifBlank { n.desc }
                val idx = if (include) elements.size else -1
                if (include) elements += element(n, vis!!, z, hasText)
                n.paintOrder().forEach(::walk)
                if (idx >= 0) elements[idx] = JsonObject(elements[idx] + ("zEnd" to JsonPrimitive(paint - 1)))
            }
            walk(w.root)
        }
        return buildJsonObject {
            put("validation_failed", false)
            put("elements_count", elements.size)
            put("screen_width_px", width)
            put("screen_height_px", height)
            put("package_name", foregroundPkg())
            put("truncated", false)
            if (offscreen.isNotEmpty()) put("offscreen_text", JsonArray(offscreen.map(::JsonPrimitive)))
            put("elements", JsonArray(elements))
        }.toString()
    }

    private fun element(n: MockNode, vis: BBox, z: Long, hasText: Boolean) = buildJsonObject {
        put("text", if (n.password && n.text.isNotEmpty()) "•".repeat(n.text.length) else n.text)
        put("contentDescription", n.desc)
        put("className", n.cls)
        put("bounds", buildJsonObject {
            put("left", vis.x1); put("top", vis.y1); put("right", vis.x2); put("bottom", vis.y2)
            put("centerX", vis.centerX); put("centerY", vis.centerY)
        })
        // UITreeExtractor lends a clickable parent's flag to its text children.
        put("isClickable", n.clickable || (hasText && n.parent?.clickable == true))
        put("isScrollable", n.scrollable)
        put("isEditable", n.editable || "EditText" in n.cls)
        put("isCheckable", n.checkable)
        put("isChecked", n.checked)
        put("isLongClickable", n.longClickable)
        put("isEnabled", n.enabled)
        put("isFocused", n === focused)
        put("actions", JsonArray(buildList {
            if (n.clickable) add(JsonPrimitive("click"))
            if (n.longClickable) add(JsonPrimitive("long_click"))
            if (n.scrollable) add(JsonPrimitive("scroll_forward"))
            if (n.editable) add(JsonPrimitive("set_text"))
        }))
        put("viewId", n.viewId)
        put("z", z)
    }

    // ── touch routing, as Android does it ──

    private fun touchWindow(x: Int, y: Int): MockWindow? =
        windows.filterNot { it.own }.sortedByDescending { it.layer }
            .firstOrNull { w -> visibleBox(w.root)?.has(x, y) == true }

    /** `ViewGroup.dispatchTouchEvent`: topmost child first, then the view itself. */
    private fun dispatch(n: MockNode, x: Int, y: Int, consumes: (MockNode) -> Boolean): MockNode? {
        if (visibleBox(n)?.has(x, y) != true) return null
        for (c in n.paintOrder().asReversed()) dispatch(c, x, y, consumes)?.let { return it }
        return n.takeIf(consumes)
    }

    fun hitAt(x: Int, y: Int): MockNode? =
        touchWindow(x, y)?.let { w -> dispatch(w.root, x, y) { it.clickable || it.longClickable } }

    /** Runs [n]'s click behaviour; true when something visibly happened. */
    fun click(n: MockNode): Boolean {
        if (!n.enabled) return false
        val acted = when {
            n.onTap != null -> { n.onTap!!.invoke(this); true }
            n.checkable -> { n.checked = !n.checked; changed("toggle ${n.id}"); true }
            n.editable -> { focused = n; changed("focus ${n.id}"); true }
            else -> false
        }
        if (acted) clicks += n.id
        return acted
    }

    /** Candidates for the accessibility-click fallback, in paint order, own windows included. */
    private fun fallbackCandidates(): Pair<List<MockNode>, List<TouchTarget.Candidate>> {
        val nodes = mutableListOf<MockNode>()
        val cands = mutableListOf<TouchTarget.Candidate>()
        for (w in windows.sortedBy { it.layer }) {
            fun walk(n: MockNode) {
                val b = visibleBox(n) ?: BBox(0, 0, 0, 0)
                nodes += n
                cands += TouchTarget.Candidate(b, n.clickable, n.enabled, own = w.own)
                n.paintOrder().forEach(::walk)
            }
            walk(w.root)
        }
        return nodes to cands
    }

    override fun performTap(x: Int, y: Int): Boolean {
        // tapAwait resolves the fallback node BEFORE tapping: a good tap navigates it away.
        val (nodes, cands) = fallbackCandidates()
        val fallback = TouchTarget.pick(cands, x, y)?.let { nodes[it] }
        val hit = hitAt(x, y)
        if (hit != null && !hit.ignoresGestures && click(hit)) {
            touches += TouchRecord("tap", x, y, hit.id, "gesture")
            lastVia = TapVia.GESTURE
            return true
        }
        if (fallback == null) {
            touches += TouchRecord("tap", x, y, hit?.id, "no_reaction")
            lastVia = TapVia.UNVERIFIED
            return true
        }
        // SemanticClicker.click climbs to the nearest clickable ancestor.
        val target = generateSequence(fallback) { it.parent }.firstOrNull { it.clickable } ?: fallback
        val clicked = click(target)
        touches += TouchRecord("tap", x, y, target.id, if (clicked) "accessibility_click" else "fallback_failed")
        lastVia = if (clicked) TapVia.SEMANTIC else null
        return clicked
    }

    override fun lastTapVia(): TapVia? = lastVia

    override fun performDoubleTap(x: Int, y: Int): Boolean {
        val hit = hitAt(x, y)
        touches += TouchRecord("double_tap", x, y, hit?.id, "gesture")
        if (hit == null || hit.ignoresGestures) return true
        click(hit)
        click(hit)
        return true
    }

    override fun performLongPress(x: Int, y: Int, durationMs: Long): Boolean {
        val hit = touchWindow(x, y)?.let { w -> dispatch(w.root, x, y) { it.longClickable || it.clickable } }
        touches += TouchRecord("long_press", x, y, hit?.id, "gesture")
        hit?.onLongPress?.let { it(this); clicks += "${hit.id}:long" }
        return true
    }

    override fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val under = touchWindow(x1, y1)?.let { w -> dispatch(w.root, x1, y1) { true } }
        val scroller = generateSequence(under) { it.parent }.firstOrNull { it.scrollable }
        touches += TouchRecord("swipe", x1, y1, scroller?.id ?: under?.id, "gesture")
        if (scroller != null) scrollBy(scroller, x1 - x2, y1 - y2)
        return true
    }

    /** Scrolls [n]'s content, clamped to what it holds; true when it moved. */
    fun scrollBy(n: MockNode, dx: Int, dy: Int): Boolean {
        val content = n.subtree().drop(1)
        val maxY = ((content.maxOfOrNull { it.box.y2 } ?: n.box.y2) - n.box.y2).coerceAtLeast(0)
        val maxX = ((content.maxOfOrNull { it.box.x2 } ?: n.box.x2) - n.box.x2).coerceAtLeast(0)
        val ny = (n.scrollY + dy).coerceIn(0, maxY)
        val nx = (n.scrollX + dx).coerceIn(0, maxX)
        if (ny == n.scrollY && nx == n.scrollX) return false
        n.scrollY = ny
        n.scrollX = nx
        changed("scroll ${n.id} to $nx,$ny")
        return true
    }

    override fun performScroll(direction: ScrollDirection): Boolean {
        // CoordinateResolver.resolveDirection: a half-screen drag centred on the screen.
        val cx = width / 2
        val cy = height / 2
        val dx = width / 4
        val dy = height / 4
        return when (direction) {
            ScrollDirection.DOWN -> performSwipe(cx, cy + dy, cx, cy - dy, 500)
            ScrollDirection.UP -> performSwipe(cx, cy - dy, cx, cy + dy, 500)
            ScrollDirection.RIGHT -> performSwipe(cx + dx, cy, cx - dx, cy, 500)
            ScrollDirection.LEFT -> performSwipe(cx - dx, cy, cx + dx, cy, 500)
        }
    }

    // ── text ──

    private fun setText(n: MockNode, text: String) {
        n.text = n.maxLength?.let { text.take(it) } ?: text
        typed += n.id to n.text
        n.onText?.invoke(this)
        changed("text ${n.id}")
    }

    /** performTextInput: accessibility SET_TEXT on the focused field. */
    private fun setTextOnFocused(text: String): Boolean {
        val f = focused ?: return false
        if (f.ignoresSetText) return false
        setText(f, text)
        return true
    }

    /** typeViaIme: the AURA keyboard commits into whatever holds focus. */
    private fun imeCommit(text: String): Boolean {
        val f = focused ?: return false
        if (!f.editable) return false
        setText(f, if (f.imeAppends) f.text + text else text)
        return true
    }

    override fun typeText(text: String): Boolean = setTextOnFocused(text) || imeCommit(text)

    override fun typeTextAt(text: String, x: Int, y: Int): Boolean {
        val editable = touchWindow(x, y)?.let { w -> dispatch(w.root, x, y) { it.editable } }
        if (editable != null && !editable.ignoresSetText) {
            focused = editable
            setText(editable, text)
            return true
        }
        performTap(x, y)
        return setTextOnFocused(text) || imeCommit(text)
    }

    override fun pressEnter(): Boolean {
        val f = focused ?: return false
        f.onEnter?.invoke(this)
        return true
    }

    // ── system ──

    override fun performBack(): Boolean {
        val prev = history.removeLastOrNull() ?: return false
        windows.clear()
        windows += prev
        focused = null
        changed("back")
        return true
    }

    override fun performHome(): Boolean = true
    override fun performRecents(): Boolean = true
    override fun adjustVolume(direction: VolumeDirection): Boolean = true
    override fun restoreKeyboard(): Boolean = true

    override fun getDeviceStatus() = DeviceStatus(
        accessibilityServiceRunning = true,
        screenWidthPx = width,
        screenHeightPx = height,
        androidApiLevel = 34,
        deviceModel = "MockPhone",
        foregroundPackage = foregroundPkg(),
    )

    override fun launchApp(packageName: String): Boolean {
        val (_, screen) = apps[packageName] ?: return false
        if (foregroundPkg() == packageName) return true
        navigate(*screen().toTypedArray())
        return true
    }

    override fun lookupApp(query: String): AppLookupResult {
        val matches = apps.filter { (pkg, v) -> v.first.contains(query, true) || pkg == query }
        val best = matches.entries.firstOrNull()
        return AppLookupResult(
            found = best != null,
            packageName = best?.key.orEmpty(),
            appName = best?.value?.first.orEmpty(),
            candidates = matches.map { (pkg, v) -> AppCandidate(pkg, v.first) },
            error = if (best == null) "no app matches '$query'" else null,
        )
    }

    // ── UiTreeBridge ──

    override suspend fun snapshot(): UiTreeSnapshot {
        val due = pending.map { (n, c) -> (n - 1) to c }
        pending.clear()
        due.filter { it.first > 0 }.forEach { pending += it }
        due.filter { it.first <= 0 }.forEach { it.second(this) }
        return UiTreeSnapshot(ok = true, payloadJson = payload())
    }

    override fun readClipboard(): String? = clipboard.takeIf { clipboardReadable }

    override fun writeClipboard(text: String): Boolean {
        clipboard = text
        return true
    }

    override suspend fun drainEvents(timeoutMs: Long, maxEvents: Int): List<DeviceEvent> {
        if (events.isEmpty()) delay(minOf(timeoutMs, 40))
        return generateSequence { events.poll() }.take(maxEvents).toList()
    }
}
