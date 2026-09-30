package com.aura.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E2 — post-action observation bundling. Every WRITE gesture result carries a
 * compact settled post-state so the model verifies the previous action AND
 * plans the next one in a single round trip (no perceive-to-verify call).
 */
class PostActionObservationTest {

    private val config = ScreenSettle.Config(noChangeProbeMs = 250, quietMs = 300, maxMs = 1_500)

    @Test
    fun `consecutive gestures each compare against the step before them`() = runBlocking<Unit> {
        // launch_app -> type_text -> press_enter runs with NO perceive between steps —
        // that is the whole point of bundling observations. Anchoring to the last
        // perceive would make step 2 report "changed" because step 1 moved things.
        val clock = FakeClock()
        val bridge = ScriptedBridge(clock)
        val lastSeen = com.aura.mcp.cache.LastSeenScreen()
        lastSeen.set(com.aura.mcp.tools.ScreenSignature.of(tree(label = "Home")))
        val observer = PostActionObservation.observer(bridge, config, clock::now, lastSeen)

        // Step 1 genuinely navigates.
        bridge.treeJson = tree(label = "Inbox")
        val first = observer.observe("tap")
        assertEquals(true, first!!["screen_changed"]?.jsonPrimitive?.boolean)

        // Step 2 lands on the same screen and does nothing. It must NOT inherit
        // step 1's change.
        val second = observer.observe("tap")
        assertEquals(
            false,
            second!!["screen_changed"]?.jsonPrimitive?.boolean,
            "a second tap that did nothing must not report the first tap's change",
        )
    }

    @Test
    fun `slow-to-start actions keep the longer probe window`() = runBlocking<Unit> {
        // A cold app launch can be silent for longer than a tap ever is. Exiting at
        // 250ms would report "nothing happened" for a launch that simply had not begun.
        val clock = FakeClock()
        val observer = PostActionObservation.observer(ScriptedBridge(clock), config, clock::now)

        observer.observe("launch_app")
        val slow = clock.now()
        assertTrue(
            slow >= config.slowStartProbeMs,
            "launch_app must wait the slow-start probe, waited ${slow}ms",
        )

        val tapClock = FakeClock()
        val tapObserver = PostActionObservation.observer(ScriptedBridge(tapClock), config, tapClock::now)
        tapObserver.observe("tap")
        assertTrue(
            tapClock.now() <= config.noChangeProbeMs,
            "a tap must still take the fast path, waited ${tapClock.now()}ms",
        )
    }

    @Test
    fun `every slow-to-start tool is actually observed`() {
        PostActionObservation.SLOW_TO_START.forEach {
            assertTrue(it in PostActionObservation.OBSERVED_TOOLS, "$it must be observed to be settled")
        }
    }

    @Test
    fun `observer returns null for tools that do not change the screen`() = runBlocking<Unit> {
        val clock = FakeClock()
        val observer = PostActionObservation.observer(ScriptedBridge(clock), config, clock::now)
        assertNull(observer.observe("volume_up"))
        assertNull(observer.observe("mute"))
        assertNull(observer.observe("perceive_screen"))
        assertNull(observer.observe("end_session"))
    }

    @Test
    fun `observer bundles the settled post-state for a tap`() = runBlocking<Unit> {
        val clock = FakeClock()
        val bridge = ScriptedBridge(clock)
        bridge.treeJson = """{"package_name":"in.amazon.mShop","elements_count":42,"elements":[
            {"text":"Add to Cart","isClickable":true},
            {"text":"Buy Now","isClickable":true}
        ]}"""
        val observer = PostActionObservation.observer(bridge, config, clock::now)

        val obs = observer.observe("tap")
        assertNotNull(obs)
        assertEquals("in.amazon.mShop", obs.jsonObject["foreground_app"]?.jsonPrimitive?.content)
        assertEquals("42", obs.jsonObject["element_count"]?.jsonPrimitive?.content)
        assertEquals(true, obs.jsonObject["settled"]?.jsonPrimitive?.boolean)
        assertEquals(false, obs.jsonObject["screen_changed"]?.jsonPrimitive?.boolean)
        assertNotNull(obs.jsonObject["top_labels"], "top labels must ride along")
        assertNotNull(obs.jsonObject["hint"], "the model needs to be told how to use the bundle")
    }

    @Test
    fun `observer marks screen_changed when events were seen`() = runBlocking<Unit> {
        val clock = FakeClock()
        val bridge = ScriptedBridge(
            clock,
            { _, _ -> clock.advance(0); emptyList() },
            { _, _ ->
                clock.advance(100)
                listOf(com.aura.mcp.bridge.DeviceEvent("TYPE_WINDOW_STATE_CHANGED", "com.amazon", 0L, "x"))
            },
            { t, _ -> clock.advance(t); emptyList() },
        )
        val observer = PostActionObservation.observer(bridge, config, clock::now)
        val obs = observer.observe("launch_app")
        assertNotNull(obs)
        assertEquals(true, obs.jsonObject["screen_changed"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `appendTo adds a second content block and preserves the original`() {
        val original = CallToolResult(
            content = listOf(TextContent("""{"success":true,"action":"tap"}""")),
            isError = false,
        )
        val obs = Json.parseToJsonElement("""{"settled":true}""").jsonObject
        val out = PostActionObservation.appendTo(original, obs)

        assertEquals(2, out.content.size)
        assertEquals("""{"success":true,"action":"tap"}""", (out.content[0] as TextContent).text)
        val second = (out.content[1] as TextContent).text.orEmpty()
        assertTrue("post_action_observation" in second, "bundle must be labeled")
        assertFalse(out.isError == true, "isError must be preserved")
    }

    @Test
    fun `every observed tool is WRITE-scoped`() {
        PostActionObservation.OBSERVED_TOOLS.forEach { tool ->
            assertEquals(
                com.aura.mcp.bridge.McpScope.WRITE,
                McpToolScopes.requiredScopeFor(tool),
                "observed tool $tool must be WRITE-scoped",
            )
        }
    }
}
