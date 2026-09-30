package com.aura.aura_ui.agent.llm

/**
 * Rescues the answer from a turn where the model wrote its tool call as prose.
 *
 * ### What actually reached the user
 *
 * Four runs in the 2026-08-26 eval suite ended with the model emitting a tool call as *text*
 * instead of a function call. The harness treated that text as the run's closing sentence and
 * spoke it. Verbatim, this is what AURA said out loud:
 *
 * ```
 * task 7   <script>end_session(goal_type='open_app',outcome='success',
 *          reason='Opened the Clock app successfully.')</script>
 * task 9   uniqueItems: ["com.aura.aura_ui.feature.debug"]package: "…"goal_type: "other"…
 * task 10  <prefer_text>The screen shows…</prefer_text><end_session R="…" outcome="success"/>
 * task 4   <custom_instruction>…</custom_instruction><call>default_api:tap{som_id:110}</call>
 * ```
 *
 * Three of those four scored **PASS**, because every truth check in the suite inspects the
 * *screen* and none inspects the utterance. The world changed correctly and the assistant
 * sounded broken, and the eval could not see it.
 *
 * ### Why salvage and not just strip
 *
 * The obvious fix — delete anything that looks like markup — throws away the answer, because in
 * three of the four cases the answer is *inside* the debris: `reason='Opened the Clock app
 * successfully.'` is exactly the sentence that should have been spoken. Stripping alone converts
 * a garbled success into a silent one, which is not much better.
 *
 * So this reads the debris the way the model meant it: pull out the argument that was destined
 * for the user, drop the machinery, and prefer whichever is actually intelligible. Task 4 is the
 * honest failure case — nothing in it was ever an answer, so it yields nothing and the caller's
 * "no final answer" path takes over.
 *
 * ### Not a substitute for the real fix
 *
 * This is the last line of defence, at the seam where text becomes speech. The causes upstream
 * — a `MALFORMED_FUNCTION_CALL` finish reason that ends the run with no repair attempt, and a
 * tool surface large enough to provoke this in the first place — are separate work. This exists
 * because that work cannot make the guarantee this makes: *no tool-call syntax is ever spoken*.
 */
internal object ToolCallDebris {

    /**
     * Markers that mean the text carries tool-call machinery.
     *
     * Kept as literal markers rather than one clever pattern: each entry is a shape observed on
     * device, and a reader should be able to match every one back to a run.
     */
    private val MARKERS = listOf(
        "<script", "<call", "<end_session", "<prefer_text", "<custom_instruction",
        "default_api:", "goal_type:", "goal_type=", "uniqueItems:", "outcome=\"", "outcome='",
    )

    /** Blocks that are pure machinery — content and all. */
    private val MACHINERY_BLOCKS = Regex(
        "(?is)<(script|call|custom_instruction|tool_code|function_call)\\b[^>]*>.*?</\\1>",
    )

    /** A self-closing or unpaired pseudo-tag: `<end_session … />`, `<call …>`, a stray `</call>`. */
    private val PSEUDO_TAG = Regex("(?is)</?(?:script|call|end_session|prefer_text|custom_instruction|tool_code|function_call)\\b[^>]*/?>")

    /** `key: "value"` / `key='value'` debris with no sentence around it (task 9's shape). */
    private val KV_DEBRIS = Regex("""(?i)\b(?:uniqueItems|package|goal_type|outcome|status|index|som_id|note|reason)\s*[:=]\s*(?:"[^"]*"|'[^']*'|\[[^\]]*\]|[A-Za-z0-9_.]+)""")

    /**
     * A bare `mark_step{…}` / `end_session(outcome=…)` call written as prose.
     *
     * Both arms are deliberately narrow. An earlier version matched any identifier followed by a
     * bracket, and its own test caught it eating "AURA (`com.aura.aura_ui.feature.debug`)" out of
     * a correct answer — it would equally have eaten "the Burj Khalifa (828 m)". So: the brace
     * form, which prose does not use, and the paren form only when it contains a `name=` or
     * `name:` argument.
     */
    private val BARE_CALL = Regex(
        """(?is)\b[a-z_][a-z0-9_]*\{[^}]*\}|\b[a-z_][a-z0-9_]*\(\s*[a-z_][a-z0-9_]*\s*[=:][^)]*\)""",
    )

    /** `<prefer_text>…</prefer_text>` — the model's own "say this to the user". */
    private val PREFER_TEXT = Regex("(?is)<prefer_text>(.*?)</prefer_text>")

    /** The user-facing argument of an end_session written as text, in the spellings observed. */
    private val REASON_ARG = Regex("""(?is)\b(?:reason|R)\s*[:=]\s*("([^"]{4,})"|'([^']{4,})')""")

    /** True when [text] carries tool-call syntax that must never be spoken. */
    fun hasDebris(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return MARKERS.any { text.contains(it, ignoreCase = true) }
    }

    /**
     * The sentence the model meant for the user, or "" when the turn contained no answer at all.
     *
     * Returns the input untouched when there is no debris, so this is safe to call on every
     * reply and costs one `contains` scan on the overwhelmingly common clean path.
     */
    fun clean(text: String?): String {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || !hasDebris(raw)) return raw

        // Captured BEFORE stripping: in three of the four observed runs the answer lives inside
        // the machinery, so removing the machinery first would throw away what we came for.
        val preferred = PREFER_TEXT.find(raw)?.groupValues?.get(1)?.trim()
        val reason = REASON_ARG.find(raw)?.let { m ->
            m.groupValues.drop(2).firstOrNull { it.isNotBlank() }?.trim()
        }

        val stripped = raw
            .let { MACHINERY_BLOCKS.replace(it, " ") }
            .let { PSEUDO_TAG.replace(it, " ") }
            .let { KV_DEBRIS.replace(it, " ") }
            .let { BARE_CALL.replace(it, " ") }
            .replace(Regex("""[\s*·•]+"""), " ")
            .trim()

        // Order of trust: what the model explicitly marked as user-facing, then the residue when
        // it reads like a sentence, then the reason it passed to its imaginary end_session.
        // `preferred` beats the residue because task 10 has both and the residue there is the
        // duplicate half.
        preferred?.takeIf { it.isNotBlank() }?.let { return it }
        if (looksLikeSentence(stripped)) return stripped
        reason?.takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    /**
     * Whether what survived stripping is something a person would recognise as an answer.
     *
     * The bar is deliberately low but not absent: a couple of orphaned words ("success", "done")
     * are the residue of machinery rather than a reply, and speaking them is the same failure in
     * a smaller font.
     */
    private fun looksLikeSentence(text: String): Boolean {
        if (text.length < 12) return false
        if (hasDebris(text)) return false
        return text.count { it == ' ' } >= 2
    }
}
