package com.aura.mcp.bench

import com.aura.mcp.bridge.BBox

/**
 * The ways we try to break the tools. Each scenario builds a screen, drives the real MCP server
 * like a model would, and checks what reached the phone, not just what the tool said.
 *
 * - Change together: a scenario marked `gap` names the planned change that closes it; when that
 *   change lands, drop the `gap` so a regression shows up as a failure.
 */
internal object Scenarios {

    private const val APP = "com.example.shop"

    private fun row(id: String, text: String, y1: Int, y2: Int, block: MockNode.() -> Unit = {}) =
        button(id, text, 0, y1, 1080, y2, block)

    val all: List<Scenario> = listOf(
        // ── layers ──
        Scenario("L1", "layers", "Floating button over a row's centre: tap the row, not the button") {
            phone.show(screen(APP, row("row", "Order #1", 1000, 1200), button("fab", "Add", 400, 950, 700, 1250)))
            read()
            tap("row")
            expectHit("row", "gesture")
        },
        Scenario("L2", "layers", "Tap the floating button itself") {
            phone.show(screen(APP, row("row", "Order #1", 1000, 1200), button("fab", "Add", 400, 950, 700, 1250)))
            read()
            tap("fab")
            expectHit("fab", "gesture")
        },
        Scenario("L3", "layers", "Row fully under a bottom bar is flagged and refused") {
            phone.show(screen(APP, row("row", "Order #1", 1000, 1200), button("bar", "Checkout", 0, 900, 1080, 1300)))
            val look = read()
            check("read_screen marks the row as under the bar", "under" in look.text, look.text.take(400))
            val before = phone.touches.size
            expectRefused(tap("row"), "covered")
            expectNoTouch(before)
        },
        Scenario("L4", "layers", "A dialog window covers the app, even its non-clickable padding") {
            phone.show(
                screen(APP, button("buy", "Buy", 300, 1000, 780, 1100)),
                MockWindow("android", 2, box("dialog", 100, 800, 980, 1400) { cls = "android.widget.FrameLayout" }
                    .add(button("ok", "OK", 600, 1250, 950, 1380))),
            )
            read()
            val before = phone.touches.size
            expectRefused(tap("buy"), "covered")
            expectNoTouch(before)
            read()
            tap("ok")
            expectHit("ok")
        },
        Scenario("L5", "layers", "Three stacked buttons with the same bounds: only the top one is reachable") {
            phone.show(
                screen(
                    APP,
                    button("a", "Alpha", 200, 1000, 880, 1150),
                    button("b", "Beta", 200, 1000, 880, 1150),
                    button("c", "Gamma", 200, 1000, 880, 1150),
                ),
            )
            read()
            val before = phone.touches.size
            expectRefused(tap("b"), "covered")
            expectNoTouch(before)
            read()
            tap("c")
            expectHit("c")
        },
        Scenario("L6", "layers", "A clickable wrapper and its clickable child share bounds: not refused") {
            phone.show(screen(APP, row("wrap", "", 1000, 1200).add(row("inner", "Settings", 1000, 1200))))
            read()
            val before = phone.touches.size
            val r = tap("wrap")
            check("tap went through", !r.isError && phone.touches.size > before, r.text.take(240))
        },
        Scenario("L7", "layers", "Elevation: a button declared first but drawn last still covers") {
            phone.show(
                screen(APP, button("fab", "Add", 400, 950, 700, 1250) { drawingOrder = 1 }, row("row", "Order #1", 1000, 1200)),
            )
            read()
            tap("row")
            expectHit("row", "gesture")
        },
        Scenario("L9", "layers", "Two covers in an L leave one free corner, and the tap finds it") {
            phone.show(
                screen(
                    APP, row("row", "Order #1", 1000, 1200),
                    button("left", "Left", 0, 1000, 700, 1200), button("top", "Top", 700, 1000, 1080, 1120),
                ),
            )
            read()
            tap("row")
            expectHit("row", "gesture")
        },
        Scenario("L10", "layers", "A sliver thinner than a fingertip counts as covered") {
            phone.show(screen(APP, row("row", "Order #1", 1000, 1200), button("sheet", "Sheet", 0, 1005, 1080, 1300)))
            read()
            val before = phone.touches.size
            expectRefused(tap("row"), "covered")
            expectNoTouch(before)
        },
        Scenario("L11", "layers", "A disabled bar still swallows touches over the row") {
            phone.show(
                screen(APP, row("row", "Order #1", 1000, 1200), button("bar", "Checkout", 0, 900, 1080, 1300) { enabled = false }),
            )
            read()
            val before = phone.touches.size
            expectRefused(tap("row"), "covered")
            expectNoTouch(before)
        },
        Scenario("L12", "layers", "A label drawn over a button does not block it") {
            phone.show(screen(APP, button("btn", "Pay", 200, 1000, 880, 1150), label("badge", "NEW", 200, 1000, 880, 1150)))
            read()
            tap("btn")
            expectHit("btn", "gesture")
        },
        Scenario("L13", "layers", "A popup appears between the look and the tap") {
            phone.show(screen(APP, button("buy", "Buy", 300, 1000, 780, 1100)))
            read()
            val id = som("buy")
            phone.open(
                MockWindow("android", 2, box("popup", 0, 800, 1080, 1400) { cls = "android.widget.FrameLayout" }
                    .add(button("deny", "Deny", 100, 900, 980, 1300))),
            )
            val before = phone.touches.size
            val r = call("tap", mapOf("som_id" to id))
            check("tap is refused", r.isError, r.text.take(240))
            expectNoTouch(before)
        },
        Scenario("L14", "layers", "An app that ignores injected touches gets an accessibility click on the right node") {
            phone.show(screen(APP, row("row", "Order #1", 1000, 1200) { ignoresGestures = true }, button("fab", "Add", 400, 950, 700, 1250)))
            read()
            val r = tap("row")
            expectHit("row", "accessibility_click")
            check("reply says how the tap landed", r.str("via") == "accessibility_click", r.text.take(240))
        },
        Scenario("L15", "layers", "The fallback click never lands on AURA's own pause button") {
            phone.show(
                screen(APP, button("btn", "Pay", 200, 1000, 880, 1150) { ignoresGestures = true }),
                MockWindow("com.aura.aura_ui", 3, box("aura", 0, 0, 1080, 2400) { cls = "android.widget.FrameLayout" }
                    .add(button("pause", "Pause", 200, 1000, 880, 1150)), own = true),
            )
            read()
            tap("btn")
            expectHit("btn", "accessibility_click")
            check("pause was never clicked", "pause" !in phone.clicks, "${phone.clicks}")
        },

        // ── tap ──
        Scenario("T1", "tap", "Tap before ever looking") {
            phone.show(screen(APP, button("btn", "Pay", 200, 1000, 880, 1150)))
            val r = call("tap", mapOf("som_id" to 1))
            check("tap is refused", r.isError, r.text.take(240))
            expectNoTouch(0)
        },
        Scenario("T2", "tap", "Tap a som_id from the previous screen after navigating") {
            phone.show(screen(APP, button("btn", "Pay", 200, 1000, 880, 1150)))
            read()
            val id = som("btn")
            phone.navigate(screen(APP, button("other", "Delete account", 200, 1000, 880, 1150)))
            val before = phone.touches.size
            val r = call("tap", mapOf("som_id" to id))
            check("tap is refused", r.isError, r.text.take(240))
            expectNoTouch(before)
            check("nothing was deleted", "other" !in phone.clicks, "${phone.clicks}")
        },
        Scenario("T3", "tap", "A som_id that does not exist") {
            phone.show(screen(APP, button("btn", "Pay", 200, 1000, 880, 1150)))
            read()
            val r = call("tap", mapOf("som_id" to 999))
            check("tap is refused", r.isError, r.text.take(240))
            expectNoTouch(0)
        },
        Scenario("T4", "tap", "Garbage arguments") {
            phone.show(screen(APP, button("btn", "Pay", 200, 1000, 880, 1150)))
            read()
            for (args in listOf(mapOf("som_id" to "abc"), mapOf<String, Any?>(), mapOf("som_id" to 1, "style" to "triple"), mapOf("som_id" to -1))) {
                val r = call("tap", args)
                check("refused $args", r.isError, r.text.take(160))
            }
            expectNoTouch(0)
        },
        Scenario("T5", "tap", "Tapping a button that changes nothing, over and over, is stopped") {
            // The tap lands (the app takes the touch) but the screen never changes.
            phone.show(screen(APP, button("btn", "Pay", 200, 1000, 880, 1150) { onTap = {} }))
            val replies = (1..4).map { read(); tap("btn") }
            check("the first tap goes through", !replies.first().isError, replies.first().text.take(160))
            check("a later repeat is refused", replies.drop(1).any { it.isError }, replies.last().text.take(240))
            check("at most 2 identical taps reached the phone", phone.clicks.count { it == "btn" } <= 2, "${phone.clicks}")
        },
        Scenario("T6", "tap", "A tap that did nothing does not claim it worked") {
            phone.show(screen(APP, button("btn", "Pay", 200, 1000, 880, 1150) { onTap = null }))
            read()
            val r = tap("btn")
            check("not reported as a clean gesture", r.isError || r.str("via") != "gesture", r.text.take(240))
        },

        // ── scroll ──
        Scenario("S1", "scroll", "Scroll inside a list by its som_id") {
            val list = box("list", 0, 400, 1080, 1400) { cls = "android.widget.ListView"; scrollable = true }
                .add(*(0 until 12).map { row("r$it", "Item $it", 400 + it * 200, 600 + it * 200) }.toTypedArray())
            phone.show(screen(APP, list))
            read()
            call("scroll", mapOf("direction" to "down", "som_id" to som("list")))
            check("the list moved", phone.node("list").scrollY > 0, "scrollY=${phone.node("list").scrollY}")
        },
        Scenario("S4", "scroll", "Scroll returns what came into view, and its som_ids tap without another look") {
            val list = box("list", 0, 400, 1080, 1400) { cls = "android.widget.ListView"; scrollable = true }
                .add(*(0 until 12).map { row("r$it", "Item $it", 400 + it * 200, 600 + it * 200) }.toTypedArray())
            phone.show(screen(APP, list))
            read()
            val r = call("scroll", mapOf("direction" to "down", "som_id" to som("list")))
            val revealed = r.text.substringAfter("CAME INTO VIEW", "").substringBefore("still in view:")
            check("lists the newly visible rows", "Item 6" in revealed && "Item 0" !in revealed, revealed.take(300))
            check("no grid, no second observation block", "+----" !in r.text && "post_action_observation" !in r.text, r.text.take(200))
            val id = Regex("\\n(\\d+)\\s+\\S+\\s+\\S+\\s+Item 6").find(revealed)?.groupValues?.get(1)?.toInt()
            check("Item 6 has a som_id", id != null, revealed.take(300))
            call("tap", mapOf("som_id" to id))
            expectHit("r6", "gesture")
        },
        Scenario("S2", "scroll", "Screen scroll on a page where a map sits under the default swipe") {
            val list = box("list", 0, 200, 1080, 1300) { cls = "android.widget.ListView"; scrollable = true }
                .add(*(0 until 10).map { row("r$it", "Place $it", 200 + it * 200, 400 + it * 200) }.toTypedArray())
            val map = box("map", 0, 1300, 1080, 2000) { cls = "android.view.View"; desc = "Map"; scrollable = true }
                .add(box("tiles", 0, 1300, 3000, 4000))
            phone.show(screen(APP, list, map))
            read()
            call("scroll", mapOf("direction" to "down"))
            check("the list moved, not the map", phone.node("list").scrollY > 0 && phone.node("map").scrollY == 0,
                "list=${phone.node("list").scrollY} map=${phone.node("map").scrollY}")
        },
        Scenario("S3", "scroll", "Scroll at the end of a list says it did not move") {
            val list = box("list", 0, 400, 1080, 1400) { cls = "android.widget.ListView"; scrollable = true }
                .add(row("r0", "Only item", 400, 600))
            phone.show(screen(APP, list))
            read()
            val r = call("scroll", mapOf("direction" to "down", "som_id" to som("list")))
            check("reply says it did not move", r.str("moved") == "false" || "at_end" in r.text, r.text.take(240))
        },

        // ── type ──
        Scenario("Y1", "type", "Type into a plain field") {
            phone.show(screen(APP, field("q", "Search", 40, 200, 1040, 320)))
            read()
            call("type_text", mapOf("text" to "milk", "som_id" to som("q")))
            check("the field holds the text", phone.node("q").text == "milk", "text='${phone.node("q").text}'")
        },
        Scenario("Y2", "type", "The search bar is a button that opens a search screen") {
            phone.show(
                screen(APP, button("bar", "Search products", 40, 200, 1040, 320) {
                    onTap = {
                        navigate(screen(APP, field("real", "Search", 40, 200, 1040, 320)))
                        focused = node("real")
                    }
                }),
            )
            read()
            call("type_text", mapOf("text" to "milk", "som_id" to som("bar")))
            check("the real field holds the text", phone.nodeOrNull("real")?.text == "milk", "text='${phone.nodeOrNull("real")?.text}'")
        },
        Scenario("Y3", "type", "A React Native field refuses SET_TEXT; the keyboard path still types") {
            phone.show(screen(APP, field("q", "Search", 40, 200, 1040, 320) { ignoresSetText = true }))
            read()
            call("type_text", mapOf("text" to "milk", "som_id" to som("q")))
            check("the field holds the text", phone.node("q").text == "milk", "text='${phone.node("q").text}'")
        },
        Scenario("Y4", "type", "A field under the keyboard's suggestion bar is refused") {
            phone.show(
                screen(APP, field("q", "Note", 40, 2000, 1040, 2100)),
                MockWindow("com.android.inputmethod", 4, box("ime", 0, 1700, 1080, 2400) { cls = "android.widget.FrameLayout" }
                    .add(button("key", "q", 0, 1700, 1080, 2400))),
            )
            read()
            val before = phone.touches.size
            val r = call("type_text", mapOf("text" to "hello", "som_id" to som("q")))
            check("type is refused", r.isError, r.text.take(240))
            expectNoTouch(before)
            check("nothing was typed", phone.typed.isEmpty(), "${phone.typed}")
        },
        Scenario("Y5", "type", "A field that truncates input: the reply reads back what is there") {
            phone.show(screen(APP, field("pin", "PIN", 40, 200, 1040, 320) { maxLength = 4 }))
            read()
            val r = call("type_text", mapOf("text" to "123456", "som_id" to som("pin")))
            check("the tool's own result says the field holds 1234", r.str("field_text") == "1234" && r.str("mismatch") == "true", r.json.toString().take(240))
        },

        // ── values ──
        Scenario("G1", "values", "Set a switch to a value in one call") {
            phone.show(screen(APP, box("wifi", 0, 400, 1080, 560) { cls = "android.widget.Switch"; text = "Wi-Fi"; clickable = true; checkable = true }))
            read()
            call("set_value", mapOf("som_id" to som("wifi"), "value" to "on"))
            check("the switch is on", phone.node("wifi").checked)
            read()
            call("set_value", mapOf("som_id" to som("wifi"), "value" to "on"))
            check("setting it on again leaves it on", phone.node("wifi").checked)
            check("the second call did not touch the phone", phone.clicks.count { it == "wifi" } == 1, "${phone.clicks}")
        },
        Scenario("G2", "values", "A covered switch is refused, not toggled") {
            phone.show(
                screen(APP, box("wifi", 0, 1000, 1080, 1160) { cls = "android.widget.Switch"; text = "Wi-Fi"; clickable = true; checkable = true },
                    button("sheet", "Sheet", 0, 900, 1080, 1300)),
            )
            read()
            val before = phone.touches.size
            expectRefused(call("set_value", mapOf("som_id" to som("wifi"), "value" to "on")), "covered")
            expectNoTouch(before)
            check("still off", !phone.node("wifi").checked)
        },
        Scenario("G3", "values", "set_value on a settings row toggles the switch inside it") {
            phone.show(
                screen(APP, row("row", "Bluetooth", 400, 560) { onTap = { node("sw").checked = !node("sw").checked; changed("bt") } }
                    .add(box("sw", 900, 440, 1040, 520) { cls = "android.widget.Switch"; checkable = true })),
            )
            read()
            val r = call("set_value", mapOf("som_id" to som("row"), "value" to "on"))
            check("reply ok", !r.isError, r.text.take(240))
            check("the switch is on", phone.node("sw").checked)
        },
        Scenario("G4", "values", "A toggle value that is not on/off is refused") {
            phone.show(screen(APP, box("wifi", 0, 400, 1080, 560) { cls = "android.widget.Switch"; text = "Wi-Fi"; clickable = true; checkable = true }))
            read()
            expectRefused(call("set_value", mapOf("som_id" to som("wifi"), "value" to "maybe")), "on or off")
            expectNoTouch(0)
        },
        Scenario("G5", "values", "set_value text into a field whose keyboard appends: reported, not hidden") {
            phone.show(screen(APP, field("name", "Name", 40, 200, 1040, 320) { text = "Old"; ignoresSetText = true; imeAppends = true }))
            read()
            val r = call("set_value", mapOf("som_id" to som("name"), "value" to "New"))
            check("refused with what the field holds", r.isError && "OldNew" in r.text, r.text.take(240))
        },
        Scenario("G6", "values", "set_value on a plain button is refused") {
            phone.show(screen(APP, button("btn", "Pay", 200, 1000, 880, 1150)))
            read()
            expectRefused(call("set_value", mapOf("som_id" to som("btn"), "value" to "on")), "neither")
            expectNoTouch(0)
        },

        // ── apps ──
        Scenario("A1", "apps", "Launch an app, then launch it again") {
            phone.install("com.example.notes", "Notes") { listOf(screen("com.example.notes", label("t", "All notes", 0, 200, 1080, 300))) }
            phone.show(screen("com.android.launcher", label("h", "Home", 0, 200, 1080, 300)))
            val first = call("launch_app", mapOf("app_name" to "Notes"))
            check("first launch succeeds", !first.isError && phone.foregroundPkg() == "com.example.notes", first.text.take(240))
            val again = call("launch_app", mapOf("app_name" to "Notes"))
            check("second launch is not an error", !again.isError, again.text.take(240))
        },
        Scenario("A2", "apps", "An unknown app name does not launch something else") {
            phone.install("com.example.notes", "Notes") { listOf(screen("com.example.notes")) }
            phone.show(screen("com.android.launcher", label("h", "Home", 0, 200, 1080, 300)))
            val r = call("launch_app", mapOf("app_name" to "Zebra"))
            check("refused", r.isError, r.text.take(240))
            check("still on the launcher", phone.foregroundPkg() == "com.android.launcher")
        },

        Scenario("A3", "apps", "A banking app is never opened") {
            phone.install("com.example.bank", "My Bank") { listOf(screen("com.example.bank")) }
            phone.show(screen("com.android.launcher", label("h", "Home", 0, 200, 1080, 300)))
            val r = call("launch_app", mapOf("package_name" to "com.example.bank"))
            expectRefused(r, "policy_blocked")
            check("still on the launcher", phone.foregroundPkg() == "com.android.launcher")
        },

        Scenario("Y7", "type", "Seen on a OnePlus: the search field is never marked focused and shrinks for a clear button") {
            phone.show(screen(APP, box("bar", 40, 200, 1040, 320) { cls = "android.widget.LinearLayout" }.add(
                field("q", "Search", 140, 220, 1000, 300) {
                    onText = {
                        focused = null
                        node("q").box = BBox(140, 220, 900, 300)
                        node("bar").add(button("clear", "Clear text", 900, 220, 1000, 300))
                    }
                },
            )))
            read()
            val r = call("type_text", mapOf("text" to "battery", "som_id" to som("q")))
            check("the read-back found the field", r.str("field_text") == "battery", r.text.take(240))
            read()
            val s = call("set_value", mapOf("som_id" to som("q"), "value" to "wifi"))
            check("set_value confirmed it", !s.isError && s.str("confirmed") == null, s.text.take(240))
        },
        Scenario("Y6", "type", "A password field reads back as dots: no false mismatch") {
            phone.show(screen(APP, field("pw", "Password", 40, 200, 1040, 320) { password = true }))
            read()
            val r = call("type_text", mapOf("text" to "hunter22", "som_id" to som("pw")))
            check("no mismatch claimed", !r.isError && r.str("mismatch") == null, r.text.take(240))
            check("the password is not echoed back", "hunter22" !in r.text, r.text.take(240))
        },

        // ── wait ──
        Scenario("W1", "wait", "wait_for expect: returns when the text shows up") {
            phone.show(screen(APP, label("s", "Finding your driver…", 0, 400, 1080, 500)))
            phone.later(3) { node("s").text = "Driver arriving in 4 min"; changed("eta") }
            val r = call("wait_for", mapOf("condition" to "driver found", "expect" to "arriving", "timeout_ms" to 5000, "poll_interval_ms" to 100))
            check("found", !r.isError && r.str("found") == "true", r.text.take(240))
        },
        Scenario("W2", "wait", "wait_for expect: a text that never comes is a failure inside the timeout") {
            phone.show(screen(APP, label("s", "Finding your driver…", 0, 400, 1080, 500)))
            val t0 = System.currentTimeMillis()
            val r = call("wait_for", mapOf("condition" to "driver found", "expect" to "arriving", "timeout_ms" to 600, "poll_interval_ms" to 100))
            val ms = System.currentTimeMillis() - t0
            check("reported as not found", r.isError && r.str("found") == "false", r.text.take(240))
            check("kept to its timeout ($ms ms)", ms < 2_500)
        },
        Scenario("W3", "wait", "wait_for expect + gone: returns when the spinner text leaves") {
            phone.show(screen(APP, label("s", "Loading", 0, 400, 1080, 500)))
            phone.later(2) { node("s").text = "Done"; changed("done") }
            val r = call("wait_for", mapOf("condition" to "loaded", "expect" to "loading", "gone" to true, "timeout_ms" to 3000, "poll_interval_ms" to 100))
            check("gone", !r.isError && r.str("gone") == "true", r.text.take(240))
        },

        // ── batches ──
        Scenario("P1", "batch", "run_steps: type a query and press enter in one call") {
            phone.show(screen(APP, field("q", "Search", 40, 200, 1040, 320) {
                onEnter = { navigate(screen(APP, label("res", "Results for milk", 0, 400, 1080, 500))) }
            }))
            read()
            val r = call("run_steps", mapOf("steps" to listOf(
                mapOf("tool" to "type_text", "args" to mapOf("text" to "milk", "som_id" to som("q"))),
                mapOf("tool" to "press_enter", "args" to emptyMap<String, Any?>()),
            )))
            check("both steps ran", !r.isError && r.str("completed") == "2", r.text.take(300))
            check("the results screen is up", phone.nodeOrNull("res") != null)
        },
        Scenario("P2", "batch", "run_steps: a step aimed at a screen that went away is refused, and the rest stop") {
            phone.show(screen(APP, button("next", "Next", 200, 1000, 880, 1150) {
                onTap = { navigate(screen(APP, button("delete", "Delete account", 200, 1000, 880, 1150))) }
            }, button("other", "Help", 200, 1300, 880, 1450)))
            read()
            val r = call("run_steps", mapOf("steps" to listOf(
                mapOf("tool" to "tap", "args" to mapOf("som_id" to som("next"))),
                mapOf("tool" to "tap", "args" to mapOf("som_id" to som("other"))),
                mapOf("tool" to "tap", "args" to mapOf("som_id" to som("next"))),
            )))
            check("the batch stopped and said so", r.isError && r.str("completed") == "1", r.text.take(300))
            check("nothing on the new screen was touched", "delete" !in phone.clicks, "${phone.clicks}")
        },
        Scenario("P3", "batch", "run_steps cannot nest, look, or launch") {
            phone.show(screen(APP, button("btn", "Pay", 200, 1000, 880, 1150)))
            read()
            for (bad in listOf("run_steps", "read_screen", "launch_app", "end_session", "mark_step")) {
                val r = call("run_steps", mapOf("steps" to listOf(
                    mapOf("tool" to "tap", "args" to mapOf("som_id" to som("btn"))),
                    mapOf("tool" to bad, "args" to emptyMap<String, Any?>()),
                )))
                check("'$bad' refused before anything ran", r.isError && "cannot run inside" in r.text, r.text.take(200))
                check("the refusal says nothing ran and how to recover", "Nothing ran" in r.text && "on its own" in r.text, r.text.take(200))
            }
            expectNoTouch(0)
        },
        Scenario("P4", "batch", "run_steps: a card number in step 2 is blocked and step 3 never runs") {
            phone.show(screen(APP, field("card", "Card", 40, 200, 1040, 320), button("pay", "Pay", 200, 1000, 880, 1150)))
            read()
            val r = call("run_steps", mapOf("steps" to listOf(
                mapOf("tool" to "tap", "args" to mapOf("som_id" to som("card"))),
                mapOf("tool" to "type_text", "args" to mapOf("text" to "4111 1111 1111 1111")),
                mapOf("tool" to "tap", "args" to mapOf("som_id" to som("pay"))),
            )))
            check("the batch failed at step 2", r.isError && r.str("completed") == "1", r.text.take(300))
            check("the card number was never typed", phone.typed.none { "4111" in it.second }, "${phone.typed}")
            check("Pay was never tapped", "pay" !in phone.clicks, "${phone.clicks}")
        },
        Scenario("P5", "batch", "run_steps rejects garbage: too many steps, no steps, bad shapes") {
            phone.show(screen(APP, button("btn", "Pay", 200, 1000, 880, 1150)))
            read()
            val step = mapOf("tool" to "press_enter", "args" to emptyMap<String, Any?>())
            for (args in listOf(
                mapOf("steps" to List(9) { step }),
                mapOf("steps" to emptyList<Any>()),
                mapOf("steps" to "tap 1"),
                mapOf("steps" to listOf("tap")),
                mapOf("steps" to listOf(mapOf("tool" to "tap", "args" to "1"))),
                emptyMap(),
            )) {
                val r = call("run_steps", args)
                check("refused ${args.toString().take(60)}", r.isError, r.text.take(160))
            }
            expectNoTouch(0)
        },
        Scenario("P6", "batch", "run_steps: set two toggles in one call") {
            phone.show(screen(APP,
                box("wifi", 0, 400, 1080, 560) { cls = "android.widget.Switch"; text = "Wi-Fi"; clickable = true; checkable = true },
                box("bt", 0, 600, 1080, 760) { cls = "android.widget.Switch"; text = "Bluetooth"; clickable = true; checkable = true; checked = true },
            ))
            read()
            val r = call("run_steps", mapOf("steps" to listOf(
                mapOf("tool" to "set_value", "args" to mapOf("som_id" to som("wifi"), "value" to "on")),
                mapOf("tool" to "set_value", "args" to mapOf("som_id" to som("bt"), "value" to "off")),
            )))
            check("both ran", !r.isError && r.str("completed") == "2", r.text.take(300))
            check("Wi-Fi on, Bluetooth off", phone.node("wifi").checked && !phone.node("bt").checked)
        },

        Scenario("P7", "batch", "run_steps with no look at all: step 1 is refused, nothing is touched") {
            phone.show(screen(APP, button("btn", "Pay", 200, 1000, 880, 1150)))
            val r = call("run_steps", mapOf("steps" to listOf(
                mapOf("tool" to "tap", "args" to mapOf("som_id" to 1)),
                mapOf("tool" to "tap", "args" to mapOf("som_id" to 1)),
            )))
            check("refused at step 1", r.isError && r.str("completed") == "0", r.text.take(300))
            expectNoTouch(0)
        },
        Scenario("P8", "batch", "run_steps after a tap moved the screen, with no new look: refused, nothing touched") {
            phone.show(screen(APP, button("next", "Next", 200, 1000, 880, 1150) {
                onTap = { navigate(screen(APP, button("next2", "Confirm", 200, 1000, 880, 1150), button("x", "Cancel", 200, 1300, 880, 1450))) }
            }, button("help", "Help", 200, 1300, 880, 1450)))
            read()
            tap("next")
            val before = phone.touches.size
            val r = call("run_steps", mapOf("steps" to listOf(
                mapOf("tool" to "tap", "args" to mapOf("som_id" to 1)),
                mapOf("tool" to "tap", "args" to mapOf("som_id" to 2)),
            )))
            check("refused at step 1", r.isError && r.str("completed") == "0", r.text.take(300))
            expectNoTouch(before)
        },
        Scenario("P9", "batch", "run_steps: set a row's switch on twice; the second call sees it is already on") {
            phone.show(
                screen(APP, row("row", "Bluetooth", 400, 560) { onTap = { node("sw").checked = !node("sw").checked; changed("bt") } }
                    .add(box("sw", 900, 440, 1040, 520) { cls = "android.widget.Switch"; checkable = true })),
            )
            read()
            val r = call("run_steps", mapOf("steps" to listOf(
                mapOf("tool" to "set_value", "args" to mapOf("som_id" to som("row"), "value" to "on")),
                mapOf("tool" to "set_value", "args" to mapOf("som_id" to som("row"), "value" to "on")),
            )))
            check("both steps ok", !r.isError && r.str("completed") == "2", r.text.take(300))
            check("the switch ends on", phone.node("sw").checked)
            check("only one tap reached it", phone.clicks.count { it == "row" } == 1, "${phone.clicks}")
        },
        Scenario("P10", "batch", "A batch that stops part-way still shows the screen its first steps made") {
            phone.show(screen(APP, button("next", "Next", 200, 1000, 880, 1150) {
                onTap = { navigate(screen(APP, label("p2", "Page two", 0, 400, 1080, 500))) }
            }, button("help", "Help", 200, 1300, 880, 1450)))
            read()
            val r = call("run_steps", mapOf("steps" to listOf(
                mapOf("tool" to "tap", "args" to mapOf("som_id" to som("next"))),
                mapOf("tool" to "tap", "args" to mapOf("som_id" to som("help"))),
            )))
            check("stopped after step 1", r.isError && r.str("completed") == "1", r.text.take(200))
            check("the observation of step 1 rides along", "post_action_observation" in r.text && "\"screen_changed\":true" in r.text, r.text.takeLast(400))
        },
        Scenario("G7", "values", "The user flipped the switch after the look: set_value does not flip it back") {
            phone.show(screen(APP, box("wifi", 0, 400, 1080, 560) { cls = "android.widget.Switch"; text = "Wi-Fi"; clickable = true; checkable = true }))
            read()
            phone.node("wifi").checked = true // the user, by hand; no event, as on a slow phone
            val r = call("set_value", mapOf("som_id" to som("wifi"), "value" to "on"))
            check("reply ok", !r.isError, r.text.take(240))
            check("still on", phone.node("wifi").checked)
            expectNoTouch(0)
        },

        // ── diff reads ──
        Scenario("D1", "read", "changes_only after a popup: the popup and the newly covered button are listed") {
            phone.show(screen(APP, button("buy", "Buy", 300, 1000, 780, 1100), button("help", "Help", 300, 1300, 780, 1400),
                label("t1", "Title", 0, 100, 1080, 200), label("t2", "Price", 0, 300, 1080, 400)))
            read()
            // An in-app dialog: same package, so the diff compares like with like.
            phone.open(MockWindow(APP, 2, box("popup", 100, 900, 980, 1200) { cls = "android.widget.FrameLayout" }
                .add(button("deny", "Deny", 150, 950, 930, 1150))))
            val r = call("read_screen", mapOf("changes_only" to true))
            val changed = r.text.substringAfter("new or changed:", "").substringBefore("unchanged:")
            check("lists only changes", "CHANGES" in r.text && "+----" !in r.text, r.text.take(400))
            check("the popup button is listed as new", "Deny" in changed, changed)
            check("the covered Buy is listed as changed", "Buy" in changed && "under" in changed, changed)
            check("Help is listed as unchanged", "Help" in r.text.substringAfter("unchanged:", ""), r.text.takeLast(200))
            tap("deny")
            expectHit("deny")
        },
        Scenario("D2", "read", "changes_only on a still screen says nothing changed") {
            phone.show(screen(APP, button("buy", "Buy", 300, 1000, 780, 1100)))
            read()
            val r = call("read_screen", mapOf("changes_only" to true))
            check("no changes", "NO CHANGES" in r.text, r.text.take(300))
            tap("buy")
            expectHit("buy")
        },
        Scenario("D3", "read", "changes_only after switching apps falls back to the whole screen") {
            phone.show(screen(APP, button("buy", "Buy", 300, 1000, 780, 1100)))
            read()
            phone.navigate(screen("com.example.mail", button("compose", "Compose", 300, 1000, 780, 1100)))
            val r = call("read_screen", mapOf("changes_only" to true))
            check("the whole grid came back", "+----" in r.text && "CHANGES" !in r.text, r.text.take(300))
        },
        Scenario("D4", "read", "changes_only on the first look of a run is the whole screen") {
            phone.show(screen(APP, button("buy", "Buy", 300, 1000, 780, 1100)))
            val r = call("read_screen", mapOf("changes_only" to true))
            check("the whole grid came back", "+----" in r.text, r.text.take(300))
        },
        Scenario("D5", "read", "query finds an element and its som_id taps it") {
            phone.show(screen(APP, *(0 until 30).map { row("r$it", "Song $it", 100 + it * 70, 160 + it * 70) }.toTypedArray()))
            val r = call("read_screen", mapOf("query" to "song 17"))
            check("one match, no grid", "MATCHES" in r.text && "+----" !in r.text && "Song 17" in r.text, r.text.take(400))
            val id = Regex("\\n(\\d+)\\s").find(r.text.substringAfter("som  in   flg  label"))?.groupValues?.get(1)?.toInt()
            call("tap", mapOf("som_id" to id))
            expectHit("r17")
        },
        Scenario("D6", "read", "query for something not there says so and points off-screen") {
            val list = box("list", 0, 400, 1080, 1400) { cls = "android.widget.ListView"; scrollable = true }
                .add(*(0 until 12).map { row("r$it", "Item $it", 400 + it * 200, 600 + it * 200) }.toTypedArray())
            phone.show(screen(APP, list))
            val r = call("read_screen", mapOf("query" to "Item 11"))
            check("not on screen, but off-screen", "NO on-screen element" in r.text && "OFFSCREEN" in r.text, r.text.take(300))
        },

        // ── clipboard ──
        Scenario("C1", "clipboard", "Write then read the clipboard") {
            phone.show(screen(APP, label("t", "Hi", 0, 100, 1080, 200)))
            val w = call("clipboard", mapOf("action" to "write", "text" to "221B Baker Street"))
            val r = call("clipboard", mapOf("action" to "read"))
            check("write ok", !w.isError, w.text)
            check("read back", r.str("text") == "221B Baker Street", r.text)
        },
        Scenario("C2", "clipboard", "A refused clipboard read is an error, not an empty clipboard") {
            phone.show(screen(APP, label("t", "Hi", 0, 100, 1080, 200)))
            phone.clipboard = "secret"
            phone.clipboardReadable = false
            val r = call("clipboard", mapOf("action" to "read"))
            check("error that says why", r.isError && "background" in r.text, r.text)
        },
        Scenario("C3", "clipboard", "Copying a card number to the clipboard is blocked") {
            phone.show(screen(APP, label("t", "Hi", 0, 100, 1080, 200)))
            val r = call("clipboard", mapOf("action" to "write", "text" to "4111 1111 1111 1111"))
            check("blocked", r.isError, r.text.take(200))
            check("clipboard untouched", phone.clipboard == null, "${phone.clipboard}")
        },
        Scenario("C4", "clipboard", "Reading a copied card number or code is blocked") {
            phone.show(screen(APP, label("t", "Hi", 0, 100, 1080, 200)))
            phone.clipboard = "4111 1111 1111 1111"
            val r = call("clipboard", mapOf("action" to "read"))
            check("blocked", r.isError, r.text.take(200))
            check("the number is not returned", "4111" !in r.text, r.text.take(200))
        },

        // ── read ──
        Scenario("R1", "read", "Rows scrolled out of a list are still named") {
            val list = box("list", 0, 400, 1080, 1400) { cls = "android.widget.ListView"; scrollable = true }
                .add(*(0 until 12).map { row("r$it", "Item $it", 400 + it * 200, 600 + it * 200) }.toTypedArray())
            phone.show(screen(APP, list))
            val r = read()
            check("an off-screen row is mentioned", "Item 11" in r.text, r.text.takeLast(400))
            check("an off-screen row has no som_id", som("r11") == null)
        },
        Scenario("R2", "read", "A 500-element screen reads in under 1.5 s") {
            val grid = (0 until 500).map { i ->
                val x = (i % 10) * 108
                val y = 100 + (i / 10) * 44
                button("g$i", "Cell $i", x, y, x + 108, y + 44)
            }
            phone.show(screen(APP, *grid.toTypedArray()))
            val t0 = System.currentTimeMillis()
            val r = read()
            val ms = System.currentTimeMillis() - t0
            check("read_screen succeeded", !r.isError, r.text.take(200))
            check("took ${ms} ms", ms < 1_500)
            tap("g499")
            expectHit("g499")
        },
    )
}
