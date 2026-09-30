package com.aura.aura_ui.mcp

import com.aura.aura_ui.agent.memory.FactKind
import com.aura.aura_ui.agent.memory.LearningsStore
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore : LearningsStore {
    val paths = mutableListOf<Pair<String, String>>() // app to goalType
    val facts = mutableListOf<FactKind>()
    var lastSource: String? = null
    override suspend fun hintsFor(appPackage: String, goalType: String) = ""
    override suspend fun appHints(appPackage: String, installedAppVersion: String?) = "# Learned hints for $appPackage"
    override suspend fun recordVerifiedPath(
        appPackage: String,
        goalType: String,
        steps: List<String>,
        recoveries: List<String>,
        appVersion: String,
        source: String,
        goalLabel: String,
        endsAt: String,
    ) {
        paths += appPackage to goalType
        lastSource = source
    }
    override suspend fun recordFacts(
        appPackage: String,
        kind: FactKind,
        texts: List<String>,
        appVersion: String,
        source: String,
    ) {
        facts += kind
    }
}

class AppLearningsGatewayTest {

    private fun successResult() = CallToolResult(
        content = listOf(TextContent("""{"success":true}""")),
        isError = false,
    )

    /** A gesture result carrying an E2 post_action_observation block. */
    private fun observed(screenChanged: Boolean, foregroundApp: String = "") = CallToolResult(
        content = listOf(
            TextContent("""{"success":true}"""),
            TextContent(
                """{"post_action_observation":{"settled":true,"screen_changed":$screenChanged,""" +
                    """"foreground_app":"$foregroundApp","element_count":10}}""",
            ),
        ),
        isError = false,
    )

    @Test fun `successful session with success outcome records path with client source`() = runTest {
        val store = FakeStore()
        val gw = AppLearningsGateway.forTest(store)
        gw.onToolExecuted("launch_app", buildJsonObject { put("package_name", "com.whatsapp") }, successResult(), failed = false, clientLabel = "Claude Code")
        gw.onToolExecuted("tap", buildJsonObject { put("text", "Chats") }, observed(screenChanged = true, foregroundApp = "com.whatsapp"), failed = false, clientLabel = "Claude Code")
        gw.onSessionEnd("sent a whatsapp message", outcome = "success", goalType = null)
        assertEquals("com.whatsapp" to "send_message", store.paths.single())
        assertEquals("Claude Code", store.lastSource)
    }

    @Test fun `absent outcome records no path`() = runTest {
        val store = FakeStore()
        val gw = AppLearningsGateway.forTest(store)
        gw.onToolExecuted("launch_app", buildJsonObject { put("package_name", "com.whatsapp") }, successResult(), failed = false, clientLabel = null)
        gw.onSessionEnd("did things", outcome = null, goalType = null)
        assertTrue(store.paths.isEmpty())
    }

    @Test fun `streaming the end_session tool call itself never verifies`() = runTest {
        val store = FakeStore()
        val gw = AppLearningsGateway.forTest(store)
        gw.onToolExecuted("launch_app", buildJsonObject { put("package_name", "com.whatsapp") }, successResult(), failed = false, clientLabel = null)
        // A client calls end_session without outcome; the dispatch stream must not
        // structurally verify the MCP session the way the agent hook lane does.
        gw.onToolExecuted("end_session", buildJsonObject { put("reason", "done") }, successResult(), failed = false, clientLabel = null)
        gw.onSessionEnd("done", outcome = null, goalType = null)
        assertTrue(store.paths.isEmpty())
    }

    @Test fun `recovery pairs persist even when the session fails`() = runTest {
        val store = FakeStore()
        val gw = AppLearningsGateway.forTest(store)
        gw.onToolExecuted("launch_app", buildJsonObject { put("package_name", "com.spotify.music") }, successResult(), failed = false, clientLabel = null)
        gw.onToolExecuted("tap", buildJsonObject { put("text", "Search") }, observed(screenChanged = false, foregroundApp = "com.spotify.music"), failed = false, clientLabel = null)
        gw.onToolExecuted("tap", buildJsonObject { put("som_id", "12"); put("label", "Search box") }, observed(screenChanged = true, foregroundApp = "com.spotify.music"), failed = false, clientLabel = null)
        gw.onSessionEnd("could not finish", outcome = "failure", goalType = null)
        assertTrue(store.paths.isEmpty())
        assertEquals(listOf(FactKind.RECOVERY), store.facts)
    }

    @Test fun `explicit goal_type wins`() = runTest {
        val store = FakeStore()
        val gw = AppLearningsGateway.forTest(store)
        gw.onToolExecuted("launch_app", buildJsonObject { put("package_name", "com.whatsapp") }, successResult(), failed = false, clientLabel = null)
        gw.onToolExecuted("tap", buildJsonObject { put("text", "Chats") }, observed(screenChanged = true, foregroundApp = "com.whatsapp"), failed = false, clientLabel = null)
        gw.onSessionEnd("sent a message", outcome = "success", goalType = "navigate")
        assertEquals("navigate", store.paths.single().second)
    }

    @Test fun `session resets after end - next session accumulates fresh`() = runTest {
        val store = FakeStore()
        val gw = AppLearningsGateway.forTest(store)
        gw.onToolExecuted("launch_app", buildJsonObject { put("package_name", "com.whatsapp") }, successResult(), failed = false, clientLabel = null)
        gw.onToolExecuted("tap", buildJsonObject { put("text", "Chats") }, observed(screenChanged = true, foregroundApp = "com.whatsapp"), failed = false, clientLabel = null)
        gw.onSessionEnd("done", outcome = "success", goalType = null)
        // fresh session, no steps → nothing new recorded on next end
        gw.onSessionEnd("nothing happened", outcome = "success", goalType = null)
        assertEquals(1, store.paths.size)
    }

    @Test fun `hintsFor resolves human app names to packages`() = runTest {
        val store = FakeStore()
        val gw = AppLearningsGateway.forTest(store)
        assertTrue(gw.hintsFor("whatsapp").contains("com.whatsapp"))
        assertTrue(gw.hintsFor("com.spotify.music").contains("com.spotify.music"))
    }
}
