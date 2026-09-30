package com.aura.aura_ui.agent.research

import com.aura.aura_ui.agent.DeviceFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchTextTest {

    private val facts = DeviceFacts("OnePlus Nord 4", "OnePlus", "CPH2661", "16", "16.0.5")
    private val goal = "Tell Amma on WhatsApp I'll be 10 minutes late"

    private val whatsApp = InstalledApp("WhatsApp", "com.whatsapp", "2.24.19.86")
    private val mapsApp = InstalledApp("Maps", "com.google.android.apps.maps", "26.38.02.123456")
    private val googleApp = InstalledApp("Google", "com.google.android.googlequicksearchbox", "16.1.0")

    @Test fun `the app is matched by label, whole words only`() {
        val apps = listOf(whatsApp, mapsApp, InstalledApp("Go", "com.example.go", null))
        assertEquals(whatsApp, ResearchText.matchApp(goal, apps))
        assertNull("'Go' must not match inside 'Google'", ResearchText.matchApp("Google it", listOf(InstalledApp("Go", "com.example.go", null))))
    }

    @Test fun `"Google Maps" picks Maps, not Google - the package covers both words`() {
        // 2026-09-24: research matched "Google" because it was the longer label.
        val goal = "Open Google Maps, set the starting point to Puducherry and add stops"
        assertEquals(mapsApp, ResearchText.matchApp(goal, listOf(googleApp, mapsApp)))
    }

    @Test fun `the query names the app's version, major and minor only`() {
        val q = ResearchText.compose("navigate in Google Maps with multiple stops", mapsApp, facts)!!
        assertTrue(q, q.contains("Maps 26.38"))
        assertFalse("the build number over-narrows the search", q.contains("26.38.02"))
    }

    @Test fun `short version keeps major and minor, and rejects nonsense`() {
        assertEquals("26.38", ResearchText.shortVersion("26.38.02.123456"))
        assertEquals("7", ResearchText.shortVersion("7"))
        assertNull(ResearchText.shortVersion("beta"))
        assertNull(ResearchText.shortVersion(null))
    }

    @Test fun `the query carries the task, app and phone - never the person or the message`() {
        val phrase = ResearchText.cleanPhrase("send a message in WhatsApp", goal)
        val q = ResearchText.compose(phrase, whatsApp, facts)!!
        assertTrue(q.contains("WhatsApp"))
        assertTrue(q.contains("OnePlus Nord 4 Android 16"))
        assertFalse(q.contains("Amma", ignoreCase = true))
        assertFalse(q.contains("late", ignoreCase = true))
    }

    @Test fun `the template fallback names only the app and the phone`() {
        val q = ResearchText.compose(null, whatsApp.copy(version = null), facts)!!
        assertEquals("how to use WhatsApp OnePlus Nord 4 Android 16 official help", q)
        assertFalse(q.contains("Amma"))
    }

    @Test fun `no phrase and no app means no query`() {
        assertNull(ResearchText.compose(null, null, facts))
    }

    @Test fun `a phrase that leaks quoted text or numbers is cleaned`() {
        val g = "text Ravi \"meeting moved to 5\" on +91 98765 43210"
        val cleaned = ResearchText.cleanPhrase("send \"meeting moved to 5\" to +91 98765 43210 in Messages", g)!!
        assertFalse(cleaned.contains("meeting moved"))
        assertFalse(cleaned.contains("98765"))
        assertFalse(cleaned.contains("<"))
    }

    @Test fun `duckduckgo redirect links are decoded to their target`() {
        val html = """<a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Ffaq.whatsapp.com%2F123&amp;rut=abc">x</a>"""
        assertEquals(listOf("https://faq.whatsapp.com/123"), ResearchText.duckDuckGoLinks(html))
    }

    @Test fun `official help hosts come first, generic sites after, forums never`() {
        val urls = listOf(
            "https://www.reddit.com/r/whatsapp/abc",
            "https://www.techblog.com/whatsapp-tips",
            "https://faq.whatsapp.com/1234",
        )
        assertEquals(
            listOf("https://faq.whatsapp.com/1234", "https://www.techblog.com/whatsapp-tips"),
            ResearchText.rankOfficial(urls, listOf("WhatsApp", "OnePlus")),
        )
    }

    @Test fun `forums are never picked, even alone`() {
        assertTrue(ResearchText.rankOfficial(listOf("https://www.youtube.com/watch?v=1"), listOf("WhatsApp")).isEmpty())
    }

    @Test fun `html becomes prose lines without scripts or menus`() {
        val html = "<html><head><title>t</title></head><body><nav>Home Menu</nav>" +
            "<script>var x=1;</script><p>Open WhatsApp and tap the chat you want to send a message to.</p>" +
            "<li>Short</li></body></html>"
        val text = ResearchText.htmlToText(html)
        assertEquals("Open WhatsApp and tap the chat you want to send a message to.", text)
    }

    @Test fun `page text is not cut to note size - the useful part is often past the first screenful`() {
        val html = "<p>" + "A long paragraph of help text that goes on and on. ".repeat(100) + "</p>"
        assertTrue(ResearchText.htmlToText(html).length > ResearchText.MAX_NOTE_CHARS)
    }

    @Test fun `an extraction reply of NONE or nothing is no note`() {
        assertNull(ResearchText.cleanExtract("NONE"))
        assertNull(ResearchText.cleanExtract("  none.  "))
        assertNull(ResearchText.cleanExtract("   "))
        assertNull(ResearchText.cleanExtract(null))
        assertEquals("1. Tap Directions.", ResearchText.cleanExtract(" 1. Tap Directions. "))
    }

    @Test fun `an extraction reply is capped at note size`() {
        assertEquals(ResearchText.MAX_NOTE_CHARS, ResearchText.cleanExtract("x".repeat(5_000))!!.length)
    }
}
