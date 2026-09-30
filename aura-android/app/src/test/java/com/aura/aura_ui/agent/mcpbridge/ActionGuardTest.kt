package com.aura.aura_ui.agent.mcpbridge

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the Phase 1 reliability guard. [ActionGuard] is pure (only a
 * coroutine Mutex), so no Android/Robolectric runner is needed.
 *
 * Lifecycle modelled in each test mirrors [McpTool.execute]:
 * `blockReasonFor(...)` is consulted first; if it allows, the tool "runs" and
 * `recordAfter(...)` updates state. A perception passes its element text so the
 * guard can fingerprint the screen for loop detection.
 */
class ActionGuardTest {

    private val tapArgs = """{"x":100,"y":200}"""

    private suspend fun ActionGuard.perceive(screen: String) =
        recordAfter("perceive_screen", "{}", success = true, screenText = screen)

    private suspend fun ActionGuard.ranTap(args: String = tapArgs) =
        recordAfter("tap", args, success = true, screenText = "")

    // ── 1a — perceive before act ──────────────────────────────────────────────

    @Test
    fun `gesture before any perception is blocked`() = runTest {
        val guard = ActionGuard()
        assertNotNull("tap on unperceived screen must be blocked", guard.blockReasonFor("tap", tapArgs))
        assertEquals(1, guard.blindBlocks)
    }

    @Test
    fun `gesture is allowed once the screen is perceived`() = runTest {
        val guard = ActionGuard()
        guard.perceive("home screen")
        assertNull("tap after perceive must be allowed", guard.blockReasonFor("tap", tapArgs))
    }

    @Test
    fun `gesture is re-blocked after a screen change until re-perceived`() = runTest {
        val guard = ActionGuard()
        guard.perceive("home")
        assertNull(guard.blockReasonFor("launch_app", """{"package":"x"}"""))
        guard.recordAfter("launch_app", """{"package":"x"}""", success = true, screenText = "")
        // Screen changed → stale again; the next gesture must be blocked.
        assertNotNull("tap after launch_app (stale screen) must be blocked", guard.blockReasonFor("tap", tapArgs))
    }

    @Test
    fun `type_text is allowed after a tap without a forced re-perceive`() = runTest {
        // Regression for the livelock: gating type_text forced a perceive between
        // tap-to-focus and the type, so "tap search box -> type query" never advanced.
        val guard = ActionGuard()
        guard.perceive("search screen")
        assertNull(guard.blockReasonFor("tap", tapArgs)); guard.ranTap() // focus the field; screen now "stale"
        assertNull("type_text after a tap must NOT be blocked", guard.blockReasonFor("type_text", """{"text":"python"}"""))
    }

    @Test
    fun `non-targeted tools are never perceive-blocked`() = runTest {
        val guard = ActionGuard()
        assertNull(guard.blockReasonFor("launch_app", """{"package":"x"}"""))
        assertNull(guard.blockReasonFor("read_screen", "{}"))
        assertNull(guard.blockReasonFor("scroll_down", "{}"))
    }

    // ── 1c — loop / stuck detection ───────────────────────────────────────────

    @Test
    fun `same gesture on the same screen is blocked after the threshold`() = runTest {
        val guard = ActionGuard()
        // Two identical taps on the SAME screen signature, re-perceiving the same screen between.
        guard.perceive("screenA"); assertNull(guard.blockReasonFor("tap", tapArgs)); guard.ranTap()
        guard.perceive("screenA"); assertNull(guard.blockReasonFor("tap", tapArgs)); guard.ranTap()
        // Third identical attempt on the unchanged screen → loop block.
        guard.perceive("screenA")
        assertNotNull("3rd identical tap on unchanged screen must be loop-blocked", guard.blockReasonFor("tap", tapArgs))
        assertEquals(1, guard.loopBlocks)
    }

    /** A6 — the harness reads the screen after each tap; IDLE vs BUSY must not hide a loop. */
    @Test
    fun `read_screen grids that differ only in the IDLE-BUSY header still loop`() = runTest {
        val guard = ActionGuard()
        fun grid(state: String) = "SEEN: com.x | Play\nSCREEN 1080x2400  com.x  $state  3 elements\n┌──┐\n│2 │\n└──┘"
        suspend fun look(state: String) = guard.recordAfter("read_screen", "{}", success = true, screenText = grid(state))
        look("IDLE"); assertNull(guard.blockReasonFor("tap", tapArgs)); guard.ranTap()
        look("BUSY"); assertNull(guard.blockReasonFor("tap", tapArgs)); guard.ranTap()
        look("IDLE")
        assertNotNull("same grid, same tap — must loop-block", guard.blockReasonFor("tap", tapArgs))
    }

    /**
     * Amazon regression: the SAME home screen re-perceived 3× produced a different
     * omniparser (red-box) count each time (0, then 2, then 1) because the CV tier
     * and YOLO output vary run-to-run. The old signature hashed the WHOLE perceive
     * text, so those three views had three different signatures and the identical
     * `tap som_id=24` (which kept opening the address modal) was never loop-blocked.
     * The screen signature must key on the STABLE ui_tree geometry only, so the
     * three home views collide and the 3rd mis-tap is refused.
     */
    private fun homePerceive(
        omniElements: String,
        omniCount: Int,
        description: String = "home",
    ): String {
        // The REAL wire shape: ui_tree elements first as positional [cx, cy, name]
        // arrays under `e`, split from the CV ones by `ui_tree_count`. Identical
        // every time; only the CV portion and the counts vary.
        val uiTree = """[60,120,"menu"],[620,689,"Deliver to Dinesh"],[620,840,"Search Amazon"]"""
        val payload = """{"element_count":${3 + omniCount},"ui_tree_count":3,""" +
            """"omniparser_count":$omniCount,"omniparser_status":"ok","perception_tier":"full",""" +
            """"source_width_px":1240,"source_height_px":2772,""" +
            """"e":[$uiTree${if (omniElements.isBlank()) "" else ",$omniElements"}]}"""
        // perceive_screen returns SEVERAL content blocks; the hook joins them with
        // newlines, so the guard never receives bare JSON. The prose line quotes the
        // caller's `description` — varying it must not change the signature.
        return "3 tree boxes, $omniCount vision boxes. Find '$description', then tap(som_id=N).\n$payload"
    }

    @Test
    fun `identical mis-tap loops even when CV output varies between perceives`() = runTest {
        val guard = ActionGuard()
        val tap24 = """{"som_id":24}"""
        // Same home screen three times, differing ONLY in the CV (red-box) output.
        guard.perceive(homePerceive(omniElements = "", omniCount = 0))
        assertNull(guard.blockReasonFor("tap", tap24)); guard.ranTap(tap24)
        guard.perceive(
            homePerceive(
                omniElements = """[300,1500,"banner"],[900,1500,"deal"]""",
                omniCount = 2,
            ),
        )
        assertNull(guard.blockReasonFor("tap", tap24)); guard.ranTap(tap24)
        guard.perceive(
            homePerceive(
                omniElements = """[500,1600,"promo"]""",
                omniCount = 1,
            ),
        )
        assertNotNull(
            "the 3rd identical tap on the same home screen must be loop-blocked despite CV variance",
            guard.blockReasonFor("tap", tap24),
        )
        assertEquals(1, guard.loopBlocks)
    }

    /**
     * The prose block perceive_screen prepends quotes the caller's `description`
     * argument, and the hook concatenates it with the JSON payload. Hashing the
     * joined text therefore made the SAME screen look different whenever the agent
     * reworded its intent — which is every turn, since the description names the
     * element it is currently hunting for.
     */
    @Test
    fun `signature ignores the prose block so a reworded description still loops`() = runTest {
        val guard = ActionGuard()
        val tap7 = """{"som_id":7}"""
        guard.perceive(homePerceive("", 0, description = "search bar"))
        assertNull(guard.blockReasonFor("tap", tap7)); guard.ranTap(tap7)
        guard.perceive(homePerceive("", 0, description = "the search field at the top"))
        assertNull(guard.blockReasonFor("tap", tap7)); guard.ranTap(tap7)
        guard.perceive(homePerceive("", 0, description = "Search Amazon box"))
        assertNotNull(
            "rewording the description must not disguise an unchanged screen",
            guard.blockReasonFor("tap", tap7),
        )
    }

    /**
     * A vision-only screen (WebView/game — `ui_tree_count` 0) has nothing
     * deterministic to key on, so the guard must fall back rather than invent a
     * stable signature from jittery YOLO output.
     */
    @Test
    fun `vision-only screen falls back instead of claiming a stable signature`() = runTest {
        val guard = ActionGuard()
        val blind = { n: Int ->
            """{"element_count":1,"ui_tree_count":0,"omniparser_count":1,"e":[[10,$n,"blob"]]}"""
        }
        val tap1 = """{"som_id":1}"""
        guard.perceive(blind(100)); assertNull(guard.blockReasonFor("tap", tap1)); guard.ranTap(tap1)
        guard.perceive(blind(140)); assertNull(guard.blockReasonFor("tap", tap1)); guard.ranTap(tap1)
        guard.perceive(blind(180))
        assertNull(
            "differing CV geometry is a differing screen — nothing here proves a loop",
            guard.blockReasonFor("tap", tap1),
        )
    }

    @Test
    fun `repeated gesture is NOT a loop when the screen changes between taps`() = runTest {
        val guard = ActionGuard()
        // Same tap coords, but each perception shows a DIFFERENT screen (e.g. a counter
        // incrementing) → different signature → never flagged as a loop.
        guard.perceive("count=1"); assertNull(guard.blockReasonFor("tap", tapArgs)); guard.ranTap()
        guard.perceive("count=2"); assertNull(guard.blockReasonFor("tap", tapArgs)); guard.ranTap()
        guard.perceive("count=3")
        assertNull("legitimate repeated tap that changes the screen must NOT be blocked", guard.blockReasonFor("tap", tapArgs))
        assertEquals(0, guard.loopBlocks)
    }

    // ── 1e — verify before finish ─────────────────────────────────────────────

    @Test
    fun `end_session is blocked when an action happened with no confirming perception`() = runTest {
        val guard = ActionGuard()
        guard.perceive("home")
        guard.recordAfter("launch_app", """{"package":"x"}""", success = true, screenText = "")
        assertNotNull("end_session on a stale screen after acting must be blocked", guard.blockReasonFor("end_session", "{}"))
    }

    @Test
    fun `end_session is allowed once the result is perceived`() = runTest {
        val guard = ActionGuard()
        guard.recordAfter("launch_app", """{"package":"x"}""", success = true, screenText = "")
        guard.perceive("app open, goal visible")
        assertNull("end_session after a confirming perception must be allowed", guard.blockReasonFor("end_session", "{}"))
    }

    @Test
    fun `end_session is allowed for an info-only task that never touched the screen`() = runTest {
        val guard = ActionGuard()
        assertNull("conversational task may end without perceiving", guard.blockReasonFor("end_session", "{}"))
    }

    @Test
    fun `end_session block is capped so the agent cannot deadlock`() = runTest {
        val guard = ActionGuard()
        guard.recordAfter("tap", tapArgs, success = true, screenText = "") // acted, now stale
        assertNotNull(guard.blockReasonFor("end_session", "{}")) // block 1
        assertNotNull(guard.blockReasonFor("end_session", "{}")) // block 2
        assertNull("end_session must pass after the block cap to avoid a deadlock", guard.blockReasonFor("end_session", "{}"))
    }

    // ── 1e — the post-action observation IS verification ─────────────────────

    private val observedResultText =
        """{"success":true,"package":"com.whatsapp"}
           {"post_action_observation":{"settled":true,"foreground_app":"com.whatsapp","top_labels":["Chats"]}}"""

    @Test
    fun `end_session is allowed when the action carried a post_action_observation`() = runTest {
        // The WhatsApp trace regression: launch_app returned a settled observation
        // proving foreground=com.whatsapp, yet 1e still demanded a redundant
        // perceive_screen before end_session (+2 LLM calls, +2s per run).
        val guard = ActionGuard()
        guard.recordAfter("launch_app", """{"app_name":"WhatsApp"}""", success = true, screenText = observedResultText)
        assertNull(
            "end_session after an observed action must be allowed",
            guard.blockReasonFor("end_session", "{}"),
        )
    }

    @Test
    fun `observation does NOT ground som-targeted gestures`() = runTest {
        // The observation is a summary, not a som map — taps still need a real perceive.
        val guard = ActionGuard()
        guard.perceive("home")
        guard.recordAfter("launch_app", """{"app_name":"WhatsApp"}""", success = true, screenText = observedResultText)
        assertNotNull("tap after an observed action must still require perceive", guard.blockReasonFor("tap", tapArgs))
    }

    @Test
    fun `an unobserved screen change after an observed one re-arms the finish gate`() = runTest {
        val guard = ActionGuard()
        guard.recordAfter("launch_app", """{"app_name":"WhatsApp"}""", success = true, screenText = observedResultText)
        guard.recordAfter("wait_for", """{"seconds":2}""", success = true, screenText = """{"success":true}""")
        assertNotNull(
            "end_session after a later un-observed change must be blocked again",
            guard.blockReasonFor("end_session", "{}"),
        )
    }

    @Test
    fun `audio-only tools stale nothing — grounding and finish survive them`() = runTest {
        // media_control / volume change no pixels: forcing a perceive_screen after
        // "pause the music" before end_session was pure waste.
        val guard = ActionGuard()
        guard.perceive("player screen")
        guard.recordAfter("media_control", """{"action":"pause"}""", success = true, screenText = """{"success":true}""")
        assertNull("tap grounding must survive an audio-only tool", guard.blockReasonFor("tap", tapArgs))
        assertNull("end_session must be allowed after an audio-only task", guard.blockReasonFor("end_session", "{}"))
    }

    // ── failures do not move state ────────────────────────────────────────────

    @Test
    fun `a failed perception does not ground the screen`() = runTest {
        val guard = ActionGuard()
        guard.recordAfter("perceive_screen", "{}", success = false, screenText = "noise")
        assertNotNull("tap after a FAILED perceive must still be blocked", guard.blockReasonFor("tap", tapArgs))
    }
}
