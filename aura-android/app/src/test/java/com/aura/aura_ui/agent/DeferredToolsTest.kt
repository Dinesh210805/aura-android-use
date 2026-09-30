package com.aura.aura_ui.agent

import ai.koog.agents.core.tools.ToolDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredToolsTest {

    private val registry = listOf("tap", "browser_act", "read_screen", "find_files", "browser_open", "browser_read", "web_search", "load_tools")

    private fun sentNames(d: DeferredTools, names: List<String> = registry) =
        d.sendable(names.map { ToolDescriptor(it, "d") }).map { it.name }

    @Test fun `only core tools are sent until the model loads more`() {
        val d = DeferredTools(registry)
        assertEquals(listOf("read_screen", "tap", "web_search", "load_tools"), sentNames(d))
        assertEquals(listOf("browser_act", "find_files", "browser_open", "browser_read"), d.onDemand())
    }

    @Test fun `without a Tavily key the browser research pair is sent up front`() {
        val unkeyed = registry - "web_search"
        val sent = sentNames(DeferredTools(unkeyed), unkeyed)
        assertTrue("browser_open" in sent && "browser_read" in sent)
        assertFalse("browser_act" in sent)
    }

    @Test fun `loaded tools append in load order so the sent prefix never changes`() {
        val d = DeferredTools(registry)
        val before = sentNames(d)
        assertEquals(listOf("find_files"), d.load(listOf("find_files", "tap", "no_such_tool")))
        d.load(listOf("browser_act"))
        assertEquals(before + listOf("find_files", "browser_act"), sentNames(d))
        assertFalse("browser_act" in d.onDemand())
    }

    @Test fun `menu lists deferred tools with hints and keeps the anti-invention anchor`() {
        val out = ToolInventorySection.render(listOf("tap", "read_screen", "find_files"), mapOf("find_files" to "Files"))
        assertTrue(out.contains("- find_files: Files"))
        assertTrue(out.contains("Callable now: read_screen, tap."))
        assertTrue(out.contains("never invent or guess a tool name"))
    }

    @Test fun `hint prefers the catalogue copy, else the first sentence`() {
        assertEquals("Alarms, timers, calls, SMS, calendar, share, navigation", ToolInventorySection.hint("system_intent", "x"))
        assertEquals("Look up a fact", ToolInventorySection.hint("recall_memory", "Look up a fact. Then more."))
    }
}
