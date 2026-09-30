package com.aura.aura_ui.mcp.bridge

import android.util.Log
import com.aura.mcp.bridge.SearchResultItem
import com.aura.mcp.bridge.WebSearchBridge
import com.aura.mcp.bridge.WebSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Concrete [WebSearchBridge] backed by Tavily's REST `/search` endpoint.
 *
 * Mirrors `aura_live_mcp/services/web_search.py` (the Python implementation)
 * so prompts and tool semantics stay consistent across both servers — same
 * `search_depth`, same `include_answer`, same fallback behaviour when Tavily
 * returns no synthesised answer. The key difference: this version returns
 * structured [SearchResultItem]s rather than a flattened string, since the
 * MCP client (typically an LLM) can render the source list itself.
 *
 * Uses [Dispatchers.IO] so the OkHttp call doesn't block whatever coroutine
 * the MCP tool dispatcher is running on.
 */
class AppWebSearchBridge(
    private val keyStore: TavilyKeyStore,
    private val httpClient: OkHttpClient = defaultClient(),
) : WebSearchBridge {

    override fun isConfigured(): Boolean = keyStore.isConfigured()

    override suspend fun search(
        query: String,
        maxResults: Int,
        topic: String,
    ): WebSearchResult = withContext(Dispatchers.IO) {
        val apiKey = keyStore.getApiKey()
            ?: return@withContext WebSearchResult.MissingApiKey

        val payload = JSONObject().apply {
            put("api_key", apiKey)
            put("query", query)
            put("search_depth", "advanced")
            put("topic", topic)
            put("include_answer", true)
            put("max_results", maxResults.coerceIn(1, 10))
        }

        val request = Request.Builder()
            .url(TAVILY_ENDPOINT)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use WebSearchResult.Error(
                        "Tavily HTTP ${response.code}: ${response.message}",
                    )
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@use WebSearchResult.Error("Tavily returned empty body")
                }
                parseResponse(query, body)
            }
        }.getOrElse { t ->
            Log.w(TAG, "Tavily request failed for query=${query.take(60)}", t)
            WebSearchResult.Error(t.message ?: "unknown network error")
        }
    }

    private fun parseResponse(query: String, body: String): WebSearchResult {
        val root = runCatching { JSONObject(body) }.getOrElse {
            return WebSearchResult.Error("Malformed Tavily JSON: ${it.message}")
        }
        val answer = root.optString("answer", "")
        val items = root.optJSONArray("results")?.toResultList() ?: emptyList()
        return WebSearchResult.Success(query = query, answer = answer, results = items)
    }

    private fun JSONArray.toResultList(): List<SearchResultItem> {
        val out = ArrayList<SearchResultItem>(length())
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            out.add(
                SearchResultItem(
                    title = obj.optString("title", ""),
                    url = obj.optString("url", ""),
                    content = obj.optString("content", ""),
                    score = obj.optDouble("score", 0.0).toFloat(),
                ),
            )
        }
        return out
    }

    companion object {
        private const val TAG = "AppWebSearchBridge"
        private const val TAVILY_ENDPOINT = "https://api.tavily.com/search"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
