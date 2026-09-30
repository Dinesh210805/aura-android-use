package com.aura.aura_ui.agent.conversation

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveToolDeclarationsTest {
    /**
     * Six tools, down from fifteen.
     *
     * This assertion is the guard on the whole redesign: the Live model is a speech model running
     * with thinking disabled on the native-audio path, and every tool added back here is another
     * branch it has to get right without reasoning. Anything needing judgment belongs behind
     * `ask_aura`, where a real reasoning model decides.
     */
    @Test fun `declares one universal tool, four instant device controls, and the sign-off`() {
        assertEquals(
            listOf(
                "ask_aura",
                "device_context", "media_control", "device_settings", "device_action",
                "end_conversation",
            ),
            LiveToolDeclarations.names(),
        )
    }

    @Test fun `the tools that moved behind ask_aura are gone from Live's surface`() {
        val gone = listOf(
            "recall_memory", "save_memory", "get_commitments", "remind", "drive_phone",
            "web_search", "read_notifications", "notification_action", "system_intent",
            "task_status",
        )
        val declared = LiveToolDeclarations.functionDeclarations()
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        gone.forEach { assertTrue("$it must no longer be a Live tool", it !in declared) }
    }

    /**
     * `isLongRunning(name)` was deleted, not updated, and this test replaced its coverage.
     *
     * With one universal tool the duration of a call is no longer a property of its NAME —
     * `ask_aura` is both "what's the capital of France" and "order me a coffee". Any name-based
     * answer would be wrong half the time, so the decision moved to the handler, which knows only
     * after the brain lane has read the request. See EscalationSink.
     */
    @Test fun `ask_aura is declared NON_BLOCKING because it might escalate`() {
        val decl = LiveToolDeclarations.functionDeclarations()
            .map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "ask_aura" }
        assertEquals("NON_BLOCKING", decl["behavior"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("request"),
            decl["parameters"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test fun `ask_aura tells the model it is the way to see the screen`() {
        val decl = LiveToolDeclarations.functionDeclarations()
            .map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "ask_aura" }
        val description = decl["description"]!!.jsonPrimitive.content
        // Without this the speech model answers "I can't see your screen" instead of looking.
        assertTrue(description.contains("SEE the phone"))
        // And this is what keeps routing out of the speech model's hands.
        assertTrue(description.contains("do not decide how it will be done"))
    }

    @Test fun `end_conversation is declared and takes no required args`() {
        val decl = LiveToolDeclarations.functionDeclarations()
            .map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "end_conversation" }
        // No required args: signing off must never fail because the model omitted a field.
        assertNull(decl["parameters"]!!.jsonObject["required"])
        val description = decl["description"]!!.jsonPrimitive.content
        // The running-task promise is load-bearing — without it the model hedges or refuses to
        // sign off while a task is in flight.
        assertTrue("must promise a running task survives", description.contains("NOT cancelled"))
    }

    @Test fun `every declared name has a matching function declaration`() {
        val declaredNames = LiveToolDeclarations.functionDeclarations()
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(LiveToolDeclarations.names().toSet(), declaredNames.toSet())
    }

    @Test fun `device_context takes no args`() {
        val ctx = LiveToolDeclarations.functionDeclarations()
            .map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "device_context" }
        assertTrue(ctx["parameters"]!!.jsonObject["properties"]!!.jsonObject.isEmpty())
    }

    /**
     * Only the tool that might escalate is NON_BLOCKING. Declaring a fast device call that way
     * would hand the model an empty slot to fill in while it waited.
     */
    @Test fun `only ask_aura is non-blocking`() {
        LiveToolDeclarations.functionDeclarations().map { it.jsonObject }
            .filterNot { it["name"]!!.jsonPrimitive.content == "ask_aura" }
            .forEach { assertNull("${it["name"]} must block", it["behavior"]) }
    }

    /** The batch guard only means anything if it covers every tool that mutates the device. */
    @Test fun `every state-changing tool is declared, and reads are not in the guard`() {
        val declared = LiveToolDeclarations.functionDeclarations()
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        LiveToolDeclarations.STATE_CHANGING.forEach { assertTrue("$it must be declared", it in declared) }
        assertTrue(
            "reading the clock cannot go wrong — it must not consume the one change per turn",
            LiveToolDeclarations.DEVICE_CONTEXT !in LiveToolDeclarations.STATE_CHANGING,
        )
        assertTrue(
            "ask_aura has its own guards and must not be capped by the batch gate",
            LiveToolDeclarations.ASK_AURA !in LiveToolDeclarations.STATE_CHANGING,
        )
    }

    @Test fun `each declaration has a name and a description`() {
        LiveToolDeclarations.functionDeclarations().forEach { decl ->
            val o = decl.jsonObject
            assertTrue(o["name"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(o["description"]!!.jsonPrimitive.content.isNotBlank())
        }
    }

    /**
     * The device_settings description carries the one-change-per-turn rule so the model can
     * anticipate the [ToolBatchGate] refusal rather than being surprised by it mid-batch.
     */
    @Test fun `device_settings warns the model about the one-change-per-turn limit`() {
        val decl = LiveToolDeclarations.functionDeclarations()
            .map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "device_settings" }
        assertTrue(decl["description"]!!.jsonPrimitive.content.contains("One setting per turn"))
    }
}
