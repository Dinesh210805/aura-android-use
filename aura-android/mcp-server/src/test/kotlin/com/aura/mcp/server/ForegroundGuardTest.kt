package com.aura.mcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GP5+GP6 — the foreground gate's pure classifier. Gestures and perception are
 * denied while a blocklisted app is foreground; escape hatches and entry points
 * are never gated; unknown foreground fails OPEN (entry gate is the primary
 * defense; screen-off flows must keep working).
 */
class ForegroundGuardTest {

    private val bankingFg = "in.org.npci.upiapp" // exact-blocklisted (BHIM)

    @Test
    fun `gesture inside banking foreground blocks with gesture kind`() {
        val v = ForegroundGuard.evaluate("tap", bankingFg)
        assertIs<ForegroundGuard.Verdict.Block>(v)
        assertEquals(ForegroundGuard.Kind.GESTURE, v.kind)
    }

    @Test
    fun `type_text inside banking foreground blocks`() {
        assertIs<ForegroundGuard.Verdict.Block>(ForegroundGuard.evaluate("type_text", bankingFg))
    }

    @Test
    fun `perception inside banking foreground blocks with perception kind`() {
        val v = ForegroundGuard.evaluate("perceive_screen", bankingFg)
        assertIs<ForegroundGuard.Verdict.Block>(v)
        assertEquals(ForegroundGuard.Kind.PERCEPTION, v.kind)
    }

    @Test
    fun `screenshot and ui tree inside banking foreground block`() {
        assertIs<ForegroundGuard.Verdict.Block>(ForegroundGuard.evaluate("get_screenshot", bankingFg))
        assertIs<ForegroundGuard.Verdict.Block>(ForegroundGuard.evaluate("read_screen", bankingFg))
    }

    @Test
    fun `escape hatches are never gated`() {
        assertIs<ForegroundGuard.Verdict.Allow>(ForegroundGuard.evaluate("press_home", bankingFg))
        assertIs<ForegroundGuard.Verdict.Allow>(ForegroundGuard.evaluate("press_back", bankingFg))
        assertIs<ForegroundGuard.Verdict.Allow>(ForegroundGuard.evaluate("open_recent_apps", bankingFg))
    }

    @Test
    fun `entry points stay on the entry gate, not this one`() {
        assertIs<ForegroundGuard.Verdict.Allow>(ForegroundGuard.evaluate("launch_app", bankingFg))
        assertIs<ForegroundGuard.Verdict.Allow>(ForegroundGuard.evaluate("open_deeplink", bankingFg))
    }

    @Test
    fun `unknown foreground fails open`() {
        assertIs<ForegroundGuard.Verdict.Allow>(ForegroundGuard.evaluate("tap", null))
        assertIs<ForegroundGuard.Verdict.Allow>(ForegroundGuard.evaluate("tap", ""))
    }

    @Test
    fun `ordinary foreground allows everything`() {
        assertIs<ForegroundGuard.Verdict.Allow>(ForegroundGuard.evaluate("tap", "com.whatsapp"))
        assertIs<ForegroundGuard.Verdict.Allow>(ForegroundGuard.evaluate("perceive_screen", "com.whatsapp"))
    }

    @Test
    fun `ungated tool is null-kind even with banking foreground`() {
        assertNull(ForegroundGuard.gatedKind("web_search"))
        assertIs<ForegroundGuard.Verdict.Allow>(ForegroundGuard.evaluate("web_search", bankingFg))
    }

    @Test
    fun `block messages tell the model not to work around and how to leave`() {
        val v = ForegroundGuard.evaluate("tap", bankingFg) as ForegroundGuard.Verdict.Block
        val p = ForegroundGuard.evaluate("perceive_screen", bankingFg) as ForegroundGuard.Verdict.Block
        assertTrue(v.message.contains("press_home"))
        assertTrue(p.message.contains("press_home"))
    }
}
