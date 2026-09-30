package com.aura.aura_ui.agent.conversation

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBatchGateTest {

    private fun call(name: String, id: String = name) = CompanionToolCall(name, JsonObject(emptyMap()), id)

    private val flashlight = call(LiveToolDeclarations.DEVICE_SETTINGS, "c1")
    private val dnd = call(LiveToolDeclarations.DEVICE_SETTINGS, "c2")
    private val bluetooth = call(LiveToolDeclarations.DEVICE_SETTINGS, "c3")
    private val context = call(LiveToolDeclarations.DEVICE_CONTEXT, "c4")
    private val askAura = call(LiveToolDeclarations.ASK_AURA, "c5")

    @Test fun `a single call is never refused`() {
        val d = ToolBatchGate.apply(listOf(flashlight))
        assertEquals(listOf(flashlight), d.allowed)
        assertTrue(d.refused.isEmpty())
    }

    @Test fun `an empty batch is handled`() {
        val d = ToolBatchGate.apply(emptyList())
        assertTrue(d.allowed.isEmpty())
        assertTrue(d.refused.isEmpty())
    }

    /** The reported failure: one misheard sentence turning on three things at once. */
    @Test fun `a misheard triple applies only the first change`() {
        val d = ToolBatchGate.apply(listOf(flashlight, dnd, bluetooth))
        assertEquals(listOf(flashlight), d.allowed)
        assertEquals(listOf(dnd, bluetooth), d.refused)
    }

    @Test fun `reads are unrestricted and do not consume the one change`() {
        val d = ToolBatchGate.apply(listOf(context, flashlight, context))
        assertEquals(listOf(context, flashlight, context), d.allowed)
        assertTrue(d.refused.isEmpty())
    }

    /** ask_aura has its own one-task-at-a-time guard and its own reasoning about intent. */
    @Test fun `ask_aura is exempt and never blocks a device change`() {
        val d = ToolBatchGate.apply(listOf(askAura, flashlight))
        assertEquals(listOf(askAura, flashlight), d.allowed)
        assertTrue(d.refused.isEmpty())
    }

    @Test fun `different state-changing tools still share the single slot`() {
        val media = call(LiveToolDeclarations.MEDIA_CONTROL, "c6")
        val action = call(LiveToolDeclarations.DEVICE_ACTION, "c7")
        val d = ToolBatchGate.apply(listOf(media, action))
        assertEquals(listOf(media), d.allowed)
        assertEquals(listOf(action), d.refused)
    }

    @Test fun `the order the model emitted is preserved`() {
        val d = ToolBatchGate.apply(listOf(context, dnd, askAura, bluetooth, flashlight))
        assertEquals(listOf(context, dnd, askAura), d.allowed)
        assertEquals(listOf(bluetooth, flashlight), d.refused)
    }

    // ── what the model is told ────────────────────────────────────────────────

    @Test fun `the refusal names what was actually applied so the model cannot claim it all worked`() {
        val text = ToolBatchGate.refusalFor(dnd, appliedCall = flashlight)
        assertTrue(text.startsWith("Not done."))
        assertTrue(text.contains(LiveToolDeclarations.DEVICE_SETTINGS))
        assertTrue("the model must be asked to confirm", text.contains("ask whether they also wanted"))
    }

    @Test fun `the refusal still reads correctly when nothing was applied`() {
        val text = ToolBatchGate.refusalFor(dnd, appliedCall = null)
        assertTrue(text.contains("used its change"))
    }
}
