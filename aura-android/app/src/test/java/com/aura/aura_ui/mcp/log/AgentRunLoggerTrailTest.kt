package com.aura.aura_ui.mcp.log

import org.robolectric.RuntimeEnvironment
import com.aura.aura_ui.agent.llm.AgentLlmTap
import com.aura.aura_ui.agent.llm.AgentTraceTap
import com.aura.aura_ui.agent.research.TaskResearch
import com.aura.mcp.bridge.CaptureResult
import com.aura.mcp.bridge.ScreenshotBridge
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * One fake run through the real logger and writer: what lands on disk is what the Logs screen
 * will show, so this checks the file, not the logger's memory.
 */
@RunWith(RobolectricTestRunner::class)
class AgentRunLoggerTrailTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()
    private val noShots = object : ScreenshotBridge {
        override fun isReady() = false
        override fun requestPermission() = false
        override suspend fun captureBase64Png(): CaptureResult = CaptureResult.Error("none in tests")
    }

    @Test fun `a run lands untrimmed, with its trail, target and research on disk`() {
        val root = File(context.filesDir, "mcp_logs").apply { deleteRecursively() }
        val logger = AgentRunLogger(context, noShots)
        logger.startRun("text Amma I'm late", "test")

        val bigRequest = "{\"messages\":\"" + "x".repeat(200_000) + "\"}"
        val call = { purpose: String? ->
            logger.onLlmCall(
                provider = "p", model = "m", prompt = "delta", response = "ok",
                promptTokens = 1, completionTokens = 1, totalTokens = 2, durationMs = 5,
                requestBody = bigRequest, responseBody = "{\"r\":1}", purpose = purpose,
            )
        }
        call(null)
        call(AgentLlmTap.PURPOSE_RESEARCH_PHRASE)
        logger.onToolStart("tap", "{\"som_id\":7}")
        AgentTraceTap.reportStep("tap", "pre-hook", "ActionGuard", "proceed", null, 1)
        AgentTraceTap.reportStep("tap", "gate", "SensitivePolicy", "pass")
        AgentTraceTap.reportStep("tap", "target", "7", "info", "10,20,110,80")
        AgentTraceTap.reportStep("web_search", "gate", "Scope", "pass") // the research's own call
        logger.onToolEnd("tap", success = true, outputSummary = "R".repeat(50_000), durationMs = 9)
        AgentTraceTap.reportResearch(
            TaskResearch.ResearchTrace("WhatsApp", "send a message", "q", listOf("step"), "faq.whatsapp.com", "note", "found", 300),
        )
        logger.endRun("completed")

        val s = awaitSession(root) { it.endReason != null }
        val dir = File(root, s.sessionId)
        assertEquals(bigRequest, File(dir, s.llmCalls[0].rawRequestFile!!).readText())
        assertEquals("research-phrase", s.llmCalls[1].purpose)

        val tap = s.invocations.single()
        assertEquals("a tool call links to the agent's turn, not the side call", 0, tap.llmCallIndex)
        assertEquals(50_000, File(dir, tap.rawResultFile!!).readText().length)
        assertEquals(listOf("pre-hook", "gate", "target"), tap.trail.map { it.stage })
        assertEquals("10,20,110,80", tap.targetBounds)
        assertEquals(7, tap.targetSomId)

        val research = requireNotNull(s.research) { "the research record must land" }
        assertEquals("faq.whatsapp.com", research.source)
        assertTrue("the research's web_search step is kept off the tap", research.serverTrail.any { it.name == "Scope" })
    }

    private fun awaitSession(root: File, until: (SessionLog) -> Boolean): SessionLog {
        val json = Json { ignoreUnknownKeys = true }
        repeat(100) {
            root.listFiles()?.firstOrNull()?.let { File(it, "metadata.json") }?.takeIf { it.exists() }?.let { f ->
                runCatching { json.decodeFromString(SessionLog.serializer(), f.readText()) }.getOrNull()
                    ?.takeIf(until)?.let { return it }
            }
            Thread.sleep(50)
        }
        error("session never finished writing")
    }
}
