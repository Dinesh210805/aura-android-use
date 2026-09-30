package com.aura.aura_ui.agent.proof

import android.content.Context
import android.util.Log
import com.aura.aura_ui.agent.conversation.BrainChat
import com.aura.aura_ui.agent.ledger.RunLedger
import com.aura.aura_ui.agent.ledger.RunOutcome
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Asks the user's own brain model whether the run really did what was asked.
 *
 * ### Why a model and not more rules
 *
 * Every other gate in this codebase is deterministic, and each covers exactly one failure:
 * `CompletionEvidence` catches an unlooked-at send, [CompletionGate] catches an under-counted
 * harvest. Neither notices an answer that quietly invents a detail, and writing a rule per task
 * type does not scale — a phone assistant's goals are open-ended. One judge prompt covers them
 * all, which is also how agent evaluation is done where there is no device state to check.
 *
 * ### What keeps it cheap and safe
 *
 * It runs on the brain the user already configured ([BrainChat]) with low thinking, sends screen
 * TEXT rather than images, and is called at most [MAX_CALLS_PER_RUN] times per run. Every failure
 * path returns [RunOutcome.UNVERIFIED] — an unreachable judge must never be able to fail a run
 * that worked, so it degrades to silence rather than to a verdict.
 *
 * ### Shadow mode
 *
 * While [ENFORCE] is false the verdict is recorded and spoken but cannot refuse `end_session`.
 * It flips once `scripts/bench/judge_eval.py` shows the judge agreeing with the hand-scored eval
 * runs; trusting a model to block completion before measuring it against human verdicts would be
 * exactly the unverified-claim problem this whole feature exists to fix.
 */
class CompletionJudge(
    private val context: Context,
    private val httpClient: OkHttpClient = BrainChat.defaultClient(READ_TIMEOUT_S),
) {

    private var calls = 0

    /** True while the judge still has a call left in this run. */
    fun canJudge(): Boolean = calls < MAX_CALLS_PER_RUN

    /**
     * The harness's verdict on this run, or [RunOutcome.UNVERIFIED] when the judge could not be
     * asked. Never throws.
     */
    suspend fun judge(
        ledger: RunLedger,
        claimed: String?,
        answer: String,
        observed: ObservedText,
    ): RunOutcome {
        val unverified = RunOutcome(
            claimed = claimed,
            verdict = RunOutcome.UNVERIFIED,
            proven = ledger.findings.size,
            target = ledger.targetCount,
        )
        if (!canJudge()) return unverified
        calls++
        val brain = BrainChat.resolve(context) ?: return unverified
        val template = runCatching {
            context.assets.open(PROMPT_ASSET).bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.w(TAG, "judge prompt asset missing: $it")
            return unverified
        }
        val prompt = JudgePrompt.fill(template, ledger, claimed, answer, observed)
        val reply = BrainChat.complete(brain, prompt, TEMPERATURE, httpClient) ?: return unverified
        val parsed = JudgePrompt.parse(reply) ?: run {
            Log.w(TAG, "judge reply unparseable: ${reply.take(200)}")
            return unverified
        }
        Log.i(TAG, "judge verdict=${parsed.verdict} why=${parsed.why}")
        return parsed.copy(
            claimed = claimed,
            proven = ledger.findings.size,
            target = ledger.targetCount,
        )
    }

    companion object {
        private const val TAG = "CompletionJudge"

        /** Off until the judge has been measured against the hand-scored runs. */
        const val ENFORCE = false

        /** Shared with `scripts/bench/judge_eval.py`, so app and offline checker judge alike. */
        const val PROMPT_ASSET = "judge/completion_judge.md"

        /** One verdict per end attempt, and a run may attempt twice (gate refusal then retry). */
        const val MAX_CALLS_PER_RUN = 2

        /** Longer than the Live lane's 15 s: nobody is waiting mid-sentence for this one. */
        const val READ_TIMEOUT_S = 30L

        /** A verdict is a judgement, not a composition — near-deterministic is what we want. */
        const val TEMPERATURE = 0.0
    }
}

/**
 * Prompt filling and verdict parsing, kept pure so both are unit-tested without a device, a
 * network or an asset manager. The template itself lives in `assets/judge/completion_judge.md`
 * so the app and `scripts/bench/judge_eval.py` read the same words and cannot drift apart.
 */
object JudgePrompt {

    /** Screen text is the biggest section and the least dense — capped hard. */
    const val MAX_SCREEN_CHARS = 4000
    const val MAX_ANSWER_CHARS = 1500
    const val SCREEN_CHUNKS = 2

    fun fill(
        template: String,
        ledger: RunLedger,
        claimed: String?,
        answer: String,
        observed: ObservedText,
    ): String {
        val findings = if (ledger.findings.isEmpty()) {
            "(none recorded)"
        } else {
            ledger.findings.joinToString("\n") { "- ${it.item} | quoted: \"${it.quote}\"" }
        }
        val steps = ledger.recentSteps.joinToString("\n") { step ->
            "- ${if (step.ok) "ok" else "FAILED"} ${step.tool}${step.label?.let { " \"$it\"" } ?: ""}"
        }.ifBlank { "(no steps recorded)" }
        return template
            .replace("{{GOAL}}", ledger.goal)
            .replace("{{CLAIMED}}", claimed ?: "never said (the run ended without end_session)")
            .replace("{{ANSWER}}", answer.take(MAX_ANSWER_CHARS).ifBlank { "(said nothing)" })
            .replace("{{FINDING_COUNT}}", ledger.findings.size.toString())
            .replace("{{FINDINGS}}", findings)
            .replace("{{STEPS}}", steps)
            // Border-stripped for the same reason as the step checker: the head of a raw read is grid.
            .replace(
                "{{SCREENS}}",
                StepCheckPrompt.screenText(observed.recentText(SCREEN_CHUNKS, Int.MAX_VALUE))
                    .take(MAX_SCREEN_CHARS).ifBlank { "(nothing read)" },
            )
    }

    /**
     * The verdict in a model reply, or null when there is none to be had.
     *
     * Tolerant of what small models actually send back — a ```json fence, a sentence before the
     * object — because the alternative is throwing away a correct verdict over punctuation. An
     * unknown verdict word is null rather than a guess: mapping "mostly" to something would be
     * inventing the judgement.
     */
    fun parse(reply: String): RunOutcome? {
        val json = extractObject(reply) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val verdict = obj.optString("verdict").lowercase().trim().takeIf {
            it in setOf(RunOutcome.SUCCESS, RunOutcome.PARTIAL, RunOutcome.FAIL)
        } ?: return null
        val why = obj.optString("why").trim().takeIf { it.isNotEmpty() }
        val missing = obj.optString("missing").trim().takeIf { it.isNotEmpty() }
        return RunOutcome(
            verdict = verdict,
            why = listOfNotNull(why, missing?.takeIf { verdict != RunOutcome.SUCCESS }?.let { "Still to do: $it" })
                .joinToString(" ")
                .takeIf { it.isNotBlank() },
        )
    }

    /** The outermost `{...}` in a reply, fence or prose notwithstanding. */
    private fun extractObject(reply: String): String? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        return if (start >= 0 && end > start) reply.substring(start, end + 1) else null
    }
}
