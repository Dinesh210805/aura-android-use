package com.aura.aura_ui.agent.proof

import com.aura.aura_ui.agent.ledger.PlanStep
import com.aura.aura_ui.agent.ledger.RunLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric because [StepCheckPrompt.parse] reads with `org.json`, like [JudgePrompt]. */
@RunWith(RobolectricTestRunner::class)
class StepCheckPromptTest {

    private val template = "{{GOAL}}|{{STEP}}|{{EVIDENCE}}|{{STEPS}}|{{SCREEN}}"

    @Test fun `fill uses only the newest screen`() {
        val observed = ObservedText().apply { record("old screen with Pause"); record("new screen with Play") }
        val ledger = RunLedger(
            runId = "r", goal = "play a song", provider = "p", modelId = "m", startedAtMs = 0L,
            planSteps = listOf(PlanStep("play a song on Spotify")),
        )
        val prompt = StepCheckPrompt.fill(template, ledger, 0, "Pause shows", observed)
        assertTrue(prompt.startsWith("play a song|play a song on Spotify|Pause shows|(none)|"))
        assertTrue(prompt.contains("new screen with play"))
        assertFalse("an older screen must not prove the step", prompt.contains("old screen"))
    }

    @Test fun `the grid is stripped so labels low on the screen still reach the checker`() {
        // A read_screen result is mostly box borders; the first 5,000 raw chars were all grid.
        val raw = "SCREEN 1240x2772\n+" + "-".repeat(6000) + "+\n|" + " ".repeat(3000) +
            "|102 Preview   |91 Add stops|\n+" + "-".repeat(200) + "+"
        val text = StepCheckPrompt.screenText(raw)
        assertTrue(text.contains("102 Preview 91 Add stops"))
        assertTrue(text.length < 100)
    }

    @Test fun `a gesture with no read after it marks the newest read stale`() = kotlinx.coroutines.test.runTest {
        val observed = ObservedText()
        val ok = io.modelcontextprotocol.kotlin.sdk.types.CallToolResult(content = emptyList(), isError = false)
        val args = kotlinx.serialization.json.JsonObject(emptyMap())
        val ctx = com.aura.aura_ui.agent.mcpbridge.hooks.HookContext(confirm = { false })
        observed.record("screen with Play")
        observed.onPostTool("tap", args, ok, ctx)
        assertTrue(observed.changedSinceRead)
        observed.record("screen with Pause")
        assertFalse(observed.changedSinceRead)
    }

    @Test fun `parse reads both verdicts, tolerating a fence`() {
        val yes = StepCheckPrompt.parse("```json\n{\"verdict\":\"verified\",\"why\":\"Pause shows\"}\n```")!!
        assertTrue(yes.verified)
        assertEquals("Pause shows", yes.reason)
        val no = StepCheckPrompt.parse("{\"verdict\":\"not_verified\",\"why\":\"Play still shows\"}")!!
        assertFalse(no.verified)
        assertEquals("Play still shows", no.reason)
    }

    @Test fun `an unknown verdict is no verdict`() {
        assertNull(StepCheckPrompt.parse("{\"verdict\":\"mostly\"}"))
        assertNull(StepCheckPrompt.parse("sure, looks fine"))
    }
}
