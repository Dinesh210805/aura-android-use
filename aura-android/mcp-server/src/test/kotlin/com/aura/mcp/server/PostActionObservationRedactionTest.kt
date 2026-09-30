package com.aura.mcp.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GP5 — a gesture allowed on screen A (launcher) can LAND on a blocked screen B
 * (banking). The post-action observation of B must not carry B's text.
 */
class PostActionObservationRedactionTest {

    private val config = ScreenSettle.Config(noChangeProbeMs = 250, quietMs = 300, maxMs = 1_500)

    @Test
    fun `observation of a banking screen is redacted`() = runBlocking<Unit> {
        val clock = FakeClock()
        val bridge = ScriptedBridge(clock)
        bridge.treeJson = """{"package_name":"in.org.npci.upiapp","elements_count":2,"elements":[
            {"text":"Balance: 12,345","isClickable":false},
            {"text":"Send money","isClickable":true}
        ]}"""
        val observer = PostActionObservation.observer(bridge, config, clock::now)

        val obs = observer.observe("tap")
        assertNotNull(obs)
        assertEquals(true, obs.jsonObject["sensitive_foreground"]?.jsonPrimitive?.boolean)
        assertEquals(
            "in.org.npci.upiapp",
            obs.jsonObject["foreground_app"]?.jsonPrimitive?.content,
        )
        assertNull(obs.jsonObject["top_labels"], "labels of a sensitive screen must not leak")
        assertNull(obs.jsonObject["element_count"])
        val hint = obs.jsonObject["hint"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(hint.contains("press_back") || hint.contains("press_home"))
    }

    @Test
    fun `observation of an ordinary screen keeps its labels`() = runBlocking<Unit> {
        val clock = FakeClock()
        val bridge = ScriptedBridge(clock)
        bridge.treeJson = """{"package_name":"com.whatsapp","elements_count":1,"elements":[
            {"text":"Chats","isClickable":true}
        ]}"""
        val observer = PostActionObservation.observer(bridge, config, clock::now)

        val obs = observer.observe("tap")
        assertNotNull(obs)
        assertNull(obs.jsonObject["sensitive_foreground"])
        assertNotNull(obs.jsonObject["top_labels"])
    }
}
