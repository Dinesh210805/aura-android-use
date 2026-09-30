package com.aura.aura_ui.agent.research

import com.aura.aura_ui.agent.DeviceFacts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class TaskResearchTest {

    private val facts = DeviceFacts("OnePlus Nord 4", "OnePlus", "CPH2661", "16", null)
    private val goal = "Tell Amma on WhatsApp I'll be 10 minutes late"
    private val labels = listOf(InstalledApp("WhatsApp", "com.whatsapp", null), InstalledApp("Chrome", "com.android.chrome", null))

    private fun ddg(vararg urls: String) = urls.joinToString("") {
        """<a class="result__a" href="//duckduckgo.com/l/?uddg=${java.net.URLEncoder.encode(it, "UTF-8")}">x</a>"""
    }

    private val boilerplate = "We remind you to comply with our terms of service and applicable laws everywhere. ".repeat(20)
    private val steps = "Open the chat, type your message, then tap the Send button to send it."
    private val page = "<p>$boilerplate</p><p>$steps</p>"

    /** A model that writes the phrase, and answers extraction with [extract]. */
    private fun model(extract: (String) -> String?): suspend (String, String) -> String? = { instruction, input ->
        if (instruction == ResearchText.PHRASE_INSTRUCTION) "send a message in WhatsApp" else extract(input)
    }

    @Test fun `the note is what the model pulled out of the page, not the top of the page`() = runTest {
        var seen: String? = null
        val r = TaskResearch(
            ask = model { input -> seen = input; "1. Open the chat.\n2. Tap Send." },
            fetch = { url -> if (url.contains("duckduckgo")) ddg("https://faq.whatsapp.com/1") else page },
        ).research(goal, labels, facts)!!
        assertEquals("faq.whatsapp.com", r.source)
        assertEquals("1. Open the chat.\n2. Tap Send.", r.text)
        assertTrue("the whole page reaches the model, past the boilerplate", seen!!.contains(steps))
        assertTrue("the model is told the task", seen!!.contains(goal))
    }

    @Test fun `a page that does not cover the task is skipped for the next one`() = runTest {
        val r = TaskResearch(
            ask = model { input -> if (input.contains("faq.whatsapp.com/1")) "NONE" else "Tap Send." },
            fetch = { url ->
                if (url.contains("duckduckgo")) ddg("https://faq.whatsapp.com/1", "https://faq.whatsapp.com/2") else page
            },
        ).research(goal, labels, facts)!!
        assertEquals("Tap Send.", r.text)
    }

    @Test fun `at most MAX_PAGES pages are read`() = runTest {
        var reads = 0
        val urls = (1..6).map { "https://faq.whatsapp.com/$it" }.toTypedArray()
        val r = TaskResearch(
            ask = model { "NONE" },
            fetch = { url -> if (url.contains("duckduckgo")) ddg(*urls) else page.also { reads++ } },
        ).research(goal, labels, facts)
        assertNull(r)
        assertEquals(TaskResearch.MAX_PAGES, reads)
    }

    @Test fun `the search query carries the task and the phone, never the person`() = runTest {
        var query: String? = null
        TaskResearch(
            ask = model { null },
            fetch = { url -> if (url.contains("duckduckgo")) { query = URLDecoder.decode(url.substringAfter("q="), "UTF-8"); null } else null },
        ).research(goal, labels, facts)
        assertTrue(query!!.contains("OnePlus Nord 4 Android 16"))
        assertFalse("the person must not leave the phone", query!!.contains("Amma"))
    }

    @Test fun `a failed phrase call still researches from the template`() = runTest {
        var query: String? = null
        TaskResearch(
            ask = { _, _ -> error("model down") },
            fetch = { url -> if (url.contains("duckduckgo")) { query = URLDecoder.decode(url.substringAfter("q="), "UTF-8"); null } else null },
        ).research(goal, labels, facts)
        assertEquals("how to use WhatsApp OnePlus Nord 4 Android 16 official help", query)
    }

    @Test fun `the model reading the page is told the app version and the phone`() = runTest {
        var seen: String? = null
        TaskResearch(
            ask = model { input -> seen = input; "Tap Send." },
            fetch = { url -> if (url.contains("duckduckgo")) ddg("https://faq.whatsapp.com/1") else page },
        ).research(goal, listOf(InstalledApp("WhatsApp", "com.whatsapp", "2.24.19.86")), facts)
        assertTrue(seen!!, seen!!.contains("WhatsApp 2.24"))
        assertTrue(seen!!, seen!!.contains("OnePlus Nord 4, Android 16"))
    }

    @Test fun `google comes first - one model call reads everything it found, and duckduckgo is never touched`() = runTest {
        var asked: String? = null
        var extractions = 0
        val r = TaskResearch(
            ask = model { input -> extractions++; asked = input; "LINK: https://wa.me/{number}\n1. Tap Send." },
            fetch = { error("the search page must not run when Google answered") },
            web = { q, _ ->
                assertTrue(q, q.startsWith("How do I send a message in WhatsApp on my OnePlus Nord 4"))
                assertFalse("the person must not leave the phone", q.contains("Amma"))
                WebResearch.Result(
                    listOf(WebResearch.Source("Google AI Overview", "Use wa.me links."), WebResearch.Source("faq.whatsapp.com", steps)),
                    listOf("google: AI Overview (16 chars)"),
                )
            },
        ).research(goal, labels, facts)!!
        assertEquals(1, extractions)
        assertTrue(asked!!.contains("Use wa.me links.") && asked!!.contains(steps))
        assertEquals("faq.whatsapp.com", r.source)
        assertTrue(r.text.startsWith("LINK:"))
    }

    @Test fun `google blocked or empty falls back to duckduckgo`() = runTest {
        val r = TaskResearch(
            ask = model { "Tap Send." },
            fetch = { url -> if (url.contains("duckduckgo")) ddg("https://faq.whatsapp.com/1") else page },
            web = { _, _ -> WebResearch.Result(emptyList(), listOf("google: blocked"), blocked = true) },
        ).research(goal, labels, facts)!!
        assertEquals("faq.whatsapp.com", r.source)
    }

    @Test fun `google found nothing useful - duckduckgo still gets its turn`() = runTest {
        val r = TaskResearch(
            ask = model { input -> if (input.contains("Google results")) "NONE" else "Tap Send." },
            fetch = { url -> if (url.contains("duckduckgo")) ddg("https://faq.whatsapp.com/1") else page },
            web = { _, _ -> WebResearch.Result(listOf(WebResearch.Source("Google results", "noise")), emptyList()) },
        ).research(goal, labels, facts)!!
        assertEquals("Tap Send.", r.text)
    }

    @Test fun `a failed extraction is no note, never the raw page`() = runTest {
        val r = TaskResearch(
            ask = model { error("model down") },
            fetch = { url -> if (url.contains("duckduckgo")) ddg("https://faq.whatsapp.com/1") else page },
        ).research(goal, labels, facts)
        assertNull(r)
    }

    @Test fun `nothing found is simply no note`() = runTest {
        val r = TaskResearch(ask = { _, _ -> null }, fetch = { null })
            .research("what time is it", labels, facts)
        assertNull(r)
    }
}
