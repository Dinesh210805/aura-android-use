package com.aura.aura_ui.agent.llm

import com.aura.aura_ui.data.AppInfo
import com.aura.mcp.bridge.RestrictedAppCategory
import com.aura.mcp.bridge.RestrictedAppEntry
import com.aura.mcp.bridge.RestrictedAppSource
import com.aura.mcp.bridge.RestrictedTier
import org.json.JSONArray
import org.json.JSONException

/**
 * Pure parsing for [RestrictedAppClassifier]'s LLM responses — no Android
 * [android.content.Context], no network, so it's plain-JVM testable. Defensive
 * by design: an LLM's raw text response is untrusted input, not a typed API
 * contract, so every step (fence stripping, JSON parsing, package matching,
 * category matching) fails soft (skip the one bad element) rather than
 * throwing and losing the whole batch.
 */
internal object RestrictedAppResponseParser {

    private val FENCE_REGEX = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    /**
     * Parses [raw] (the model's text response for one [chunk] of the request)
     * into suggestion entries. Only packages present in [chunk] are trusted —
     * a model-invented package name is dropped, never surfaced as a suggestion.
     */
    fun parse(raw: String, chunk: List<AppInfo>, nowMs: Long): List<RestrictedAppEntry> {
        val byPackage = chunk.associateBy { it.packageName }
        val jsonText = extractJsonArray(raw) ?: return emptyList()
        val array = try {
            JSONArray(jsonText)
        } catch (e: JSONException) {
            return emptyList()
        }
        val entries = mutableListOf<RestrictedAppEntry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val pkg = obj.optString("package").takeIf { it.isNotBlank() } ?: continue
            val app = byPackage[pkg] ?: continue
            val category = parseCategory(obj.optString("category")) ?: continue
            val tier = when (category) {
                RestrictedAppCategory.BANKING, RestrictedAppCategory.PAYMENT -> RestrictedTier.DISABLE_ACCESSIBILITY
                else -> RestrictedTier.DECLINE_ONLY
            }
            entries += RestrictedAppEntry(
                packageName = app.packageName,
                appName = app.appName,
                category = category,
                tier = tier,
                source = RestrictedAppSource.LLM_SUGGESTED,
                addedAt = nowMs,
            )
        }
        return entries
    }

    fun parseCategory(raw: String): RestrictedAppCategory? =
        when (raw.trim().uppercase()) {
            "BANKING" -> RestrictedAppCategory.BANKING
            "PAYMENT" -> RestrictedAppCategory.PAYMENT
            "TRADING_CRYPTO" -> RestrictedAppCategory.TRADING_CRYPTO
            "AUTH_PASSWORD" -> RestrictedAppCategory.AUTH_PASSWORD
            else -> null // NONE / OTHER / garbage — not a suggestion
        }

    /** Strips a ```json ... ``` fence if present, else returns the first `[...]` span found. */
    fun extractJsonArray(raw: String): String? {
        val fenced = FENCE_REGEX.find(raw)?.groupValues?.get(1)
        val candidate = fenced ?: raw
        val start = candidate.indexOf('[')
        val end = candidate.lastIndexOf(']')
        if (start == -1 || end == -1 || end < start) return null
        return candidate.substring(start, end + 1)
    }
}
