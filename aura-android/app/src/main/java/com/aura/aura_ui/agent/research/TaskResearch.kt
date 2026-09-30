package com.aura.aura_ui.agent.research

import android.content.Context
import android.content.Intent
import android.util.Log
import com.aura.aura_ui.agent.DeviceFacts
import com.aura.aura_ui.agent.ledger.ResearchNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Pre-task research: before the agent has taken its first step, look up how the task is done on
 * THIS phone and app from the official help pages, and hand the notes to the run's ledger.
 *
 * ### Ask Google first, in a hidden browser
 *
 * [web] asks Google a natural question ([ResearchText.question]) in a WebView that is never
 * shown — the AI Overview and its official source, or the AI Mode answer ([WebResearch]) — and
 * the model reads all of it in one call. Blocked or empty falls through to the search page below.
 *
 * ### Fallback: find with a search page, read with the model
 *
 * DuckDuckGo's HTML page (Bing when it returns nothing) finds candidate pages — no key, so it
 * works on every provider. The run's own model then reads each official page and writes down only
 * what helps THIS task, or NONE; up to [MAX_PAGES] pages until one answers. The old note was the
 * first 1,200 characters of the page, which on the Maps run (2026-09-24) was terms-of-service
 * boilerplate ending one line before "add more destinations" — the one thing the task needed.
 *
 * Spec: `docs/superpowers/specs/2026-09-23-agent-instructions-rewrite.md` §6.
 *
 * ### It helps; it never holds the run back
 *
 * Started by the harness at run start, in parallel with turn 1, and never awaited. Every step
 * fails soft to "no notes": no query, no search route, a blocked search engine, a timeout. The
 * agent's doctrine says to carry on without notes, and the run is otherwise unchanged.
 *
 * ### Why it does not use the run's tool chain or AURA's browser
 *
 * Running concurrently with the agent, a call through the hook chain would make ActionGuard mark
 * the screen changed mid-turn and refuse the agent's next tap, and the in-app browser would
 * navigate the very tab the agent may be using. So every fetch is a plain HTTPS request that
 * touches nothing on the phone.
 *
 * ### Privacy
 *
 * The query is built from a generic task phrase ([ResearchText.cleanPhrase]), the matched app's
 * label and this phone's name and Android version — never the raw request. The phrase and the page
 * reading come from the user's own configured model, which already receives the request.
 */
class TaskResearch(
    /** One model call — system instruction, user text — returning the reply, or null. */
    private val ask: suspend (instruction: String, input: String) -> String?,
    /** HTTPS GET returning the body, or null. [httpGet] below is the production one. */
    private val fetch: suspend (url: String) -> String?,
    /** What happened, for the run log — called once, whatever the outcome. */
    private val onTrace: (ResearchTrace) -> Unit = {},
    /**
     * Google in a hidden browser ([WebResearch]), tried before the search-page route. Null in
     * tests and wherever no browser exists; an empty or blocked result falls through to it.
     */
    private val web: (suspend (question: String, hints: List<String>) -> WebResearch.Result)? = null,
) {

    /** One research attempt, as the log shows it. Every step is recorded, including the ones that found nothing. */
    data class ResearchTrace(
        val app: String?,
        val phrase: String?,
        val query: String?,
        val steps: List<String>,
        val source: String?,
        val note: String?,
        val outcome: String,
        val durationMs: Long,
    )

    private val steps = mutableListOf<String>()
    private var app: String? = null
    private var phrase: String? = null
    private var query: String? = null

    suspend fun research(goal: String, installedApps: Collection<InstalledApp>, facts: DeviceFacts): ResearchNote? {
        val started = System.currentTimeMillis()
        var failure: String? = null
        var finished = false
        val note = try {
            withTimeoutOrNull(DEADLINE_MS) { run(goal, installedApps, facts).also { finished = true } }
        } catch (e: CancellationException) {
            report("cancelled (run ended first)", null, started)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "research failed soft: ${e.message}")
            failure = "failed: ${e.message}"
            null
        }
        val outcome = when {
            note != null -> "found"
            failure != null -> failure
            !finished -> "timed out after ${DEADLINE_MS / 1000}s"
            query == null -> "no query (no app matched and no phrase)"
            else -> "nothing found"
        }
        report(outcome, note, started)
        return note
    }

    private fun report(outcome: String, note: ResearchNote?, started: Long) {
        runCatching {
            onTrace(
                ResearchTrace(app, phrase, query, steps.toList(), note?.source, note?.text, outcome, System.currentTimeMillis() - started),
            )
        }
    }

    private suspend fun run(goal: String, apps: Collection<InstalledApp>, facts: DeviceFacts): ResearchNote? {
        val matched = ResearchText.matchApp(goal, apps)
        app = matched?.let(ResearchText::appWithVersion)
        steps += "app matched: " + (matched?.let { "${ResearchText.appWithVersion(it)} (${it.packageName})" } ?: "none")
        val raw = withTimeoutOrNull(PHRASE_TIMEOUT_MS) {
            runCatching { ask(ResearchText.PHRASE_INSTRUCTION, goal) }
                .onFailure { steps += "phrase call failed: ${it.message}" }
                .getOrNull()
        }
        if (raw == null) steps += "phrase: none (template used)"
        phrase = raw?.let { ResearchText.cleanPhrase(it, goal) }
        if (raw != null) steps += "phrase: model said \"${raw.trim()}\" -> cleaned \"${phrase ?: "(rejected)"}\""
        val q = ResearchText.compose(phrase, matched, facts) ?: return null
        query = q
        Log.i(TAG, "researching: $q")
        val hints = listOfNotNull(matched?.label, matched?.packageName?.split('.')?.getOrNull(1), facts.maker)
        val setting = ResearchText.setting(matched, facts)

        web?.let { search ->
            val question = ResearchText.question(phrase, matched, facts) ?: q
            steps += "google: asked \"$question\""
            val found = runCatching { search(question, hints) }
                .onFailure { steps += "google: failed: ${it.message}" }
                .getOrNull()
            found?.steps?.let { steps += it }
            if (found != null && found.sources.isNotEmpty()) readSources(goal, setting, found.sources)?.let { return it }
        }

        val encoded = URLEncoder.encode(q, "UTF-8")
        var links = fetch("https://html.duckduckgo.com/html/?q=$encoded")?.let(ResearchText::duckDuckGoLinks).orEmpty()
        steps += "duckduckgo: ${links.size} links"
        if (links.isEmpty()) {
            links = fetch("https://www.bing.com/search?q=$encoded")?.let(ResearchText::bingLinks).orEmpty()
            steps += "bing: ${links.size} links"
        }
        val pages = ResearchText.rankOfficial(links, hints).take(MAX_PAGES)
        if (pages.isEmpty()) steps += "no readable page among the results"
        for (url in pages) readPage(goal, setting, url)?.let { return it }
        return null
    }

    /**
     * Everything Google gave (overview, cited page, AI Mode answer) read by the model in ONE call —
     * one request, not one per source, because free-tier request limits are per minute. The note is
     * credited to the first non-Google source when there is one.
     */
    private suspend fun readSources(goal: String, setting: String, sources: List<WebResearch.Source>): ResearchNote? {
        val where = sources.joinToString(" + ") { it.where }
        val text = sources.joinToString("\n\n") { "[${it.where}]\n${it.text}" }
        val reply = runCatching { ask(ResearchText.EXTRACT_INSTRUCTION, ResearchText.extractInput(goal, setting, where, text)) }
            .onFailure { steps += "google: model call failed: ${it.message}" }
            .getOrNull()
        val note = ResearchText.cleanExtract(reply)
        steps += "google: read $where (${text.length} chars): " + if (note != null) "kept ${note.length} chars" else "nothing for this task"
        val credit = sources.firstOrNull { !it.where.startsWith("Google") }?.where ?: "google.com"
        return note?.let { ResearchNote(credit, it) }
    }

    /** One page: fetch it, have the model pull out what helps [goal]. Null = try the next one. */
    private suspend fun readPage(goal: String, setting: String, url: String): ResearchNote? {
        val text = fetch(url)?.let(ResearchText::htmlToText)?.takeIf { it.isNotBlank() }
        if (text == null) {
            steps += "read $url: no text"
            return null
        }
        val reply = runCatching { ask(ResearchText.EXTRACT_INSTRUCTION, ResearchText.extractInput(goal, setting, url, text)) }
            .onFailure { steps += "read $url: model call failed: ${it.message}" }
            .getOrNull()
        val note = ResearchText.cleanExtract(reply)
        steps += "read $url (${text.length} chars): " + if (note != null) "kept ${note.length} chars" else "nothing for this task"
        return note?.let { ResearchNote(ResearchText.hostOf(url) ?: url, it) }
    }

    companion object {
        private const val TAG = "TaskResearch"

        /**
         * The whole lookup. Turn 1 does not wait for it; this only bounds the background work.
         * Room for the Google route (load, expand, cited page, or AI Mode streaming for up to
         * [WebResearch.AI_MODE_WAIT_MS]) and then the search-page fallback.
         */
        const val DEADLINE_MS = 60_000L

        /** The phrase call must never cost more than this — a slow model means the template. */
        const val PHRASE_TIMEOUT_MS = 6_000L

        /** Pages read before giving up: one model call each, stopping at the first that answers. */
        const val MAX_PAGES = 3

        private const val MAX_BODY_BYTES = 400_000L

        /** Short, bounded client: a half-open socket must end as a failure, never a hang. */
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        /** Every launchable app with its version — what [ResearchText.matchApp] matches the goal against. */
        fun launcherApps(context: Context): List<InstalledApp> = runCatching {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .distinctBy { it.activityInfo.packageName }
                .map { ri ->
                    val pkg = ri.activityInfo.packageName
                    val version = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()
                    InstalledApp(ri.loadLabel(pm).toString(), pkg, version)
                }
        }.getOrDefault(emptyList())

        /** HTTPS only, body capped, any failure → null. Blocking I/O: call from Dispatchers.IO. */
        fun httpGet(url: String): String? {
            if (!url.startsWith("https://")) return null
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile Safari/537.36")
                .header("Accept-Language", "en")
                .build()
            return runCatching {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    resp.body?.source()?.let { src ->
                        src.request(MAX_BODY_BYTES)
                        src.buffer.readUtf8(minOf(src.buffer.size, MAX_BODY_BYTES))
                    }
                }
            }.getOrNull()
        }
    }
}
