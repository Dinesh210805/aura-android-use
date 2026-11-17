package com.aura.mcp.tools

import com.aura.mcp.bench.Ctx
import com.aura.mcp.bench.MockPhone
import com.aura.mcp.bench.button
import com.aura.mcp.bench.screen
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The screen ledger's silent read must match what the model reads and must not move its som_ids. */
class ScreenSnapshotTest {

    private val app = "com.example.shop"

    private fun phone() = MockPhone().apply {
        show(
            screen(
                app,
                button("alpha", "Alpha", 100, 300, 980, 450),
                button("beta", "Beta", 100, 600, 980, 750),
                button("gamma", "Gamma", 100, 900, 980, 1050),
            ),
        )
    }

    /** read_screen's lines minus the header, which only it can fill (IDLE/BUSY from its settle). */
    private fun body(text: String) = text.lines().filterNot { it.startsWith("SCREEN ") }

    @Test
    fun `a snapshot reads the same elements as read_screen`() = runBlocking {
        val ctx = Ctx(phone())
        val look = ctx.read()
        val snap = assertNotNull(ScreenSnapshot.read(ctx.phone))
        assertEquals(body(look.text), body(snap.text))
        assertEquals(app, snap.pkg)
        assertTrue("\"Beta\"" in snap.frame, snap.frame)
    }

    @Test
    fun `a snapshot between a read and a tap leaves the tap on its element`() = runBlocking {
        val ctx = Ctx(phone())
        ctx.read()
        repeat(3) { ScreenSnapshot.read(ctx.phone) }
        ctx.tap("beta")
        ctx.expectHit("beta")
        assertTrue(ctx.checks.all { it.ok }, ctx.checks.joinToString { "${it.name}: ${it.detail}" })
    }

    @Test
    fun `same screen gives the same keys, a changed label only a new content key`() {
        val phone = phone()
        val first = ScreenSnapshot.of(phone.payload())
        assertEquals(first, ScreenSnapshot.of(phone.payload()))
        assertNotNull(phone.nodeOrNull("beta")).text = "Beta 2"
        val changed = ScreenSnapshot.of(phone.payload())
        assertEquals(first.layoutKey, changed.layoutKey)
        assertTrue(first.contentKey != changed.contentKey)
    }

    @Test
    fun `an empty screen still has a caption`() {
        val snap = ScreenSnapshot.of("""{"package_name":"com.example.game","elements":[]}""")
        assertTrue(snap.text.startsWith("SEEN: "), snap.text)
    }
}
