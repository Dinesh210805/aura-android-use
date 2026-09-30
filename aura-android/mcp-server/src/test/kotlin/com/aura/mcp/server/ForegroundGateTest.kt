package com.aura.mcp.server

import com.aura.mcp.bridge.DeviceEvent
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.bridge.UiTreeSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** GP5+GP6 — the glue that reads the foreground package lazily and only for gated tools. */
class ForegroundGateTest {

    private class FakeTreeBridge(private val payload: String, private val ok: Boolean = true) : UiTreeBridge {
        var snapshots = 0
        override suspend fun snapshot(): UiTreeSnapshot {
            snapshots++
            return UiTreeSnapshot(ok = ok, payloadJson = payload)
        }
        override suspend fun drainEvents(timeoutMs: Long, maxEvents: Int): List<DeviceEvent> = emptyList()
    }

    @Test
    fun `gated tool with banking foreground is blocked`() = runBlocking {
        val bridge = FakeTreeBridge("""{"package_name":"in.org.npci.upiapp"}""")
        val gate = ForegroundGate.fromUiTree(bridge)
        val block = gate.check("tap")
        assertNotNull(block)
        assertEquals(ForegroundGuard.Kind.GESTURE, block.kind)
    }

    @Test
    fun `gated tool with ordinary foreground passes`() = runBlocking {
        val gate = ForegroundGate.fromUiTree(FakeTreeBridge("""{"package_name":"com.whatsapp"}"""))
        assertNull(gate.check("perceive_screen"))
    }

    @Test
    fun `ungated tool never reads the tree`() = runBlocking {
        val bridge = FakeTreeBridge("""{"package_name":"in.org.npci.upiapp"}""")
        val gate = ForegroundGate.fromUiTree(bridge)
        assertNull(gate.check("press_home"))
        assertNull(gate.check("web_search"))
        assertEquals(0, bridge.snapshots, "ungated tools must not pay a snapshot")
    }

    @Test
    fun `failed snapshot fails open`() = runBlocking {
        val gate = ForegroundGate.fromUiTree(FakeTreeBridge("{}", ok = false))
        assertNull(gate.check("tap"))
    }

    @Test
    fun `throwing bridge fails open`() = runBlocking {
        val throwing = object : UiTreeBridge {
            override suspend fun snapshot(): UiTreeSnapshot = error("accessibility down")
            override suspend fun drainEvents(timeoutMs: Long, maxEvents: Int): List<DeviceEvent> = emptyList()
        }
        assertNull(ForegroundGate.fromUiTree(throwing).check("tap"))
    }

    @Test
    fun `NOOP gate always passes`() = runBlocking {
        assertNull(ForegroundGate.NOOP.check("tap"))
    }
}
