package com.aura.aura_ui.agent.proof

import android.content.Context
import android.util.Log
import com.aura.aura_ui.agent.conversation.BrainChat
import com.aura.aura_ui.agent.ledger.RunLedger
import com.aura.aura_ui.agent.ledger.StepVerdict
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * The overseer: asks the user's own brain model whether a plan step the agent calls done really
 * happened, judged from the screen the run is looking at now.
 *
 * The end-of-run [CompletionJudge] notices a false claim only after every step has run, when it is
 * too late to redo step 1. This asks at the step, while the agent can still retry it — which is
 * what turns "Spotify never played" from a silent miss into a second attempt.
 *
 * Same brain, low thinking, text only ([ObservedText]'s newest read). Unlike the end judge this
 * one is enforced: a `done` it does not accept is not stored. A check that could not run is not a
 * pass either ([StepVerdict.ran] false) — only a user with no brain configured at all gets their
 * claims through unchecked, because otherwise every step of every run would fail.
 */
class StepChecker(
    private val context: Context,
    private val observed: ObservedText,
    private val httpClient: OkHttpClient = BrainChat.defaultClient(CompletionJudge.READ_TIMEOUT_S),
) {

    suspend fun check(ledger: RunLedger, stepIndex: Int, evidence: String): StepVerdict {
        if (observed.changedSinceRead) {
            return StepVerdict(
                verified = false,
                reason = "your last action's result has not been looked at yet — claim the step " +
                    "from the screen that action produced (call read_screen, or get_media_sessions " +
                    "for playback, if you do not have it)",
                ran = false,
                premature = true,
            )
        }
        val brain = BrainChat.resolve(context)
            ?: return StepVerdict(verified = true, reason = "no model configured to check it", ran = false)
        val template = runCatching {
            context.assets.open(PROMPT_ASSET).bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.w(TAG, "step-check prompt asset missing: $it")
            return couldNotCheck
        }
        val prompt = StepCheckPrompt.fill(template, ledger, stepIndex, evidence, observed)
        val reply = BrainChat.complete(brain, prompt, CompletionJudge.TEMPERATURE, httpClient) ?: return couldNotCheck
        val verdict = StepCheckPrompt.parse(reply) ?: run {
            Log.w(TAG, "step-check reply unparseable: ${reply.take(200)}")
            return couldNotCheck
        }
        Log.i(TAG, "step ${stepIndex + 1}: verified=${verdict.verified} why=${verdict.reason}")
        return verdict
    }

    private val couldNotCheck = StepVerdict(
        verified = false,
        reason = "the check could not run, so this is not confirmed yet — look at the screen and claim it again",
        ran = false,
    )

    companion object {
        private const val TAG = "StepChecker"
        const val PROMPT_ASSET = "judge/step_check.md"
    }
}

/** Prompt filling and verdict parsing for [StepChecker]. Pure, so both are unit-tested. */
object StepCheckPrompt {

    /** A border-stripped Maps screen measured 5.4k chars (from 32.8k); room for a busier one. */
    const val MAX_SCREEN_CHARS = 8000

    /**
     * The screen's words without the grid drawn around them. A `read_screen` result is mostly box
     * borders (`+----|`): its first 5,000 characters were the top rows of the drawing, so a Pause
     * control or a caption lower down never reached the checker. Stripping keeps every label.
     */
    fun screenText(raw: String): String = raw.replace(BORDERS, " ").replace(SPACES, " ").trim()

    private val BORDERS = Regex("[+|]|-{2,}")
    private val SPACES = Regex("\\s+")

    fun fill(template: String, ledger: RunLedger, stepIndex: Int, evidence: String, observed: ObservedText): String {
        val steps = ledger.recentSteps.takeLast(RECENT_STEPS).joinToString("\n") { step ->
            "- ${if (step.ok) "ok" else "FAILED"} ${step.tool}${step.label?.let { " \"$it\"" } ?: ""}"
        }.ifBlank { "(none)" }
        return template
            .replace("{{GOAL}}", ledger.goal)
            // No plan step (index -1): the whole goal is the claim — a single-action run's success.
            .replace("{{STEP}}", ledger.planSteps.getOrNull(stepIndex)?.text ?: "the whole task: ${ledger.goal}")
            .replace("{{EVIDENCE}}", evidence.ifBlank { "(none given)" })
            .replace("{{STEPS}}", steps)
            // Only the newest read: "done" is a claim about the screen now, and an older screen
            // proving it would be the very "it was true a minute ago" this check exists to stop.
            .replace("{{SCREEN}}", screenText(observed.recentText(1, Int.MAX_VALUE)).take(MAX_SCREEN_CHARS).ifBlank { "(nothing read yet)" })
    }

    /** The verdict in a reply, or null when it has none. Tolerates fences and prose, like [JudgePrompt]. */
    fun parse(reply: String): StepVerdict? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { JSONObject(reply.substring(start, end + 1)) }.getOrNull() ?: return null
        val verified = when (obj.optString("verdict").lowercase().trim()) {
            "verified" -> true
            "not_verified" -> false
            else -> return null
        }
        val why = obj.optString("why").trim().ifEmpty { if (verified) "confirmed on screen" else "the screen does not show it" }
        return StepVerdict(verified = verified, reason = why, ran = true)
    }

    private const val RECENT_STEPS = 5
}
