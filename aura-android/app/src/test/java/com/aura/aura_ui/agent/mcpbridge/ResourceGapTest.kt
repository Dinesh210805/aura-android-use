package com.aura.aura_ui.agent.mcpbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Built against the **on-device envelope rendering**, not hand-written JSON.
 *
 * That distinction is the whole reason this class scans instead of parsing. The rendering the
 * agent loop receives embeds each tool's JSON as a string with the inner quotes left unescaped,
 * so a strict parse dies at the first inner `"` — which is exactly how
 * `ToolCallOutcome.decodeEncodedResult` silently never matched on device, logging every failed
 * call as a green ✓. A `resource_required` reader that repeated the mistake would mean a
 * user-fixable gap never reaching the user.
 */
class ResourceGapTest {

    /** The shape a tool result actually arrives in: unescaped inner quotes, `isError` outside. */
    private fun envelope(payload: String) =
        """{"content":[{"text":"$payload", "type":"text"}], "isError":true}"""

    private val tavilyPayload =
        """{"success":false,"error":"resource_required","tool":"web_search",""" +
            """"resource":"tavily_api_key","fixable_by":"user",""" +
            """"message":"Web search needs a Tavily API key, which has not been set up on this phone.",""" +
            """"hint":"Only the user can fix this. Tell them: open AURA Settings and paste a key."}"""

    private val screenCapturePayload =
        """{"success":false,"error":"resource_required","tool":"get_screenshot",""" +
            """"resource":"screen_capture","fixable_by":"agent",""" +
            """"message":"Screen capture has not been allowed yet.",""" +
            """"hint":"Call request_screen_capture_permission, then retry get_screenshot.",""" +
            """"permission_required":true}"""

    // ── the user-fixable case ────────────────────────────────────────────────

    @Test
    fun `reads a user-fixable gap out of the real envelope rendering`() {
        val gap = ResourceGap.from(envelope(tavilyPayload))
        assertEquals("tavily_api_key", gap?.resource)
        assertEquals(ResourceGap.Fixer.USER, gap?.fixableBy)
        assertTrue("the hint is what gets said to the user", gap!!.hint.contains("AURA Settings"))
    }

    /** The verdict the agent loop acts on: stop calling this tool, tell the user. */
    @Test
    fun `a user-fixable gap is terminal`() {
        assertTrue(ResourceGap.isTerminal(envelope(tavilyPayload)))
    }

    // ── the agent-fixable case must NOT be terminal ──────────────────────────

    /**
     * The asymmetry that makes `fixable_by` worth having. `request_screen_capture_permission`
     * exists so `get_screenshot` can be retried — treating this as terminal would break the very
     * repair path its own hint recommends.
     */
    @Test
    fun `an agent-fixable gap is not terminal`() {
        val gap = ResourceGap.from(envelope(screenCapturePayload))
        assertEquals(ResourceGap.Fixer.AGENT, gap?.fixableBy)
        assertEquals("screen_capture", gap?.resource)
        assertFalse(ResourceGap.isTerminal(envelope(screenCapturePayload)))
    }

    // ── everything else is not a gap ─────────────────────────────────────────

    @Test
    fun `an ordinary success is not a gap`() {
        val ok = """{"content":[{"text":"{"success":true,"action":"tap"}", "type":"text"}], "isError":false}"""
        assertNull(ResourceGap.from(ok))
        assertFalse(ResourceGap.isTerminal(ok))
    }

    /**
     * The other refusal codes are neighbours in the same family and must not be mistaken for
     * this one — each already has its own handling, and `paused_by_user` in particular means
     * *retry shortly*, the opposite verdict.
     */
    @Test
    fun `neighbouring refusal codes are not resource gaps`() {
        listOf("paused_by_user", "scope_denied", "policy_blocked", "foreground_blocked").forEach { code ->
            val payload = """{"success":false,"error":"$code","tool":"tap","hint":"something"}"""
            assertNull("mistook $code for a resource gap", ResourceGap.from(envelope(payload)))
        }
    }

    @Test
    fun `null and empty results are not gaps`() {
        assertNull(ResourceGap.from(null))
        assertNull(ResourceGap.from(""))
        assertFalse(ResourceGap.isTerminal(null))
    }

    // ── degraded payloads ────────────────────────────────────────────────────

    /**
     * An unrecognised `fixable_by` falls to USER deliberately. The failure modes are not
     * symmetric: guessing AGENT invites a retry loop against something no tool can supply, while
     * guessing USER at worst surfaces one honest sentence.
     */
    @Test
    fun `an unknown fixable_by is treated as user-fixable`() {
        val payload = """{"error":"resource_required","resource":"something_new","fixable_by":"martians","hint":"x"}"""
        assertEquals(ResourceGap.Fixer.USER, ResourceGap.from(envelope(payload))?.fixableBy)
    }

    /** No `resource` id means nothing to branch on — better to report no gap than a blank one. */
    @Test
    fun `a payload with no resource id is not a gap`() {
        val payload = """{"error":"resource_required","fixable_by":"user","hint":"x"}"""
        assertNull(ResourceGap.from(envelope(payload)))
    }

    /**
     * `type_text` is the odd one out among the migrated tools: it reports through its own
     * `jsonOk(success, Map)` shape rather than `resourceRequiredResult`, so the fields are built
     * by a different code path. It is also the gap a user is most likely to hit — any React
     * Native or Flutter app with no editable accessibility node. Pinned separately because a
     * serializer that nested or renamed these keys would break it silently: `ResourceGap` scans
     * for exactly `"resource"` and `"fixable_by"`.
     */
    @Test
    fun `type_text's keyboard gap is readable despite its different payload shape`() {
        val payload = """{"success":false,"action":"type_text","error":"resource_required",""" +
            """"resource":"aura_keyboard","fixable_by":"user",""" +
            """"hint":"Tell them to enable it once: Settings, then AURA Keyboard."}"""
        val gap = ResourceGap.from(envelope(payload))
        assertEquals("aura_keyboard", gap?.resource)
        assertEquals(ResourceGap.Fixer.USER, gap?.fixableBy)
        assertTrue(gap!!.hint.contains("AURA Keyboard"))
        assertTrue(ResourceGap.isTerminal(envelope(payload)))
    }
}
