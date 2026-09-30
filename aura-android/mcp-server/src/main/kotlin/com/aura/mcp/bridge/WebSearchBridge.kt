package com.aura.mcp.bridge

/**
 * Port for fetching real-time documentation and task instructions from the web.
 *
 * AURA's primary use case is **task-grounding**: when a user says "create a
 * WhatsApp group", the agent calls [search] with a query like "how to create a
 * WhatsApp group official documentation" and uses the returned snippets to
 * reason about which UI elements to tap. The bridge therefore biases toward
 * authoritative sources (Tavily's `include_answer` + `advanced` depth) rather
 * than raw link aggregation.
 *
 * Implementations live in `:app` and read the API key from secure on-device
 * storage. If the user has not configured a key, [search] returns
 * [WebSearchResult.MissingApiKey] so the tool layer can prompt them to open
 * Settings — distinct from a true network error.
 */
interface WebSearchBridge {
    /**
     * True if an API key is currently configured. Cheap; called by the tool
     * layer to short-circuit before doing an HTTPS request.
     */
    fun isConfigured(): Boolean

    /**
     * Run a single web search.
     *
     * @param query natural-language query (e.g. "how to enable dark mode in Instagram")
     * @param maxResults upper bound on result snippets returned; the provider
     *   may return fewer. Defaults to 5 — enough context for an agent to reason
     *   over without bloating the LLM prompt.
     * @param topic provider hint: "general" (default) or "news" for time-sensitive queries.
     */
    suspend fun search(
        query: String,
        maxResults: Int = 5,
        topic: String = "general",
    ): WebSearchResult
}

sealed class WebSearchResult {
    /**
     * Successful search.
     *
     * @param answer Tavily-generated direct answer to the query (may be empty
     *   if the provider couldn't synthesise one — fall back to [results]).
     * @param results ranked list of source snippets, each with a URL the agent
     *   can cite or follow.
     */
    data class Success(
        val query: String,
        val answer: String,
        val results: List<SearchResultItem>,
    ) : WebSearchResult()

    /** No API key configured. The user must open Settings and paste their Tavily key. */
    object MissingApiKey : WebSearchResult()

    /** Network failure, HTTP non-2xx, or malformed response. */
    data class Error(val message: String) : WebSearchResult()
}

/**
 * One result returned by the search provider.
 *
 * Fields mirror Tavily's `/search` response shape so adapters stay thin, but
 * the contract is provider-agnostic: any future bridge (Brave, SerpAPI, etc.)
 * must map its native shape into these four fields.
 */
data class SearchResultItem(
    val title: String,
    val url: String,
    val content: String,
    val score: Float,
)
