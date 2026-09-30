package com.aura.aura_ui.agent.memory

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SemanticRecallTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    /**
     * A stand-in for a real embedding model: each text maps onto a few "concept" axes, so texts
     * about the same thing point the same way without sharing a single word.
     */
    private class ConceptEmbedder : Embedder {
        val embedded = mutableListOf<String>()
        var available = true
        private val concepts = listOf(
            listOf("food", "eat", "vegetarian", "biryani", "chicken"),
            listOf("sister", "family", "amma", "brother"),
            listOf("work", "benchmark", "office", "project"),
        )

        override suspend fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray>? {
            if (!available) return null
            if (!isQuery) embedded += texts
            return texts.map { t ->
                val lower = t.lowercase()
                FloatArray(concepts.size + 1) { i ->
                    if (i == concepts.size) 0.1f else if (concepts[i].any { it in lower }) 1f else 0f
                }
            }
        }
    }

    private suspend fun seeded(name: String, embedder: Embedder?): EncryptedMemoryService {
        val svc = EncryptedMemoryService(EncryptedJsonStore(ctx, name))
        svc.save(MemoryType.USER, "is vegetarian")
        svc.save(MemoryType.USER, "sister lives in Coimbatore")
        svc.save(MemoryType.USER, "runs the AndroidWorld benchmark")
        svc.embedder = embedder
        return svc
    }

    @Test fun `a question that shares no word with the memory still finds it`() = runTest {
        val svc = seeded("semantic_1", ConceptEmbedder())
        val hits = svc.recall("what food do I like", limit = 5)
        assertEquals("is vegetarian", hits.first().text)
        assertTrue("unrelated memories stay out", hits.none { it.text.contains("Coimbatore") })
    }

    @Test fun `without embeddings recall is exactly the old keyword recall`() = runTest {
        val svc = seeded("semantic_2", ConceptEmbedder().apply { available = false })
        assertTrue(svc.recall("what food do I like", limit = 5).isEmpty())
        assertEquals("sister lives in Coimbatore", svc.recall("Coimbatore", limit = 5).single().text)
    }

    @Test fun `vectors are computed once, and again only for a memory whose text changed`() = runTest {
        val embedder = ConceptEmbedder()
        val svc = seeded("semantic_3", embedder)
        svc.recall("food", limit = 5)
        svc.recall("family", limit = 5)
        assertEquals(3, embedder.embedded.size)

        svc.saveResolved(MemoryType.USER, "eats chicken now", subject = "", supersedesId = svc.allEntries().first { it.text == "is vegetarian" }.id)
        svc.recall("food", limit = 5)
        assertEquals(listOf("eats chicken now"), embedder.embedded.drop(3))
    }

    @Test fun `sensitive memories are never sent to be embedded`() = runTest {
        val embedder = ConceptEmbedder()
        val svc = EncryptedMemoryService(EncryptedJsonStore(ctx, "semantic_4"))
        svc.save(MemoryType.USER, "is vegetarian")
        val sensitive = svc.allEntries().first().copy(id = "s", text = "diagnosed with diabetes", sensitive = true)
        assertTrue(SemanticRanker.stale(listOf(sensitive), emptyMap()).isEmpty())
        svc.embedder = embedder
        svc.recall("food", limit = 5)
        assertTrue(embedder.embedded.none { it.contains("diabetes") })
    }

    @Test fun `int8 round trip keeps direction`() {
        val v = floatArrayOf(0.3f, -0.9f, 0.1f, 0.0f)
        val back = VectorCodec.decode(VectorCodec.encode(v))
        assertTrue(VectorCodec.cosine(v, back) > 0.999)
    }
}
