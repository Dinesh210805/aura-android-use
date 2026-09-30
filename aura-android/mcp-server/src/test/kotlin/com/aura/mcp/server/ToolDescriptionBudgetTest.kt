package com.aura.mcp.server

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A ratchet on what tool descriptions cost the model on every single request.
 *
 * ### Why this test exists rather than a note in a doc
 *
 * Measured 2026-08-26: tool descriptions cost **8,086 tokens per request** — more than twice the
 * system prompt, and the largest single item in the turn-0 floor.
 *
 * That number is larger than `scripts/tool_desc_budget.py` reports, and the gap is the point.
 * The script reads Kotlin source with a regex, so a schema assembled by a helper is invisible to
 * it — which hid `browserSchema()`, whose three shared properties were being charged to all
 * ELEVEN browser tools. This test measures what registration actually produced, and found that
 * ~600-token blind spot on its first run. Trust this number; use the script for the fast loop. Nobody had ever budgeted them, because no
 * individual edit looks expensive — a tool gains two clarifying sentences, a schema gains a
 * worked example, and the total drifts up forever with every change locally justified.
 *
 * A number in a design doc does not stop that. A failing build does.
 *
 * ### Why the ceiling is per plane and not per tool
 *
 * Tools genuinely differ: `press_back` needs seven tokens and `read_screen` has a grid format,
 * a flag legend and an escalation rule to convey. A per-tool cap would either be too loose to
 * bind or would force real content out of the tools that need it.
 *
 * A plane is the right unit because it is where the trade actually lives — if `browser_act`
 * needs more, `browser_wait` can afford less, and the plane's total is what the request pays.
 *
 * ### How to change a ceiling
 *
 * Downward, freely. Upward, only with the cut that pays for it in the same change: find the
 * duplicate, delete it from its other home, and say which in the commit. The one-home rule in
 * `Doctrine`'s KDoc is the tool for that — most growth here is a rule that already exists in
 * the system prompt being restated at the tool.
 *
 * `scripts/tool_desc_budget.py` reports the same numbers with a per-tool breakdown and
 * `--compare` against a saved baseline, which is the faster loop while editing.
 */
class ToolDescriptionBudgetTest {

    /**
     * Registration is the only place a description's FINAL text exists, so the budget is measured
     * against a real server built with the same inert fakes `ToolNameListsTest` uses. Guarded on
     * emptiness because both tests share the process-global recorder and either may run first.
     */
    @BeforeTest
    fun buildServerOnce() {
        if (RegisteredToolNames.descriptions.isEmpty()) buildTestServer()
    }


    /**
     * Set at the 2026-08-26 measurement, after the first cutting pass, with a little headroom
     * so an ordinary edit is not a build break. These are ratchets: lower them whenever a plane
     * genuinely shrinks.
     */
    private val ceilings: Map<String, Int> = mapOf(
        "perception" to 1_340,
        "browser" to 2_060,
        "gestures" to 860,
        "assistant" to 1_160,
        "apps" to 745,
        "other" to 1_080,
        "session" to 620,
    )

    /** The whole surface, so trading between planes cannot quietly grow the total. */
    private val totalCeiling = 7_800

    // ── measurement ──────────────────────────────────────────────────────────

    private fun tokens(text: String) = text.length / CHARS_PER_TOKEN

    /**
     * Description plus input schema, because both ride the same `tools` field of the same
     * request. Measuring the description alone lets prose move into the schema and read as a cut
     * — which is exactly how `perceive_screen` ended up documenting one parameter twice.
     */
    private fun cost(tool: RegisteredTool): Int =
        tokens(tool.description) + tokens(tool.schemaText)

    @Test
    fun `every plane is within its token ceiling`() {
        val byPlane = registeredTools().groupBy { planeOf(it.name) }
        val overspent = buildList {
            ceilings.forEach { (plane, cap) ->
                val spent = byPlane[plane].orEmpty().sumOf { cost(it) }
                if (spent > cap) add("$plane: $spent tok > $cap")
            }
        }
        assertTrue(
            overspent.isEmpty(),
            "Tool descriptions grew past their per-plane budget:\n  " +
                overspent.joinToString("\n  ") +
                "\n\nEvery request pays this. Before raising a ceiling, find the rule that is " +
                "stated twice — most growth here is doctrine restated at the tool — and delete " +
                "it from its other home. Run scripts/tool_desc_budget.py for the breakdown.",
        )
    }

    @Test
    fun `the whole tool surface is within its ceiling`() {
        val spent = registeredTools().sumOf { cost(it) }
        assertTrue(
            spent <= totalCeiling,
            "Tool descriptions total $spent tok > $totalCeiling on every request. " +
                "Trading between planes must not grow the total.",
        )
    }

    /**
     * A tool nobody can find is a tool that does not exist. This catches the opposite failure
     * from the ceilings above — a cut that goes so far the model cannot tell what the tool is for.
     */
    @Test
    fun `no tool is left without a usable description`() {
        val tooThin = registeredTools().filter { tokens(it.description) < MIN_DESCRIPTION_TOKENS }
        assertTrue(
            tooThin.isEmpty(),
            "These tools no longer say enough to be chosen correctly: " +
                tooThin.joinToString { "${it.name} (${tokens(it.description)} tok)" },
        )
    }

    /**
     * Every exclusion must name a tool that actually exists, or the list is silently protecting
     * nothing — the same drift `RegisteredToolNames` exists to catch for the scope map.
     */
    @Test
    fun `every agent-lane exclusion names a real tool`() {
        val names = registeredTools().map { it.name }.toSet()
        val ghosts = AgentLaneTools.EXCLUDED_FROM_AGENT - names
        assertTrue(ghosts.isEmpty(), "excluded tools that are not registered: $ghosts")
    }

    private companion object {
        const val CHARS_PER_TOKEN = 4

        /** Below this a description cannot carry what the tool does AND when to prefer it. */
        const val MIN_DESCRIPTION_TOKENS = 3
    }
}

/** What the budget needs to know about one registered tool. */
internal data class RegisteredTool(
    val name: String,
    val description: String,
    val schemaText: String,
)

/**
 * Plane assignment, mirroring `scripts/tool_desc_budget.py`. A tool matching nothing lands in
 * "other" and is still budgeted — an unassigned tool must never be an unbudgeted one.
 */
internal fun planeOf(name: String): String = when {
    name.startsWith("browser_") -> "browser"
    name in setOf(
        "perceive_screen", "read_screen", "get_screenshot", "verify_action",
        "wait_for", "get_device_status",
    ) -> "perception"
    name in setOf("launch_app", "open_deeplink", "list_app_deeplinks", "resolve_deeplink") -> "apps"
    name in setOf(
        "system_intent", "read_notifications", "notification_action", "media_control",
        "resolve_contact",
    ) -> "assistant"
    name in setOf("end_session", "get_usage_guide") -> "session"
    name in setOf(
        "tap", "double_tap", "long_press", "swipe", "scroll_to",
        "type_text", "press_enter", "press_back", "press_home",
    ) -> "gestures"
    else -> "other"
}

/**
 * Every tool the server registers, with the description and schema text it was registered with.
 *
 * Read from the registration chokepoint rather than from the source, because a description
 * assembled at registration — an interpolated action list, a generated narrative — is invisible
 * to a source regex and would silently escape the budget.
 */
internal fun registeredTools(): List<RegisteredTool> =
    RegisteredToolNames.descriptions.map { (name, texts) ->
        RegisteredTool(name = name, description = texts.first, schemaText = texts.second)
    }
