package com.aura.aura_ui.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuraCapabilitiesTest {
    @Test fun `is audio-safe prose — no markdown, emoji or non-ascii`() {
        val t = AuraCapabilities.text
        assertFalse("no asterisks", t.contains('*'))
        assertFalse("no hashes", t.contains('#'))
        assertFalse("no backticks", t.contains('`'))
        assertTrue("all ascii (no emoji)", t.all { it.code < 128 })
    }

    @Test fun `states the real capability surface`() {
        val t = AuraCapabilities.text.lowercase()
        assertTrue(t.contains(AuraCapabilities.SENTINEL.lowercase()))
        listOf(
            "phone", "app", "remember", "remind", "web", "notification", "alarm",
            // self-knowledge the companion must be able to speak: contacts, files,
            // and the ask-instead-of-guess behaviour. The ask phrase is CapabilityNarrative's
            // generated safety sentence — the one home for it here; the actionable rule
            // (which tool, when) is Doctrine.ask_when_detail_missing's.
            "contact", "file", "decision only the user can make",
        ).forEach {
            assertTrue("mentions $it", t.contains(it))
        }
    }
}
