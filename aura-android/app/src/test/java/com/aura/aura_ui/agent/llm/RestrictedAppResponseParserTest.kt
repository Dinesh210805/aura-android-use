package com.aura.aura_ui.agent.llm

import com.aura.aura_ui.data.AppInfo
import com.aura.mcp.bridge.RestrictedAppCategory
import com.aura.mcp.bridge.RestrictedAppSource
import com.aura.mcp.bridge.RestrictedTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RestrictedAppResponseParser — defensive parsing of an LLM's free-text
 * classification response. The model's output is untrusted: it may wrap JSON
 * in a code fence, add prose, invent a package that was never in the request,
 * or return a category the schema doesn't define. Every case here is one of
 * those failure shapes, verifying the parser degrades gracefully instead of
 * crashing or fabricating a suggestion for something never asked about.
 */
class RestrictedAppResponseParserTest {

    private val bank = AppInfo(packageName = "com.bank.app", appName = "MyBank")
    private val game = AppInfo(packageName = "com.fun.game", appName = "Puzzle Game")
    private val chunk = listOf(bank, game)

    @Test fun `parses a clean JSON array`() {
        val raw = """[{"package":"com.bank.app","category":"BANKING","confidence":0.95}]"""
        val result = RestrictedAppResponseParser.parse(raw, chunk, nowMs = 1000L)

        assertEquals(1, result.size)
        val entry = result.single()
        assertEquals("com.bank.app", entry.packageName)
        assertEquals("MyBank", entry.appName)
        assertEquals(RestrictedAppCategory.BANKING, entry.category)
        // BANKING and PAYMENT earn the stronger tier — see the parser's tierFor.
        assertEquals(RestrictedTier.DISABLE_ACCESSIBILITY, entry.tier)
        assertEquals(RestrictedAppSource.LLM_SUGGESTED, entry.source)
        assertEquals(1000L, entry.addedAt)
    }

    @Test fun `strips a json code fence`() {
        val raw = "```json\n[{\"package\":\"com.bank.app\",\"category\":\"BANKING\"}]\n```"
        val result = RestrictedAppResponseParser.parse(raw, chunk, nowMs = 1L)
        assertEquals(1, result.size)
    }

    @Test fun `strips a bare code fence without the json tag`() {
        val raw = "```\n[{\"package\":\"com.bank.app\",\"category\":\"PAYMENT\"}]\n```"
        val result = RestrictedAppResponseParser.parse(raw, chunk, nowMs = 1L)
        assertEquals(RestrictedAppCategory.PAYMENT, result.single().category)
    }

    @Test fun `tolerates surrounding prose`() {
        val raw = "Here you go:\n[{\"package\":\"com.bank.app\",\"category\":\"BANKING\"}]\nHope that helps!"
        val result = RestrictedAppResponseParser.parse(raw, chunk, nowMs = 1L)
        assertEquals(1, result.size)
    }

    @Test fun `empty array yields no suggestions`() {
        val result = RestrictedAppResponseParser.parse("[]", chunk, nowMs = 1L)
        assertTrue(result.isEmpty())
    }

    @Test fun `garbage text with no brackets yields no suggestions, does not crash`() {
        val result = RestrictedAppResponseParser.parse("I cannot help with that.", chunk, nowMs = 1L)
        assertTrue(result.isEmpty())
    }

    @Test fun `malformed json inside brackets yields no suggestions, does not crash`() {
        val result = RestrictedAppResponseParser.parse("[{package: com.bank.app,, ]", chunk, nowMs = 1L)
        assertTrue(result.isEmpty())
    }

    @Test fun `drops a package the model invented that was never in the request`() {
        val raw = """[{"package":"com.not.in.chunk","category":"BANKING"}]"""
        val result = RestrictedAppResponseParser.parse(raw, chunk, nowMs = 1L)
        assertTrue(result.isEmpty())
    }

    @Test fun `drops an unrecognized category, keeps other valid elements`() {
        val raw = """
            [
              {"package":"com.bank.app","category":"BANKING"},
              {"package":"com.fun.game","category":"NONE"}
            ]
        """.trimIndent()
        val result = RestrictedAppResponseParser.parse(raw, chunk, nowMs = 1L)
        assertEquals(1, result.size)
        assertEquals("com.bank.app", result.single().packageName)
    }

    @Test fun `category matching is case-insensitive`() {
        assertEquals(RestrictedAppCategory.BANKING, RestrictedAppResponseParser.parseCategory("banking"))
        assertEquals(RestrictedAppCategory.TRADING_CRYPTO, RestrictedAppResponseParser.parseCategory("Trading_Crypto"))
        assertNull(RestrictedAppResponseParser.parseCategory("other"))
        assertNull(RestrictedAppResponseParser.parseCategory("garbage"))
    }

    @Test fun `extractJsonArray returns null when there is nothing to extract`() {
        assertNull(RestrictedAppResponseParser.extractJsonArray("no brackets here"))
        assertNull(RestrictedAppResponseParser.extractJsonArray("] reversed ["))
    }
}
