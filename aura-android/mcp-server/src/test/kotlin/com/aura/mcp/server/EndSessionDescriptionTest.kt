package com.aura.mcp.server

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The success gate must be legible to the model that will hit it.
 *
 * [CompletionEvidence.GATED_GOAL_TYPES] refuses `outcome="success"` without a fresh look. A refusal
 * the model was never warned about costs a turn and teaches it nothing, so the warning is part of
 * the gate — and this is the test that keeps them together when one of them changes.
 *
 * It lives here, and no longer in `:app`'s `AuraAgentPromptTest`, because the doctrine used to
 * carry a full paraphrase of these mechanics on **every** request for a rule that only matters
 * while choosing this one tool. The one-home rule sent it here; this assertion followed it.
 */
class EndSessionDescriptionTest {

    @BeforeTest
    fun buildServerOnce() {
        if (RegisteredToolNames.descriptions.isEmpty()) buildTestServer()
    }

    private val description: String
        get() = RegisteredToolNames.descriptions.getValue("end_session").first

    /** Line wrapping in a `trimIndent` block is formatting, not meaning — match on the words. */
    private val unwrapped: String get() = description.replace(Regex("""\s+"""), " ")

    @Test
    fun `end_session names every goal type its own gate enforces`() {
        val unnamed = CompletionEvidence.GATED_GOAL_TYPES.filterNot { description.contains(it) }
        assertTrue(
            unnamed.isEmpty(),
            "end_session's description does not warn about $unnamed, which its gate refuses " +
                "without a fresh look. Add them here — this is the one home for that fact.",
        )
    }

    /**
     * Phrases, not keywords. A `contains("after")` would pass on almost any prose, which is how a
     * single-homed fact rots without a build break — the failure `CLAUDE.md` calls out as tests
     * that look like they check the code and do not.
     */
    @Test
    fun `end_session says what the gate wants and that failure is always accepted`() {
        listOf(
            // the gate's actual verdict, not the word "verify"
            "refused unless you looked at the screen after",
            // the escape hatch that keeps an honest failure cheaper than a false success
            "partial and failure are always accepted",
        ).forEach {
            assertTrue(
                unwrapped.contains(it, ignoreCase = true),
                "end_session's description no longer says \"$it\" — this is the one home for it.",
            )
        }
    }
}
