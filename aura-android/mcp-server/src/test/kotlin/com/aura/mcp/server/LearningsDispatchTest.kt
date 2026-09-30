package com.aura.mcp.server

import com.aura.mcp.bridge.LearningsGateway
import com.aura.mcp.bridge.NoOpLearningsGateway
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Spec 2026-07-17 — shared-memory dispatch glue: streaming, hint injection, arg parsing. */
class LearningsDispatchTest {

    private class RecordingGateway(private val hints: String = "") : LearningsGateway {
        val executed = mutableListOf<Triple<String, Boolean, String?>>()
        var sessionEnd: Triple<String, String?, String?>? = null
        override fun onToolExecuted(
            toolName: String,
            args: JsonObject?,
            result: CallToolResult,
            failed: Boolean,
            clientLabel: String?,
        ) {
            executed += Triple(toolName, failed, clientLabel)
        }
        override suspend fun onSessionEnd(reason: String, outcome: String?, goalType: String?) {
            sessionEnd = Triple(reason, outcome, goalType)
        }
        override suspend fun hintsFor(appPackage: String): String = hints
    }

    private class ThrowingGateway : LearningsGateway {
        override fun onToolExecuted(
            toolName: String,
            args: JsonObject?,
            result: CallToolResult,
            failed: Boolean,
            clientLabel: String?,
        ): Nothing = error("boom")
        override suspend fun onSessionEnd(reason: String, outcome: String?, goalType: String?): Nothing = error("boom")
        override suspend fun hintsFor(appPackage: String): Nothing = error("boom")
    }

    private fun ok() = CallToolResult(content = listOf(TextContent("""{"success":true}""")), isError = false)

    @Test
    fun `stream forwards call details to the gateway`() {
        val gateway = RecordingGateway()
        LearningsDispatch.stream(gateway, "echo", null, ok(), failed = false, clientLabel = "Claude Code")
        assertEquals(Triple("echo", false, "Claude Code"), gateway.executed.single())
    }

    @Test
    fun `a throwing gateway never breaks streaming`() {
        LearningsDispatch.stream(ThrowingGateway(), "echo", null, ok(), failed = false, clientLabel = null)
        // no exception = pass
    }

    @Test
    fun `hintTargetApp prefers package_name and falls back to app_name`() {
        assertEquals(
            "com.whatsapp",
            LearningsDispatch.hintTargetApp(
                buildJsonObject { put("package_name", JsonPrimitive("com.whatsapp")); put("app_name", JsonPrimitive("WhatsApp")) },
            ),
        )
        assertEquals(
            "WhatsApp",
            LearningsDispatch.hintTargetApp(buildJsonObject { put("app_name", JsonPrimitive("WhatsApp")) }),
        )
        assertEquals(null, LearningsDispatch.hintTargetApp(null))
        assertEquals(null, LearningsDispatch.hintTargetApp(buildJsonObject { put("text", JsonPrimitive("x")) }))
    }

    @Test
    fun `withLearnedHints appends a learned_hints block on success`() = runBlocking {
        val gateway = RecordingGateway(hints = "# Learned hints for com.whatsapp\n• A path that worked before")
        val result = LearningsDispatch.withLearnedHints(
            gateway,
            "launch_app",
            buildJsonObject { put("package_name", JsonPrimitive("com.whatsapp")) },
            ok(),
        )
        val texts = result.content.filterIsInstance<TextContent>().mapNotNull { it.text }
        assertTrue(texts.any { it.contains("learned_hints") && it.contains("worked before") })
    }

    @Test
    fun `no hints means the result is unchanged`() = runBlocking {
        val original = ok()
        val result = LearningsDispatch.withLearnedHints(
            RecordingGateway(hints = ""),
            "launch_app",
            buildJsonObject { put("package_name", JsonPrimitive("com.whatsapp")) },
            original,
        )
        assertSame(original, result)
    }

    @Test
    fun `hints are only injected for hint tools and successful results`() = runBlocking {
        val gateway = RecordingGateway(hints = "# hints")
        val original = ok()
        // wrong tool
        assertSame(original, LearningsDispatch.withLearnedHints(gateway, "tap", buildJsonObject { put("package_name", JsonPrimitive("x")) }, original))
        // errored result
        val errored = CallToolResult(content = listOf(TextContent("err")), isError = true)
        assertSame(errored, LearningsDispatch.withLearnedHints(gateway, "launch_app", buildJsonObject { put("package_name", JsonPrimitive("x")) }, errored))
    }

    @Test
    fun `a throwing gateway leaves the result unchanged`() = runBlocking {
        val original = ok()
        val result = LearningsDispatch.withLearnedHints(
            ThrowingGateway(),
            "launch_app",
            buildJsonObject { put("package_name", JsonPrimitive("com.whatsapp")) },
            original,
        )
        assertSame(original, result)
    }

    @Test
    fun `outcome and goal_type parse against closed sets`() {
        assertEquals("success", LearningsDispatch.parseOutcome("success"))
        assertEquals("failure", LearningsDispatch.parseOutcome("failure"))
        assertEquals(null, LearningsDispatch.parseOutcome("maybe"))
        assertEquals(null, LearningsDispatch.parseOutcome(null))
        assertEquals("send_message", LearningsDispatch.parseGoalType("send_message"))
        assertEquals(null, LearningsDispatch.parseGoalType("world_domination"))
    }

    @Test
    fun `ServerSinks defaults to the NoOp gateway`() {
        assertSame(NoOpLearningsGateway, ServerSinks().learnings)
    }
}
