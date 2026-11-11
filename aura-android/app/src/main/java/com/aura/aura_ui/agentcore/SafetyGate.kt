package com.aura.aura_ui.agentcore

/**
 * The deterministic safety layer between the model and every tool: decides allow, ask or deny,
 * fences untrusted text, and redacts secrets.
 *
 * - Contract: pure Kotlin and side-effect free except [TurnTaint]. Nothing here calls a model, so
 *   no prompt, screen or web page can talk it into a different answer.
 * - Fails: closed. A tool the hub cannot classify reaches this file as [ToolRisk.COMMIT].
 * - Change together: `README.md` (the safety rules); `SafetyGateTest`. The phone
 *   server's `SensitivePolicy` and `ActionGuard` (module `:mcp-server`) run after this gate and
 *   are the second layer, not a replacement.
 */

/** One tool call as the gate sees it. */
data class ToolCallRequest(
    val tool: String,
    val risk: ToolRisk,
    val origin: ToolOrigin,
    /** The call's arguments rendered as text, for secret screening. */
    val argsText: String,
    /** True when the arguments travel to a host the user did not choose: web search, a URL, a connector. */
    val egress: Boolean = false,
    /** Visible label of the element a gesture targets, when the caller knows it. */
    val targetLabel: String? = null,
)

sealed interface Verdict {
    data object Allow : Verdict

    /**
     * Run only if the user says yes to [question]. Silence is a no.
     * [unlocksEgress] marks the once-per-turn egress approval, so the hub can remember the yes.
     */
    data class Ask(val question: String, val unlocksEgress: Boolean = false) : Verdict

    /** Never run. [modelMessage] is returned to the model as the tool's error result. */
    data class Deny(val modelMessage: String) : Verdict
}

/** User-controlled strictness. Defaults are the strict ones. */
data class SafetySettings(
    val confirmCommits: Boolean = true,
    val confirmConnectorWrites: Boolean = true,
    val confirmEgressAfterPrivateRead: Boolean = true,
)

/**
 * Remembers what has entered the context this turn, and what the user has already allowed.
 *
 * - Why: an agent that has read content somebody else wrote, holds private data and can send data
 *   out is exploitable by whoever wrote that content. Every private origin on a phone (a screen, a
 *   notification, a file) is also written by somebody else, so one flag covers both conditions:
 *   once a turn holds private content, the first call that sends data out needs the user.
 * - Contract: thread-safe; one instance per turn, seeded with what the thread already holds,
 *   because untrusted text read three turns ago is still in the context. The user's approval is
 *   per turn and is never stored.
 */
class TurnTaint(initial: Set<Origin> = emptySet()) {
    private val seen = initial.filterNot { it.trusted }.toMutableSet()
    private var egressAllowed = false

    @Synchronized
    fun mark(origin: Origin) {
        if (!origin.trusted) seen += origin
    }

    @Synchronized
    fun allowEgress() {
        egressAllowed = true
    }

    @get:Synchronized
    val origins: Set<Origin> get() = seen.toSet()

    @get:Synchronized
    val tainted: Boolean get() = seen.isNotEmpty()

    /** True when the turn has read something private and egress has not been approved yet. */
    @get:Synchronized
    val egressNeedsUser: Boolean get() = !egressAllowed && seen.any { it in PRIVATE_ORIGINS }

    private companion object {
        /**
         * [Origin.WEB] is absent because a public page is untrusted but not private.
         * [Origin.MEMORY] is absent because saved memory is in every thread: counting it would ask
         * on every web search, and a prompt shown every time stops being read. Its exposure is
         * bounded instead by `MemoryPolicy`, which refuses secrets and caps what can be saved.
         * [Origin.HISTORY] is present because past conversations are unbounded and unscreened.
         */
        val PRIVATE_ORIGINS = setOf(Origin.SCREEN, Origin.NOTIFICATION, Origin.CONNECTOR, Origin.FILE, Origin.HISTORY)
    }
}

object SafetyGate {

    /** First matching rule wins; the order is the policy. */
    fun evaluate(
        call: ToolCallRequest,
        mode: RunMode,
        taint: TurnTaint,
        settings: SafetySettings = SafetySettings(),
    ): Verdict {
        if (call.risk == ToolRisk.FORBIDDEN) {
            return Verdict.Deny("policy_blocked: ${call.tool} is never allowed. Do not look for another way.")
        }
        if (mode == RunMode.BACKGROUND && call.risk != ToolRisk.READ) {
            return Verdict.Deny(
                "policy_blocked: ${call.tool} changes something and nobody is watching. " +
                    "Tell the user what you would do and let them start it.",
            )
        }
        val sendsOut = call.egress || call.risk == ToolRisk.COMMIT || call.origin == ToolOrigin.CONNECTOR
        if (sendsOut && SecretPatterns.containsSecret(call.argsText)) {
            return Verdict.Deny("policy_blocked: the arguments contain a password, key, card number or code.")
        }

        val risk = effectiveRisk(call)
        if (risk == ToolRisk.COMMIT && settings.confirmCommits) {
            return Verdict.Ask(commitQuestion(call))
        }
        val connectorWrite = call.origin == ToolOrigin.CONNECTOR && risk != ToolRisk.READ
        if (connectorWrite && (settings.confirmConnectorWrites || taint.tainted)) {
            return Verdict.Ask("Allow ${call.tool}? It changes something outside this phone.")
        }
        if (call.egress && taint.egressNeedsUser) {
            if (mode == RunMode.BACKGROUND) {
                return Verdict.Deny("policy_blocked: private content was read this turn, so nothing may be sent out.")
            }
            if (settings.confirmEgressAfterPrivateRead) {
                return Verdict.Ask(
                    "AURA read private content this turn and now wants to use ${call.tool}, " +
                        "which sends text off this phone. Allow for this task?",
                    unlocksEgress = true,
                )
            }
        }
        return Verdict.Allow
    }

    /** A gesture on a button that commits the user is a commit, whatever the tool is called. */
    internal fun effectiveRisk(call: ToolCallRequest): ToolRisk =
        if (call.risk == ToolRisk.ACT && CommitLabels.isCommit(call.targetLabel)) ToolRisk.COMMIT else call.risk

    private fun commitQuestion(call: ToolCallRequest): String =
        call.targetLabel?.let { "Allow AURA to press \"$it\"? This can't be undone." }
            ?: "Allow ${call.tool}? ${SecretPatterns.redact(call.argsText).take(MAX_ARGS_IN_QUESTION)}"

    private const val MAX_ARGS_IN_QUESTION = 200
}

/**
 * Button labels that commit the user (send, pay, delete, post).
 *
 * ponytail: English keywords only. A label in another language or an icon-only button passes as
 * [ToolRisk.ACT]; the system prompt's confirm-before-committing rule is the cover for those.
 * Upgrade path: per-locale lists keyed on the device language, plus the node's resource id.
 */
object CommitLabels {
    private val words = setOf(
        "send", "pay", "buy", "order", "purchase", "checkout", "confirm", "submit", "post", "publish",
        "delete", "remove", "erase", "uninstall", "book", "transfer", "donate", "subscribe", "unsubscribe",
        "merge", "reply", "share",
    )

    fun isCommit(label: String?): Boolean {
        val text = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: return false
        return text.split(Regex("[^a-z]+")).any { it in words }
    }
}

/** Shapes of secrets that must never leave the device through a tool call or a log. */
object SecretPatterns {
    /** 15 or 16 digits, optionally grouped. Checked with Luhn so ids and timestamps pass. */
    private val card = Regex("""\b\d(?:[ -]?\d){14,15}\b""")

    private val others = listOf(
        Regex("""\b(?:sk|pk|rk|gsk|ghp|gho|ghu|ghs|github_pat|xox[abprs])[-_][A-Za-z0-9_\-]{16,}"""),
        Regex("""\bAIza[0-9A-Za-z_\-]{30,}"""),
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._\-]{20,}"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        // "password is hunter2", "pin: 4321". The separator is required so "pin the chat" passes.
        Regex("""(?i)\b(?:password|passcode|pin|cvv)\b\s*(?:is|:|=)\s*\S{3,}"""),
        // "otp 123456", "verification code is 4821".
        Regex("""(?i)\b(?:otp|verification code|security code|one[- ]time code)\b\D{0,12}\d{4,8}\b"""),
    )

    fun containsSecret(text: String): Boolean =
        others.any { it.containsMatchIn(text) } || card.findAll(text).any { luhn(it.value) }

    /** [text] with every secret replaced by a marker. For logs and exported telemetry. */
    fun redact(text: String): String {
        val withoutCards = card.replace(text) { if (luhn(it.value)) REDACTED else it.value }
        return others.fold(withoutCards) { acc, pattern -> pattern.replace(acc, REDACTED) }
    }

    private fun luhn(raw: String): Boolean {
        val digits = raw.filter(Char::isDigit).map { it - '0' }
        val sum = digits.reversed().mapIndexed { index, digit ->
            if (index % 2 == 1) (digit * 2).let { if (it > 9) it - 9 else it } else digit
        }.sum()
        return digits.size >= 15 && sum % 10 == 0
    }

    private const val REDACTED = "[redacted]"
}

/**
 * Wraps untrusted text so the model can tell data from instructions.
 *
 * - Contract: the returned block always closes. A fence tag inside [text] is defused, so content
 *   cannot end its own fence and continue as if it were the user.
 * - Why the user channel: fenced text is appended to user or tool messages, never to the system
 *   prompt, which would give it durable system authority.
 */
object Fence {
    private val tag = Regex("(?i)<(/?)\\s*untrusted")

    fun wrap(origin: Origin, text: String): String {
        require(!origin.trusted) { "Only untrusted text is fenced; $origin is trusted." }
        val safe = tag.replace(text) { "<${it.groupValues[1]}_untrusted" }
        return "<untrusted source=\"${origin.name.lowercase()}\">\n$safe\n</untrusted>"
    }
}
