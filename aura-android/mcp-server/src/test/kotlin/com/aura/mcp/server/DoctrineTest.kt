package com.aura.mcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoctrineTest {

    @Test
    fun `every section id is unique`() {
        val dupes = Doctrine.ids.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate section ids: $dupes")
    }

    @Test
    fun `ids are lowercase snake case`() {
        Doctrine.ids.forEach { id ->
            assertTrue(id.matches(Regex("[a-z][a-z0-9_]*")), "id '$id' is not lowercase snake_case")
        }
    }

    @Test
    fun `every section is wrapped in its own tag`() {
        Doctrine.blocks.forEach { b ->
            assertTrue(b.text.startsWith("<${b.id}>\n"), "section '${b.id}' does not open with its tag")
            assertTrue(b.text.endsWith("\n</${b.id}>"), "section '${b.id}' does not close with its tag")
        }
    }

    @Test
    fun `the default render is the preamble plus every section in order`() {
        val rendered = Doctrine.render()
        assertTrue(rendered.startsWith(Doctrine.preamble))
        var from = 0
        Doctrine.blocks.forEach { b ->
            val at = rendered.indexOf(b.text, from)
            assertTrue(at >= 0, "section '${b.id}' missing or out of order")
            from = at + b.text.length
        }
    }

    @Test
    fun `selecting sections keeps them and drops the rest, in declaration order`() {
        val first = Doctrine.blocks.first()
        val last = Doctrine.blocks.last()
        val rendered = Doctrine.render(only = linkedSetOf(last.id, first.id, "section_that_never_existed"))
        Doctrine.blocks.forEach { b ->
            assertEquals(b === first || b === last, rendered.contains(b.text), "selection wrong for '${b.id}'")
        }
        assertTrue(rendered.indexOf(first.text) < rendered.indexOf(last.text), "declaration order was not preserved")
    }

    @Test
    fun `selecting nothing still yields the preamble`() {
        assertEquals(Doctrine.preamble, Doctrine.render(only = emptySet()))
    }

    /**
     * Facts that moved to their one home in this rewrite. A copy creeping back here is the drift
     * the one-home rule exists to stop.
     */
    @Test
    fun `facts that live in tool descriptions are not restated here`() {
        val rendered = Doctrine.render()
        listOf("keyboard_not_enabled", "handed_off", "suggestion", "search_query", "web_search")
            .forEach { assertFalse(rendered.contains(it, ignoreCase = true), "doctrine restates '$it'") }
    }
}
