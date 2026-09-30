package com.aura.mcp.server

import com.aura.mcp.tools.UsageGuide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 2026-07-17 context delivery: the handshake survival kit must survive
 * client truncation (~2,048 chars in Claude Code), the full playbook must be
 * servable topic-by-topic, and skills must map to legal prompt names.
 */
class ContextDeliveryTest {

    @Test
    fun `perception guidance tells the agent that toggles report their own state`() {
        // perceive_screen emits `checked` for Switch/CheckBox elements. If the
        // guide never says so, the agent ignores it and still turns Wi-Fi OFF
        // while trying to turn it ON — the field would be shipped invisible,
        // which is the exact class of bug it was added to fix.
        val perception = UsageGuide.guideFor("perception")

        assertTrue(perception.contains("checked"), "perception guide must document the `checked` field")
        assertTrue(
            perception.contains("toggle", ignoreCase = true),
            "perception guide must explain what `checked` is for",
        )
    }

    // ── survival kit ──

    @Test
    fun `survival kit fits inside the smallest known client truncation budget`() {
        val len = AuraInstructions.survivalKit.length
        assertTrue(len < 2000, "survival kit is $len chars; must stay < 2000")
    }

    @Test
    fun `survival kit is self-sufficient - carries every load-bearing doctrine`() {
        val kit = AuraInstructions.survivalKit
        listOf(
            "get_usage_guide", // pointer to the full playbook
            "learned hints", // pointer to the memory read lane (lowercase in kit)
            "resolve_contact", // person-by-name rule
            "system_intent", // deterministic verb lane
            "som_id", // perception discipline
            "post_action_observation", // act → observe loop
            "policy_blocked", // safety hard-block protocol
            "never read numbers aloud", // PII rule (lowercased phrasing)
            "irreversible", // stop rule
        ).forEach { marker ->
            assertTrue(kit.contains(marker, ignoreCase = true), "survival kit must mention '$marker'")
        }
    }

    // ── topic sections ──

    @Test
    fun `full playbook splits into the documented topic set`() {
        assertEquals(
            setOf(
                "decision_tree", "perception", "loop", "loading", "trust",
                "deeplinks", "action_plane", "safety", "stop", "efficiency",
                "browser",
            ),
            AuraInstructions.sections.keys,
        )
        AuraInstructions.sections.forEach { (topic, body) ->
            assertTrue(body.isNotBlank(), "section '$topic' must not be blank")
        }
    }

    @Test
    fun `guide routing serves full, topic, and a helpful unknown-topic error`() {
        assertEquals(AuraInstructions.text, UsageGuide.guideFor(null))
        assertEquals(AuraInstructions.text, UsageGuide.guideFor("full"))
        assertEquals(AuraInstructions.sections.getValue("safety"), UsageGuide.guideFor("safety"))
        assertEquals(AuraInstructions.sections.getValue("safety"), UsageGuide.guideFor("  SAFETY "))
        val err = UsageGuide.guideFor("world_domination")
        assertTrue(err.contains("Unknown topic"))
        assertTrue(err.contains("efficiency")) // lists the real topics
    }

    /**
     * "Registered is not the same as used." All 12 browser tools passed their tests and
     * shipped while the routing ladder said nothing about them, so the model had no reason
     * to reach for `browser_read` over `perceive_screen` + taps on a web page — the single
     * most expensive mistake available on this plane (a screenshot plus a vision model, to
     * recover badly what the DOM hands over for free).
     *
     * Asserted on the *handshake* kit specifically, not just the full guide: clients
     * truncate `instructions`, and a rule that only exists in a guide the model never
     * fetches is a rule that does not exist.
     */
    @Test
    fun `the handshake tells the model to use the browser plane on a web page`() {
        val kit = AuraInstructions.survivalKit
        assertTrue(kit.contains("browser_read"), "handshake must name the DOM-first entry point")
        assertTrue(
            kit.contains("perceive_screen+taps") || kit.contains("NEVER perceive_screen"),
            "handshake must say NOT to drive a web page by screenshot",
        )
        assertTrue(kit.contains("browser_handoff"), "handshake must route logins to the handoff")
    }

    /**
     * The one guardrail that is non-negotiable in the spec, and doubly so once scheduled
     * jobs ever run unattended: read/watch/compare are automatic, but buying, paying,
     * applying and sending always stop for the user first.
     */
    @Test
    fun `the buy-pay-apply-send guardrail survives into the truncated handshake`() {
        val kit = AuraInstructions.survivalKit.lowercase()
        listOf("buy", "pay", "apply", "send").forEach { verb ->
            assertTrue(kit.contains(verb), "handshake must name '$verb' in the ask-first guardrail")
        }
        assertTrue(kit.contains("ask first"), "handshake must say to ask first")
    }

    @Test
    fun `the browser topic carries the doctrine the handshake had no room for`() {
        val browser = UsageGuide.guideFor("browser")
        assertTrue(browser.contains("el_id"), "must explain opaque element handles")
        assertTrue(browser.contains("stale_handles"), "must explain generation staleness")
        assertTrue(browser.contains("browser_extract"), "must cover the intention tools")
        assertTrue(browser.contains("browser_tabs"), "must cover cross-site comparison")
        assertTrue(
            browser.contains("never type a password", ignoreCase = true) ||
                browser.contains("cannot log in", ignoreCase = true),
            "must tell the agent it does not log in — it hands off",
        )
    }

    @Test
    fun `efficiency section made it into the full playbook and the resource text`() {
        assertTrue(AuraInstructions.text.contains("Efficiency — the cheapest sufficient tool wins"))
        assertTrue(AuraInstructions.text.contains("learned_hints"))
    }

    // ── skills as prompts ──

    @Test
    fun `prompt names are sanitized to legal identifiers`() {
        assertEquals("whatsapp-send", promptSafeName("WhatsApp Send"))
        assertEquals("book-a-ride", promptSafeName("book a/ride"))
        assertEquals("a-b", promptSafeName("  A  B!! "))
        assertEquals(null, promptSafeName("!!!"))
        assertEquals(64, promptSafeName("x".repeat(100))!!.length)
    }
}
