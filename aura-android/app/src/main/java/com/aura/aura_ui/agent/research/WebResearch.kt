package com.aura.aura_ui.agent.research

import com.aura.mcp.bridge.BrowserAction
import com.aura.mcp.bridge.BrowserBridge
import com.aura.mcp.bridge.BrowserPage
import com.aura.mcp.bridge.BrowserResult
import com.aura.mcp.bridge.BrowserSessionMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

/**
 * Ask Google the way a person does, in a real browser that is never shown: search, read the AI
 * Overview (expanded) and the official page it cites; when there is no overview, open the AI Mode
 * tab and read its answer once it stops growing; failing both, read the results page.
 *
 * A real Chromium WebView, not a scripted HTTP GET: the 2026-09-24 test searched Google this way
 * with no bot check, while a plain request to DuckDuckGo was challenged on its second search. The
 * AI Mode tab is TAPPED rather than opened by URL so Google's own current link is followed — its
 * parameters change, a hard-coded one would quietly break.
 *
 * Takes the [BrowserBridge] port so every branch is JVM-testable; production passes
 * `AppBrowserBridge.research(context)`, a second browser that never shows and never publishes.
 * Returns what it read; deciding what matters in it is the caller's model call.
 */
class WebResearch(private val browser: BrowserBridge) {

    /** One thing read: where it came from ("Google AI Overview", a host) and its text. */
    data class Source(val where: String, val text: String)

    data class Result(val sources: List<Source>, val steps: List<String>, val blocked: Boolean = false)

    suspend fun search(question: String, hints: List<String>): Result = LOCK.withLock {
        val steps = mutableListOf<String>()
        val first = browser.open(googleUrl(question), MODE, visible = false)
        if (first !is BrowserResult.Success) {
            steps += "google: did not load (${(first as BrowserResult.Failure).message})"
            return@withLock Result(emptyList(), steps)
        }
        var page = first.value
        if (isBlocked(page.url)) {
            steps += "google: blocked (${page.url.substringBefore('?')})"
            return@withLock Result(emptyList(), steps, blocked = true)
        }

        val sources = mutableListOf<Source>()
        if (page.text.contains(OVERVIEW)) {
            page.button(SHOW_MORE)?.let { more -> click(more)?.let { page = it } }
            sources += Source("Google AI Overview", page.text.substring(page.text.indexOf(OVERVIEW).coerceAtLeast(0)).take(MAX_SOURCE_CHARS))
            steps += "google: AI Overview (${sources.last().text.length} chars)"
            citedOfficial(page, hints)?.let { link ->
                val source = click(link)
                if (source != null && !isBlocked(source.url) && ResearchText.hostOf(source.url) != "google.com") {
                    sources += Source(ResearchText.hostOf(source.url) ?: source.url, source.text.take(MAX_SOURCE_CHARS))
                    steps += "read ${source.url} (${source.text.length} chars)"
                }
            }
        } else {
            val tab = page.elements.firstOrNull { it.role == "link" && it.label.trim().equals(AI_MODE, ignoreCase = true) }
            val answer = tab?.let { click(it) }?.let { settled(it) }
            if (answer != null) {
                sources += Source("Google AI Mode", answer.text.take(MAX_SOURCE_CHARS))
                steps += "google: no overview; AI Mode (${answer.text.length} chars)"
            } else {
                sources += Source("Google results", page.text.take(MAX_SOURCE_CHARS))
                steps += "google: no overview, no AI Mode; results page (${page.text.length} chars)"
            }
        }
        Result(sources, steps)
    }

    private suspend fun click(element: com.aura.mcp.bridge.RawElement): BrowserPage? =
        (browser.act(MODE, element.selector, BrowserAction.CLICK) as? BrowserResult.Success)?.value

    /** AI Mode writes its answer in; read until one poll shows no growth, capped. */
    private suspend fun settled(start: BrowserPage): BrowserPage {
        var current = start
        var waited = 0L
        while (waited < AI_MODE_WAIT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
            val next = (browser.read(MODE) as? BrowserResult.Success)?.value ?: break
            val grew = next.text.length != current.text.length
            current = next
            if (!grew) break
        }
        return current
    }

    /** The cited or listed page most likely to be official: a link whose label carries its URL. */
    private fun citedOfficial(page: BrowserPage, hints: List<String>): com.aura.mcp.bridge.RawElement? {
        val byUrl = page.elements
            .filter { it.role == "link" }
            .mapNotNull { el -> URL_IN_LABEL.find(el.label)?.value?.let { it to el } }
            .filter { (url, _) -> ResearchText.hostOf(url) != "google.com" }
            .distinctBy { it.first }
        val best = ResearchText.rankOfficial(byUrl.map { it.first }, hints).firstOrNull() ?: return null
        return byUrl.first { it.first == best }.second
    }

    private fun BrowserPage.button(label: String) =
        elements.firstOrNull { it.role == "button" && it.label.contains(label, ignoreCase = true) }

    companion object {
        private val MODE = BrowserSessionMode.SCRATCH

        /** One look-up at a time: pre-task research and `look_up` share the one hidden browser. */
        private val LOCK = Mutex()

        private const val OVERVIEW = "AI Overview"
        private const val SHOW_MORE = "Show more AI Overview"
        private const val AI_MODE = "AI Mode"

        const val MAX_SOURCE_CHARS = 8_000
        const val AI_MODE_WAIT_MS = 15_000L
        const val POLL_MS = 1_000L

        private val URL_IN_LABEL = Regex("""https://[^\s]+""")

        /** `hl=en` keeps the labels this class matches on ("AI Mode", "Show more AI Overview") in English. */
        fun googleUrl(question: String): String =
            "https://www.google.com/search?hl=en&q=" + URLEncoder.encode(question, "UTF-8")

        /** Google's "unusual traffic" and consent interstitials — decided by URL, never by page text. */
        fun isBlocked(url: String): Boolean =
            url.contains("/sorry/") || ResearchText.hostOf(url)?.startsWith("consent.") == true
    }
}
