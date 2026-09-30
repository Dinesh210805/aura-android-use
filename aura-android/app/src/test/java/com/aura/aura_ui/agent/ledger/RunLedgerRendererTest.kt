package com.aura.aura_ui.agent.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLedgerRendererTest {

    private fun ledger(
        planSteps: List<PlanStep> = emptyList(),
        facts: List<String> = emptyList(),
        deadEnds: List<String> = emptyList(),
        recentSteps: List<StepRecord> = emptyList(),
        totalSteps: Int = recentSteps.size,
        failedSteps: Int = recentSteps.count { !it.ok },
        directive: String? = null,
    ) = RunLedger(
        runId = "123-abc",
        goal = "add iphone to cart on amazon",
        provider = "GROQ",
        modelId = "llama-4-scout",
        startedAtMs = 1_000L,
        planVersion = if (planSteps.isEmpty()) 0 else 1,
        planSteps = planSteps,
        directive = directive,
        directiveAtMs = directive?.let { 2_000L },
        facts = facts,
        deadEnds = deadEnds,
        recentSteps = recentSteps,
        totalSteps = totalSteps,
        failedSteps = failedSteps,
    )

    @Test fun `block starts with the marker and carries the goal`() {
        val out = RunLedgerRenderer.render(ledger())
        assertTrue(out.startsWith(RunLedgerRenderer.MARKER))
        assertTrue(out.contains("add iphone to cart on amazon"))
    }

    @Test fun `plan checklist renders statuses and progress`() {
        val out = RunLedgerRenderer.render(
            ledger(
                planSteps = listOf(
                    PlanStep("open amazon", PlanStepStatus.DONE),
                    PlanStep("search iphone", PlanStepStatus.IN_PROGRESS),
                    PlanStep("add to cart", PlanStepStatus.PENDING),
                    PlanStep("old detour", PlanStepStatus.SKIPPED),
                ),
            ),
        )
        assertTrue(out.contains("[x] open amazon"))
        assertTrue(out.contains("[>] search iphone"))
        assertTrue(out.contains("[ ] add to cart"))
        assertTrue(out.contains("[-] old detour"))
        assertTrue("progress summary expected", out.contains("1/4 done"))
    }

    @Test fun `no plan yet nudges set_plan`() {
        val out = RunLedgerRenderer.render(ledger())
        assertTrue(out.contains("set_plan"))
    }

    @Test fun `recent steps digest shows outcome and no-effect writes`() {
        val out = RunLedgerRenderer.render(
            ledger(
                recentSteps = listOf(
                    StepRecord("tap", "Compose", ok = true, screenChanged = true),
                    StepRecord("press_enter", null, ok = true, screenChanged = false),
                    StepRecord("swipe", "up", ok = false, screenChanged = null),
                ),
            ),
        )
        assertTrue(out.contains("✓ tap \"Compose\""))
        assertTrue("silent no-effect writes must be visible", out.contains("no effect"))
        assertTrue(out.contains("✗ swipe"))
    }

    @Test fun `facts and dead ends ride inside the untrusted fence`() {
        val out = RunLedgerRenderer.render(
            ledger(
                facts = listOf("price is 134900"),
                deadEnds = listOf("search bar leads nowhere"),
            ),
        )
        val fenceStart = out.indexOf(RunLedgerRenderer.FENCE_BEGIN)
        val fenceEnd = out.indexOf(RunLedgerRenderer.FENCE_END)
        assertTrue(fenceStart >= 0 && fenceEnd > fenceStart)
        val fenced = out.substring(fenceStart, fenceEnd)
        assertTrue(fenced.contains("price is 134900"))
        assertTrue(fenced.contains("search bar leads nowhere"))
    }

    @Test fun `no fence when there is nothing screen-derived to show`() {
        val out = RunLedgerRenderer.render(ledger())
        assertTrue(!out.contains(RunLedgerRenderer.FENCE_BEGIN))
    }

    @Test fun `newlines inside items are collapsed`() {
        val out = RunLedgerRenderer.render(
            ledger(facts = listOf("line one\nline two")),
        )
        assertTrue(out.contains("line one line two"))
    }

    // ── Voice steering: the directive must be TRUSTED and UNTRIMMABLE ──

    @Test fun `a directive renders above the untrusted fence, not inside it`() {
        val out = RunLedgerRenderer.render(
            ledger(directive = "stop searching for food A, search for food B", facts = listOf("cart is empty")),
        )
        val directiveAt = out.indexOf("search for food B")
        val fenceAt = out.indexOf(RunLedgerRenderer.FENCE_BEGIN)
        assertTrue("directive must be present", directiveAt >= 0)
        assertTrue("fence must be present for this fixture", fenceAt >= 0)
        assertTrue(
            "a spoken instruction must NOT sit inside the screen-derived fence — that fence " +
                "tells the model the content may be stale or wrong",
            directiveAt < fenceAt,
        )
    }

    @Test fun `the directive is labelled as overriding the goal`() {
        val out = RunLedgerRenderer.render(ledger(directive = "search for food B"))
        assertTrue(out.contains(RunLedgerRenderer.DIRECTIVE_LABEL))
    }

    @Test fun `no directive line at all when the user has not steered`() {
        val out = RunLedgerRenderer.render(ledger())
        assertTrue(!out.contains(RunLedgerRenderer.DIRECTIVE_LABEL))
    }

    @Test fun `the directive survives a render that has to trim facts and steps away`() {
        val bloated = ledger(
            directive = "forget food A — find food B instead",
            planSteps = (1..5).map { PlanStep("step $it") },
            facts = (1..RunLedgerCaps.MAX_FACTS).map { "fact $it " + "y".repeat(100) },
            deadEnds = (1..RunLedgerCaps.MAX_DEAD_ENDS).map { "dead end $it " + "z".repeat(60) },
            recentSteps = (1..RunLedgerCaps.MAX_RECENT_STEPS).map {
                StepRecord("tap", "button-$it-" + "x".repeat(30), ok = true, screenChanged = true)
            },
        )
        val out = RunLedgerRenderer.render(bloated)
        assertTrue(
            "the user's live instruction must never be the thing trimmed to fit the cap",
            out.contains("find food B instead"),
        )
        assertEquals("trimming still happened", false, out.contains("fact 1 "))
    }

    @Test fun `research notes ride their own fence before the footer and are never trimmed`() {
        val out = RunLedgerRenderer.render(
            ledger(recentSteps = listOf(StepRecord("tap", "Buy", ok = true, screenChanged = true)))
                .copy(research = ResearchNote("faq.whatsapp.com", "Tap the chat, then Send.")),
        )
        val begin = out.indexOf(RunLedgerRenderer.RESEARCH_BEGIN)
        assertTrue(begin > out.indexOf("Recent steps"))
        assertTrue(out.contains("from faq.whatsapp.com"))
        assertTrue(out.indexOf("Tap the chat, then Send.") in begin until out.indexOf(RunLedgerRenderer.RESEARCH_END))
        assertTrue(out.endsWith("=== END RUN LEDGER ==="))
    }

    @Test fun `output respects the hard size cap by trimming oldest recent steps first`() {
        val bloated = ledger(
            planSteps = (1..5).map { PlanStep("step $it") },
            facts = (1..RunLedgerCaps.MAX_FACTS).map { "fact $it " + "y".repeat(100) },
            deadEnds = (1..RunLedgerCaps.MAX_DEAD_ENDS).map { "dead end $it " + "z".repeat(60) },
            recentSteps = (1..RunLedgerCaps.MAX_RECENT_STEPS).map {
                StepRecord("tap", "button-$it-" + "x".repeat(30), ok = true, screenChanged = true)
            },
        )
        val out = RunLedgerRenderer.render(bloated)
        assertTrue("cap exceeded: ${out.length}", out.length <= RunLedgerRenderer.MAX_RENDER_CHARS)
        assertTrue("newest step must survive trimming", out.contains("button-${RunLedgerCaps.MAX_RECENT_STEPS}-"))
        assertEquals("oldest step is trimmed first", false, out.contains("button-1-"))
    }
}
