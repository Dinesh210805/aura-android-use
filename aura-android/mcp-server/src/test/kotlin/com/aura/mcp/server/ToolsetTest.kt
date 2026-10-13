package com.aura.mcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolsetTest {

    private val subsets: List<Set<Toolset>> = listOf(
        setOf(Toolset.CORE),
        setOf(Toolset.CORE, Toolset.ASSISTANT),
        setOf(Toolset.CORE, Toolset.BROWSER),
        Toolset.ALL,
    )

    private val all: Set<String> = buildTestServer(Toolset.ALL).tools.keys

    /** A tool registered under one group but classified under another fails here. */
    @Test
    fun `a server built with some groups registers exactly the tools of those groups`() {
        for (subset in subsets) {
            assertEquals(
                all.filter { Toolset.of(it) in subset }.toSet(),
                buildTestServer(subset).tools.keys,
                "toolsets=$subset",
            )
        }
    }

    @Test
    fun `every group has tools`() {
        Toolset.entries.forEach { group -> assertTrue(all.any { Toolset.of(it) == group }, "$group is empty") }
    }

    @Test
    fun `the handshake names only registered tools and always keeps the safety rules`() {
        val toolName = Regex("[a-z]+(?:_[a-z]+)+")
        for (subset in subsets) {
            val kit = AuraInstructions.survivalKit(subset)
            val registered = buildTestServer(subset).tools.keys
            val unregistered = toolName.findAll(kit).map { it.value }.filter { it in all && it !in registered }.toSet()
            assertTrue(unregistered.isEmpty(), "toolsets=$subset: kit names $unregistered")
            listOf("SAFETY", "STOP", "policy_blocked", "read_screen").forEach {
                assertTrue(it in kit, "toolsets=$subset: kit lost '$it'")
            }
        }
        assertEquals(AuraInstructions.survivalKit, AuraInstructions.survivalKit(Toolset.ALL))
    }

    @Test
    fun `stored names parse leniently and always include core`() {
        assertEquals(Toolset.DEFAULT_REMOTE, Toolset.parse(null))
        assertEquals(setOf(Toolset.CORE), Toolset.parse(emptyList()))
        assertEquals(setOf(Toolset.CORE, Toolset.BROWSER), Toolset.parse(listOf("BROWSER", "TELEPORT")))
    }
}
