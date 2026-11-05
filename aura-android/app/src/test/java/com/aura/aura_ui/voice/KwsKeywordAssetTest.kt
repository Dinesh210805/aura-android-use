package com.aura.aura_ui.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the ONE hand-crafted artifact in the sherpa-onnx wake-word setup:
 * `assets/kws/keywords.txt`. Its BPE tokenization of "Wake up AURA" was produced from
 * the GigaSpeech model's `bpe.model` and is easy to break with a well-meaning edit.
 *
 * A wrong token line does NOT crash — the spotter simply never fires, which is the
 * worst kind of regression (silent). These pure-JVM checks catch it at build time.
 *
 * The detector itself (AudioRecord + native KeywordSpotter) is validated on-device;
 * it cannot run under a JVM unit test because `libsherpa-onnx-jni.so` is unavailable.
 */
class KwsKeywordAssetTest {

    private val assetsDir = File("src/main/assets/kws")
    private val keywordsFile = File(assetsDir, "keywords.txt")
    private val tokensFile = File(assetsDir, "tokens.txt")

    // Bare BPE tokens only — boost/threshold come from KeywordSpotterConfig, and a
    // trailing "@…" display phrase is FORBIDDEN: a space in it makes sherpa's native
    // encoder read the tail as an unknown token and abort() the process.
    // The only wake phrase is "Wake up AURA": "wake up" is a strong common-word anchor and the
    // "aura" tail is caught by the ▁A/▁OR/▁OUR spellings.
    private val expectedLine = "▁WA KE ▁UP ▁A UR A"

    @Test
    fun `keywords file exists and the first variant is the literal Wake up AURA tokenization`() {
        assertTrue("keywords.txt missing at ${keywordsFile.absolutePath}", keywordsFile.exists())
        val line = keywordsFile.readLines().firstOrNull { it.isNotBlank() }?.trim()
        assertEquals(expectedLine, line)
    }

    @Test
    fun `every keyword variant has NO trailing phrase and all tokens are in vocab`() {
        assertTrue("tokens.txt missing at ${tokensFile.absolutePath}", tokensFile.exists())

        // Vocab tokens are the first whitespace-separated column of tokens.txt.
        val vocab = tokensFile.readLines()
            .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.takeIf(String::isNotEmpty) }
            .toSet()

        val lines = keywordsFile.readLines().filter { it.isNotBlank() }
        assertTrue("expected multiple phonetic variants", lines.size >= 2)
        assertTrue("only \"Wake up AURA\" may wake AURA", lines.all { it.startsWith("▁WA KE ▁UP ") })

        lines.forEach { raw ->
            // A space-bearing "@phrase" makes sherpa's native encoder abort() the
            // whole process — forbid any '@' display tail (the SIGABRT we hit).
            assertTrue("keyword line must not carry an @display phrase: \"$raw\"", '@' !in raw)

            val tokens = raw.substringBefore(" :").substringBefore(" #").trim().split(Regex("\\s+"))
            tokens.forEach { tok ->
                assertTrue(
                    "wake-word token '$tok' (line \"$raw\") not in model vocab → SIGABRT / never fires",
                    tok in vocab,
                )
            }
        }
    }
}
