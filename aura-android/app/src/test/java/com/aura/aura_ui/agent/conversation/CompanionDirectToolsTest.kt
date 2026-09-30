package com.aura.aura_ui.agent.conversation

import com.aura.mcp.bridge.MediaBridge
import com.aura.mcp.bridge.MediaCommand
import com.aura.mcp.bridge.MediaCommandResult
import com.aura.mcp.bridge.MediaSessionSnapshot
import com.aura.mcp.bridge.SpecialAccessState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fast lane after the cutover: four instant device calls and nothing else.
 *
 * Web search, notifications and system intents used to be tested here. They moved behind
 * `ask_aura` — see [CompanionToolsTest] — because none of them is a sub-100 ms deterministic
 * device call. Their bridges are unchanged and still reached from the action plane.
 */
class CompanionDirectToolsTest {

    private class FakeDeviceContext(private val s: DeviceContextSnapshot) : DeviceContextProvider {
        override suspend fun snapshot() = s
    }

    private class FakeMedia(
        private val access: SpecialAccessState = SpecialAccessState.ENABLED,
        var lastCommand: MediaCommand? = null,
        private val succeeds: Boolean = true,
    ) : MediaBridge {
        override fun accessState() = access
        override suspend fun activeSessions(): List<MediaSessionSnapshot> = emptyList()
        override suspend fun sendCommand(packageName: String?, command: MediaCommand): MediaCommandResult {
            lastCommand = command
            return MediaCommandResult(succeeds, packageName, if (succeeds) null else "nothing playing")
        }
    }

    private class FakeControls(
        var lastSetting: Pair<String, String?>? = null,
        var lastAction: String? = null,
        private val ok: Boolean = true,
    ) : DeviceControls {
        override suspend fun applySetting(setting: String, state: String?): ControlResult {
            lastSetting = setting to state
            return if (ok) ControlResult("Torch on.", ok = true) else ControlResult("Can't do that.", ok = false)
        }
        override suspend fun doAction(action: String): ControlResult {
            lastAction = action
            return ControlResult("Home.", ok = true)
        }
    }

    private fun tools(
        media: MediaBridge = FakeMedia(),
        ctx: DeviceContextProvider = FakeDeviceContext(sampleSnapshot()),
        controls: DeviceControls = FakeControls(),
    ) = CompanionDirectTools(media, ctx, controls)

    private fun sampleSnapshot() = DeviceContextSnapshot(
        "3:00 PM", 50, false, null, null, true, false, false,
    )

    private fun args(vararg pairs: Pair<String, String>) =
        JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    private suspend fun CompanionDirectTools.call(name: String, args: JsonObject = JsonObject(emptyMap())) =
        handle(CompanionToolCall(name, args), EscalationSink.NONE)

    // ── ownership ─────────────────────────────────────────────────────────────

    @Test
    fun `owns exactly the four instant device tools`() {
        val t = tools()
        listOf("device_context", "media_control", "device_settings", "device_action")
            .forEach { assertTrue("$it must be in the fast lane", t.handles(it)) }
    }

    /** Everything requiring judgment belongs to ask_aura now, not here. */
    @Test
    fun `does not own the tools that moved behind ask_aura`() {
        val t = tools()
        listOf("web_search", "read_notifications", "notification_action", "system_intent", "ask_aura")
            .forEach { assertFalse("$it must NOT be in the fast lane", t.handles(it)) }
    }

    // ── device_context ────────────────────────────────────────────────────────

    @Test
    fun `device_context speaks a summary of the phone state`() = runTest {
        val r = tools().call("device_context")
        assertFalse(r.isError)
        assertTrue(r.text.contains("3:00 PM"))
    }

    // ── media_control ─────────────────────────────────────────────────────────

    @Test
    fun `media_control forwards a parsed command`() = runTest {
        val media = FakeMedia()
        val r = tools(media = media).call("media_control", args("command" to "next"))
        assertFalse(r.isError)
        assertEquals(MediaCommand.NEXT, media.lastCommand)
    }

    @Test
    fun `an unknown media command lists the valid ones instead of failing silently`() = runTest {
        val r = tools().call("media_control", args("command" to "rewind"))
        assertTrue(r.isError)
        assertTrue(r.text.contains("play_pause"))
    }

    @Test
    fun `media_control without notification access explains what to enable`() = runTest {
        val r = tools(media = FakeMedia(access = SpecialAccessState.DISABLED))
            .call("media_control", args("command" to "play"))
        assertTrue(r.isError)
        assertTrue(r.text.contains("Notification access"))
    }

    @Test
    fun `a failed media command reports the bridge's reason`() = runTest {
        val r = tools(media = FakeMedia(succeeds = false)).call("media_control", args("command" to "play"))
        assertTrue(r.isError)
        assertTrue(r.text.contains("nothing playing"))
    }

    // ── device_settings / device_action ───────────────────────────────────────

    @Test
    fun `device_settings passes the setting and state through`() = runTest {
        val controls = FakeControls()
        val r = tools(controls = controls).call("device_settings", args("setting" to "flashlight", "state" to "on"))
        assertFalse(r.isError)
        assertEquals("flashlight" to "on", controls.lastSetting)
    }

    @Test
    fun `device_settings without a setting is a model-readable error`() = runTest {
        val r = tools().call("device_settings")
        assertTrue(r.isError)
    }

    @Test
    fun `a refused setting surfaces as an error the model can speak`() = runTest {
        val r = tools(controls = FakeControls(ok = false)).call("device_settings", args("setting" to "wifi"))
        assertTrue(r.isError)
        assertTrue(r.text.contains("Can't do that."))
    }

    @Test
    fun `device_action passes the action through`() = runTest {
        val controls = FakeControls()
        val r = tools(controls = controls).call("device_action", args("action" to "home"))
        assertFalse(r.isError)
        assertEquals("home", controls.lastAction)
    }

    @Test
    fun `device_action without an action is a model-readable error`() = runTest {
        assertTrue(tools().call("device_action").isError)
    }

    // ── never throws ──────────────────────────────────────────────────────────

    @Test
    fun `a tool this lane does not own becomes an error, not an exception`() = runTest {
        val r = tools().call("web_search", args("query" to "x"))
        assertTrue(r.isError)
        assertTrue(r.text.contains("not a direct tool"))
    }
}
