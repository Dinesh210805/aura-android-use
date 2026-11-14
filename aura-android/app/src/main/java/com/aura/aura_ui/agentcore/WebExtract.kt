package com.aura.aura_ui.agentcore

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `web_extract`: one web page as readable text, a page of [PAGE_CHARS] at a time, for the model
 * to answer from. The pages a turn read become the source chips under its answer.
 *
 * - Contract: http and https only, public addresses only. Every host and every redirect hop is
 *   resolved through [PublicDns], so a page cannot reach the phone's network (a router, the MCP
 *   bridge on localhost) by name, by redirect or by DNS rebinding. Bodies stop at [MAX_BODY_BYTES].
 * - Fails: a refused address, an HTTP error or a page with no text is an error result; never throws.
 * - Reads/Writes: the network; an in-memory cache of [CACHE_PAGES] pages for [CACHE_MS].
 * - Why its own fetcher: the old agent's research reader keeps only prose lines and caps the page,
 *   which drops tables and the end of long pages.
 */
class WebExtract(private val now: () -> Long = System::currentTimeMillis) {
    private class Cached(val title: String, val text: String, val atMs: Long)

    private val cache = object : LinkedHashMap<String, Cached>(CACHE_PAGES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) = size > CACHE_PAGES
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .dns(PublicDns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Page [page] (from 1) of [url], with the title, or an error sentence for the model. */
    suspend fun read(url: String, page: Int): SourceResult {
        val parsed = url.trim().toHttpUrlOrNull()
            ?: return SourceResult("Give a full http:// or https:// address.", isError = true)
        val key = parsed.toString()
        val doc = synchronized(cache) { cache[key]?.takeIf { now() - it.atMs < CACHE_MS } } ?: fetch(key)
            ?: return SourceResult("Could not read that page: it did not load, is not a public address, or has no text.", isError = true)
        val pages = WebText.pages(doc.text, PAGE_CHARS)
        val n = page.coerceIn(1, pages.size)
        val more = if (pages.size > 1) "\n" + MORE_PAGES.replace("{page}", "$n").replace("{pages}", "${pages.size}") else ""
        return SourceResult("${doc.title.ifBlank { parsed.host }}\n$key\n\n${pages[n - 1]}$more")
    }

    /** The title of [url] if this extractor read it, for the source chip. */
    fun titleOf(url: String): String? = synchronized(cache) { cache[url.toHttpUrlOrNull()?.toString()]?.title }

    private suspend fun fetch(url: String): Cached? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "text/html,text/plain;q=0.9").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val type = response.body?.contentType()?.toString().orEmpty()
                if (type.isNotEmpty() && "html" !in type && "text" !in type) return@use null
                val source = response.body?.source() ?: return@use null
                source.request(MAX_BODY_BYTES)
                val body = source.buffer.snapshot().utf8().take(MAX_BODY_BYTES.toInt())
                val text = if ("html" in type || "<html" in body.take(500).lowercase()) WebText.fromHtml(body) else body.trim()
                text.takeIf { it.isNotBlank() }?.let { Cached(WebText.title(body), it, now()) }
            }
        }.getOrNull()?.also { synchronized(cache) { cache[url] = it } }
    }

    companion object {
        const val PAGE_CHARS = 12_000
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
        const val CACHE_MS = 20 * 60_000L
        private const val CACHE_PAGES = 24
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        const val MORE_PAGES = "[Page {page} of {pages}. If the answer needs more, call web_extract again with the same url and " +
            "page=N. Otherwise answer from this.]"
    }
}

/**
 * Resolves only to public addresses; anything private, loopback, link-local, carrier-grade NAT,
 * unique-local IPv6 or multicast fails as if the host did not exist.
 */
object PublicDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val public = all.filter { isPublic(it) }
        if (public.isEmpty()) throw UnknownHostException("$hostname is not a public address")
        return public
    }

    fun isPublic(address: InetAddress): Boolean {
        val ip = (address as? Inet6Address)?.let(::mappedV4) ?: address
        if (ip.isLoopbackAddress || ip.isAnyLocalAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || ip.isMulticastAddress) return false
        val b = ip.address.map { it.toInt() and 0xff }
        return when (ip) {
            is Inet4Address -> !(b[0] == 0 || b[0] == 10 || (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
                (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || (b[0] == 198 && b[1] in 18..19) || b[0] >= 224)
            else -> !((b[0] and 0xfe) == 0xfc || (b[0] == 0xfe && (b[1] and 0xc0) == 0x80))
        }
    }

    /** The IPv4 address inside a v4-mapped IPv6 address (::ffff:a.b.c.d), or null. */
    private fun mappedV4(address: Inet6Address): InetAddress? {
        val b = address.address
        val mapped = (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
        return if (mapped) InetAddress.getByAddress(b.copyOfRange(12, 16)) else null
    }
}

/** HTML to readable text, and the paging of long text. Pure. */
object WebText {
    fun title(html: String): String =
        TITLE.find(html)?.groupValues?.get(1)?.let(::entities)?.replace(SPACES, " ")?.trim()?.take(MAX_TITLE).orEmpty()

    fun fromHtml(html: String): String {
        var s = DROP_BLOCKS.replace(html, " ")
        s = BREAKS.replace(s, "\n")
        s = TAGS.replace(s, " ")
        s = entities(s)
        return s.lineSequence().map { SPACES.replace(it, " ").trim() }.filter { it.isNotEmpty() }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** [text] cut into pages of about [size] characters, at a line break where one is near. */
    fun pages(text: String, size: Int): List<String> {
        if (text.length <= size) return listOf(text)
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + size).coerceAtMost(text.length)
            if (end < text.length) text.lastIndexOf('\n', end).takeIf { it > start + size / 2 }?.let { end = it }
            out += text.substring(start, end).trim()
            start = end
        }
        return out
    }

    private fun entities(text: String): String = ENTITIES.fold(text) { acc, (k, v) -> acc.replace(k, v) }
        .replace(NUMERIC) { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 32..0x10FFFF }?.let { String(Character.toChars(it)) } ?: " " }

    private const val MAX_TITLE = 120
    private val TITLE = Regex("""(?is)<title[^>]*>(.*?)</title>""")
    private val DROP_BLOCKS = Regex("""(?is)<(script|style|noscript|svg|head|nav|footer|form|iframe)[^>]*>.*?</\1>|<!--.*?-->""")
    private val BREAKS = Regex("""(?i)<br\s*/?>|</(p|li|h[1-6]|div|tr|table|section|article|blockquote|pre)>""")
    private val TAGS = Regex("""<[^>]+>""")
    private val SPACES = Regex("""[ \t ]+""")
    private val NUMERIC = Regex("""&#(\d{1,7});""")
    private val ENTITIES = listOf(
        "&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&#x27;" to "'", "&apos;" to "'", "&amp;" to "&",
    )
}

/** The `web_extract` tool. The URL leaves the phone, so the gate treats it as a send-out, like `web_search`. */
fun webTools(extract: WebExtract): List<NativeTool> = listOf(
    NativeTool(
        ToolSpec(
            ToolDescriptor(
                name = "web_extract",
                description = "Download one web page and return its readable text: title, then text. Use it to read a page " +
                    "web_search found, or a link the user gave, before answering from it. Prefer it to the browser when the " +
                    "user only needs information. The URL leaves the phone: never put private details in it. Results are " +
                    "cached for 20 minutes.",
                requiredParameters = listOf(
                    ToolParameterDescriptor("url", "The page's full address, starting with http:// or https://.", ToolParameterType.String),
                ),
                optionalParameters = listOf(
                    ToolParameterDescriptor(
                        "page",
                        "Which part of a long page, from 1. Default 1. Pages hold about 12,000 characters each.",
                        ToolParameterType.Integer,
                    ),
                ),
            ),
            ToolRisk.READ,
            ToolOrigin.NATIVE,
            ToolHub.CORE,
            resultOrigin = Origin.WEB,
            egress = true,
        ),
    ) { args, turn ->
        val url = args["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        extract.read(url, args["page"]?.jsonPrimitive?.intOrNull ?: 1).also { result ->
            if (!result.isError) turn.cite(url.trim(), extract.titleOf(url) ?: url)
        }
    },
)
