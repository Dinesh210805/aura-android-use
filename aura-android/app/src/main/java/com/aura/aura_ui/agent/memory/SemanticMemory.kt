package com.aura.aura_ui.agent.memory

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Turns text into vectors whose closeness means closeness in meaning. Null = unavailable. */
fun interface Embedder {
    suspend fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray>?
}

/**
 * Gemini's embedding API with the user's own key. Chosen over an on-device model because the
 * memories already travel to Gemini inside every prompt — this sends nothing new — and it costs no
 * APK size and understands mixed Tamil/English. No key, no network: null, and recall stays keyword.
 */
class GeminiEmbedder(
    private val apiKey: () -> String?,
    private val client: OkHttpClient = DEFAULT_CLIENT,
) : Embedder {
    override suspend fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray>? =
        withContext(Dispatchers.IO) {
            val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext null
            if (texts.isEmpty()) return@withContext emptyList()
            runCatching {
                texts.chunked(MAX_BATCH).flatMap { batch -> call(key, batch, isQuery) ?: return@runCatching null }
            }.getOrNull()
        }

    private fun call(key: String, batch: List<String>, isQuery: Boolean): List<FloatArray>? {
        val body = buildJsonObject {
            putJsonArray("requests") {
                batch.forEach { text ->
                    add(
                        buildJsonObject {
                            put("model", "models/$MODEL")
                            putJsonObject("content") {
                                putJsonArray("parts") {
                                    // Asymmetric retrieval prefixes, per the embeddings-2 docs.
                                    val framed = if (isQuery) "task: search result | query: $text" else "title: none | text: $text"
                                    add(buildJsonObject { put("text", framed) })
                                }
                            }
                            put("outputDimensionality", DIMENSIONS)
                        },
                    )
                }
            }
        }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:batchEmbedContents")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Otherwise the only symptom of a bad key or a renamed model is recall quietly going keyword-only.
                Log.w(TAG, "embedding call failed: HTTP ${resp.code}")
                return null
            }
            val embeddings = Json.parseToJsonElement(resp.body?.string() ?: return null)
                .jsonObject["embeddings"]?.jsonArray ?: return null
            if (embeddings.size != batch.size) return null
            return embeddings.map { e ->
                (e.jsonObject["values"] as JsonArray).map { it.jsonPrimitive.float }.toFloatArray()
            }
        }
    }

    companion object {
        private const val TAG = "GeminiEmbedder"
        const val MODEL = "gemini-embedding-2"

        /** Small on purpose: a few hundred memories × 256 bytes stays cheap to rewrite. */
        const val DIMENSIONS = 256
        private const val MAX_BATCH = 100
        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

/** A memory's vector, keyed to the text it was computed from so an edited memory is re-embedded. */
@Serializable
data class StoredVector(val id: String, val textHash: Int, val v: String)

/** Unit-length int8 vectors: 256 bytes each instead of ~2 KB of JSON floats. */
object VectorCodec {
    fun encode(raw: FloatArray): String {
        val norm = sqrt(raw.fold(0.0) { acc, x -> acc + x * x }).takeIf { it > 0 } ?: 1.0
        val bytes = ByteArray(raw.size) { i -> (raw[i] / norm * 127).roundToInt().coerceIn(-127, 127).toByte() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun decode(encoded: String): FloatArray {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        return FloatArray(bytes.size) { bytes[it] / 127f }
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / sqrt(na * nb)
    }
}

/**
 * Hybrid recall: meaning first, keywords as a tiebreak and as the whole answer when embeddings are
 * unavailable. "What food do I like?" shares no word with "is vegetarian"; this is what finds it.
 *
 * Vectors are computed lazily at recall, not on every write — writes happen in run teardown, where
 * a network call has no business. Sensitive memories are never sent to be embedded.
 */
object SemanticRanker {
    /** ponytail: fixed threshold for gemini-embedding-2 at 256 dims; tune from real recalls on device. */
    const val MIN_SIMILARITY = 0.55
    const val KEYWORD_BONUS = 0.05
    const val MAX_BACKFILL = 100

    fun textHash(e: MemoryEntry) = e.text.hashCode()

    /** Entries that need a (new) vector: not sensitive, and missing or computed from older text. */
    fun stale(entries: List<MemoryEntry>, vectors: Map<String, StoredVector>): List<MemoryEntry> =
        entries.filter { !it.sensitive && vectors[it.id]?.textHash != textHash(it) }.take(MAX_BACKFILL)

    fun rank(
        query: FloatArray,
        entries: List<MemoryEntry>,
        vectors: Map<String, StoredVector>,
        keywordHits: (MemoryEntry) -> Int,
        limit: Int,
    ): List<MemoryEntry> =
        entries.mapNotNull { e ->
            val hits = keywordHits(e)
            val sim = vectors[e.id]?.takeIf { it.textHash == textHash(e) }?.let { VectorCodec.cosine(query, VectorCodec.decode(it.v)) } ?: 0.0
            if (sim < MIN_SIMILARITY && hits == 0) null else e to (sim + KEYWORD_BONUS * minOf(hits, 3))
        }
            .sortedWith(compareByDescending<Pair<MemoryEntry, Double>> { it.second }.thenByDescending { it.first.confirmations })
            .take(limit)
            .map { it.first }
}
