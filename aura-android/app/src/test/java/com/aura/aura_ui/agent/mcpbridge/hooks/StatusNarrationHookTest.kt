package com.aura.aura_ui.agent.mcpbridge.hooks

import com.aura.aura_ui.services.AgentStatusRegistry.Beat
import com.aura.aura_ui.services.AgentStatusRegistry.Kind
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lines the on-screen status strip shows (spec 2026-09-23 §3.8).
 *
 * The bars: it names what is **really** there (from the result, never the request); it says
 * where the run is (the plan headline); it never leaks jargon and is never blank; and only a
 * moment that needs the user is coloured.
 */
class StatusNarrationHookTest {

    private val hook = StatusNarrationHook(
        appLabel = { pkg -> if (pkg == "com.google.android.youtube") "YouTube" else null },
    )

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun result(text: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(text)))

    /** A screen read, as `ScreenPayload` renders it: the SEEN header above the grid. */
    private fun screenRead(pkg: String, vararg labels: String): CallToolResult =
        result((listOf("SEEN: $pkg") + labels).joinToString(" | ") + "\nSCREEN 1080x2400  $pkg  IDLE")

    private fun observed(topLabel: String? = null, changed: Boolean? = null): CallToolResult {
        val fields = listOfNotNull(
            topLabel?.let { "\"top_labels\":[\"$it\"]" },
            changed?.let { "\"screen_changed\":$it" },
        ).joinToString(",")
        return result("{\"post_action_observation\":{$fields}}")
    }

    private val empty = CallToolResult(content = emptyList())

    // ── it names what is actually there ────────────────────────────────

    @Test fun `a screen read names the app and what is on it`() {
        assertEquals(
            Beat(Kind.LOOK, "On YouTube · Home, Shorts, Subscriptions"),
            hook.narrate("read_screen", args(), screenRead("com.google.android.youtube", "Home", "Shorts", "Subscriptions")),
        )
    }

    @Test fun `a tap names what it touched and where it landed`() {
        val r = result("{\"action\":\"tap\",\"label\":\"Playlists\",\"post_action_observation\":{\"top_labels\":[\"Library\"]}}")
        assertEquals(Beat(Kind.ACT, "Tapped Playlists → Library"), hook.narrate("tap", args("som_id" to "12"), r))
    }

    @Test fun `a tap that changed nothing says so`() {
        assertEquals("Tapped — nothing moved yet", hook.narrate("tap", args(), observed(changed = false)).text)
    }

    @Test fun `a scroll that moved nothing says it reached the end`() {
        assertEquals("Scrolled — that's the end of it", hook.narrate("scroll_down", args(), observed(changed = false)).text)
    }

    // ── what kind of moment it is ──────────────────────────────────────

    @Test fun `each moment gets its kind`() {
        assertEquals(Kind.TYPE, hook.narrate("type_text", args("text" to "hi"), empty).kind)
        assertEquals(Kind.NAV, hook.narrate("launch_app", args("app_name" to "YouTube"), empty).kind)
        assertEquals(Kind.SEARCH, hook.narrate("web_search", args("query" to "x"), empty).kind)
        assertEquals(Kind.DONE, hook.narrate("end_session", args("outcome" to "success"), empty).kind)
    }

    @Test fun `only moments that need the user are held`() {
        assertEquals(Kind.HOLD, hook.narrate("end_session", args("outcome" to "partial"), empty).kind)
        assertEquals(Kind.HOLD, hook.narrateFailure(result("{\"error\":\"paused_by_user\"}")).kind)
        assertEquals(Kind.HOLD, hook.narrateFailure(result("{\"error\":\"policy_blocked\"}")).kind)
        assertEquals(Kind.RECOVER, hook.narrateFailure(result("som_id 4 is STALE: screen changed")).kind)
        assertEquals(Kind.RECOVER, hook.narrateFailure(empty).kind)
    }

    // ── where the run is ───────────────────────────────────────────────

    @Test fun `the headline is the step in progress, counted`() {
        assertEquals(
            "Step 2 of 3 · Open the chat",
            StatusNarrationHook.headlineFor(listOf("Open WhatsApp" to true, "Open the chat" to false, "Send" to false)),
        )
        assertEquals("All 2 steps done · checking", StatusNarrationHook.headlineFor(listOf("a" to true, "b" to true)))
        assertNull("no plan, no headline", StatusNarrationHook.headlineFor(emptyList()))
    }

    @Test fun `the published line carries the headline and lands in the log`() {
        var published: Beat? = null
        val withPlan = StatusNarrationHook(headline = { "Step 1 of 2 · Find the chat" }, publish = { published = it })
        kotlinx.coroutines.runBlocking { withPlan.onPostTool("press_home", args(), empty, HookContext { true }) }
        assertEquals(Beat(Kind.NAV, "Home screen", "Step 1 of 2 · Find the chat"), published)
    }

    // ── the fallbacks, which matter more ───────────────────────────────

    @Test fun `no line leaks jargon and none is blank`() {
        val jargon = listOf("som", "package", "tool", "json", "_")
        val tools = listOf(
            "tap", "read_screen", "perceive_screen", "type_text", "launch_app", "browser_open",
            "web_search", "end_session", "mark_step", "get_battery_level",
        )
        tools.forEach { name ->
            val line = hook.narrate(name, args("som_id" to "12"), result("{\"action\":\"tap\",\"som_id\":12}")).text
            assertTrue("$name narrated to blank", line.isNotBlank())
            jargon.forEach { bad -> assertFalse("'$bad' leaked into: $line", line.lowercase().contains(bad)) }
        }
    }

    @Test fun `a malformed result degrades to a plain line instead of throwing`() {
        assertEquals("Taking a look", hook.narrate("read_screen", args(), result("{not json at all")).text)
    }

    @Test fun `a url is shown as its domain`() {
        assertEquals("Opened google.com", hook.narrate("browser_open", args("url" to "https://www.google.com/search?q=x"), empty).text)
    }

    @Test fun `an unknown package still gets a readable name`() {
        assertEquals("Looking at Music", hook.narrate("read_screen", args(), screenRead("com.spotify.music")).text)
    }

    @Test fun `typed text is truncated so it cannot overflow the strip`() {
        assertTrue(hook.narrate("type_text", args("text" to "x".repeat(200)), empty).text.length < 60)
    }
}
