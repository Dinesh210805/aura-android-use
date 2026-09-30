package com.aura.aura_ui.agent.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSummaryParserTest {
    @Test fun `parses summary and facts from model JSON`() {
        val json = """{"summary":"You asked about the weather and your schedule.","facts":["User's dog is named Rex","User prefers tea"]}"""
        val s = ConversationSummaryParser.parse(json)!!
        assertEquals("You asked about the weather and your schedule.", s.summary)
        assertEquals(listOf("User's dog is named Rex", "User prefers tea"), s.facts)
    }

    @Test fun `tolerates code-fenced JSON and missing facts`() {
        val json = "```json\n{\"summary\":\"Chatted about music.\"}\n```"
        val s = ConversationSummaryParser.parse(json)!!
        assertEquals("Chatted about music.", s.summary)
        assertTrue(s.facts.isEmpty())
    }

    @Test fun `blank summary or garbage returns null`() {
        assertNull(ConversationSummaryParser.parse("not json"))
        assertNull(ConversationSummaryParser.parse("""{"summary":"  ","facts":[]}"""))
    }

    @Test fun `prompt includes the transcript and asks for JSON`() {
        val p = SummaryPromptBuilder.prompt("User: hi\nAURA: hello")
        assertTrue(p.contains("User: hi"))
        assertTrue(p.contains("summary"))
        assertTrue(p.contains("facts"))
    }

    @Test fun `parseFacts reads a facts-only checkpoint reply`() {
        val facts = ConversationSummaryParser.parseFacts("""{"facts":["User's dog is Rex","User is planning a trip"]}""")
        assertEquals(listOf("User's dog is Rex", "User is planning a trip"), facts)
    }

    @Test fun `parseFacts returns empty on garbage`() {
        assertEquals(emptyList<String>(), ConversationSummaryParser.parseFacts("nope"))
        assertEquals(emptyList<String>(), ConversationSummaryParser.parseFacts("""{"facts":[]}"""))
    }

    @Test fun `factsPrompt includes the transcript and asks for facts JSON`() {
        val p = SummaryPromptBuilder.factsPrompt("User: my dog is Rex\nAURA: nice")
        assertTrue(p.contains("my dog is Rex"))
        assertTrue(p.contains("facts"))
    }

    // ── speech style (spec 2026-07-28) ───────────────────────────────────────

    @Test fun `parses style notes alongside facts`() {
        val out = ConversationSummaryParser.parse(
            """{"summary":"You asked about trains","facts":["takes the 7am"],""" +
                """"style":["mixes Tamil and English in one sentence"]}""",
        )
        assertEquals(listOf("mixes Tamil and English in one sentence"), out?.style)
    }

    /** A model that omits the key must not break parsing — style is additive, not required. */
    @Test fun `missing style key yields an empty list`() {
        val out = ConversationSummaryParser.parse("""{"summary":"hi","facts":[]}""")
        assertEquals(emptyList<String>(), out?.style)
    }

    @Test fun `summary prompt asks for the language mix explicitly`() {
        val p = SummaryPromptBuilder.prompt("User: enna panra\nAURA: sollunga")
        assertTrue(p.contains("style"))
        assertTrue(p.contains("mix them inside one sentence"))
    }
}
