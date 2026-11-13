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
    /** The app in front ("WhatsApp"), for the verification card's title. Null when unknown. */
    val appLabel: String? = null,
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

/**
 * How often AURA stops to check with the user. Chosen on the agent page; [CONFIRM_SENDS] is the default.
 *
 * - [ASK_OFTEN]: every commit, every connector write, every action that opens an app, link or
 *   intent, and the first send-out after private content.
 * - [CONFIRM_SENDS]: one verification card per commit (send, post, order, book, delete), connector
 *   writes, and the first send-out to the web after private content. Opening apps never asks.
 * - [HANDS_FREE]: only orders and deletes, plus any commit in a turn that read a notification or a
 *   web page, plus the first send-out to the web after private content.
 * - Why every mode keeps the web send-out question: that is the one path by which text a stranger
 *   wrote (a page, a notification) can carry the user's private data off the phone.
 * - Why HANDS_FREE still asks after a notification or web page: those are the places a stranger can
 *   write "send this to everyone". A screen alone does not count, or it would ask on every task.
 */
enum class ApprovalMode { ASK_OFTEN, CONFIRM_SENDS, HANDS_FREE }

/** User-controlled strictness. */
data class SafetySettings(val mode: ApprovalMode = ApprovalMode.CONFIRM_SENDS)

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
        if (risk == ToolRisk.COMMIT && commitAsks(call, settings.mode, taint)) {
            return Verdict.Ask(commitQuestion(call))
        }
        val connectorWrite = call.origin == ToolOrigin.CONNECTOR && risk != ToolRisk.READ
        if (connectorWrite && (settings.mode != ApprovalMode.HANDS_FREE || taint.tainted)) {
            return Verdict.Ask("Allow ${call.tool}? It changes something outside this phone.")
        }
        if (call.egress && taint.egressNeedsUser) {
            if (mode == RunMode.BACKGROUND) {
                return Verdict.Deny("policy_blocked: private content was read this turn, so nothing may be sent out.")
            }
            return Verdict.Ask(
                "AURA read private content this turn and now wants to use ${call.tool}, " +
                    "which sends text off this phone. Allow for this task?",
                unlocksEgress = true,
            )
        }
        if (settings.mode == ApprovalMode.ASK_OFTEN && risk == ToolRisk.ACT && call.tool in OPENS_SOMETHING) {
            return Verdict.Ask("Allow ${call.tool}? ${SecretPatterns.redact(call.argsText).take(MAX_ARGS_IN_QUESTION)}")
        }
        return Verdict.Allow
    }

    private fun commitAsks(call: ToolCallRequest, mode: ApprovalMode, taint: TurnTaint): Boolean = when (mode) {
        ApprovalMode.ASK_OFTEN, ApprovalMode.CONFIRM_SENDS -> true
        ApprovalMode.HANDS_FREE ->
            CommitLabels.kind(call.targetLabel) in ALWAYS_ASK ||
                call.targetLabel == null ||
                taint.origins.any { it == Origin.NOTIFICATION || it == Origin.WEB }
    }

    /** A gesture on a button that commits the user is a commit, whatever the tool is called. */
    internal fun effectiveRisk(call: ToolCallRequest): ToolRisk =
        if (call.risk == ToolRisk.ACT && CommitLabels.isCommit(call.targetLabel)) ToolRisk.COMMIT else call.risk

    /**
     * The card's title: what will happen, where. "Send in WhatsApp?", "Place order in Zomato?".
     * The recipient, text or amount is the model's own line, shown under it by the hub.
     */
    internal fun commitQuestion(call: ToolCallRequest): String {
        val label = call.targetLabel ?: return when (call.tool) {
            "notification_action" -> "Send this reply?"
            else -> "Allow ${call.tool}? ${SecretPatterns.redact(call.argsText).take(MAX_ARGS_IN_QUESTION)}"
        }
        val app = call.appLabel?.takeIf { it.isNotBlank() && !it.equals("AURA", ignoreCase = true) }
        val (verb, preposition) = when (CommitLabels.kind(label)) {
            CommitKind.SEND -> "Send" to "in"
            CommitKind.POST -> "Post" to "on"
            CommitKind.ORDER -> "Place order" to "in"
            CommitKind.BOOK -> "Book" to "in"
            CommitKind.DELETE -> "Delete" to "in"
            CommitKind.OTHER -> "Press \"$label\"" to "in"
        }
        return if (app == null) "$verb?" else "$verb $preposition $app?"
    }

    /** What the model is told when the user says no to a card. */
    const val DECLINED =
        "The user declined this step. Nothing was sent or changed. Do not retry it or reach the same " +
            "result another way; ask the user what they want changed."

    private const val MAX_ARGS_IN_QUESTION = 200

    /** Commits HANDS_FREE still asks about: money and deletion cannot be taken back. */
    private val ALWAYS_ASK = setOf(CommitKind.ORDER, CommitKind.DELETE)

    /** Phone actions that open something; ASK_OFTEN asks before each. */
    private val OPENS_SOMETHING = setOf("launch_app", "open_deeplink", "system_intent", "open_file", "notification_action")
}

/** What a commit button does, for the card title and for which commits HANDS_FREE still asks about. */
enum class CommitKind { SEND, POST, ORDER, BOOK, DELETE, OTHER }

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

    fun isCommit(label: String?): Boolean = tokens(label).any { it in words }

    /** The kind of the first commit word in [label]; [CommitKind.OTHER] when none or unknown. */
    fun kind(label: String?): CommitKind = tokens(label).firstNotNullOfOrNull { KINDS[it] } ?: CommitKind.OTHER

    private fun tokens(label: String?): List<String> =
        label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 40 }?.split(Regex("[^a-z]+")).orEmpty()

    private val KINDS: Map<String, CommitKind> = buildMap {
        listOf("send", "reply", "share").forEach { put(it, CommitKind.SEND) }
        listOf("post", "publish").forEach { put(it, CommitKind.POST) }
        listOf("pay", "buy", "order", "purchase", "checkout", "transfer", "donate", "subscribe").forEach { put(it, CommitKind.ORDER) }
        put("book", CommitKind.BOOK)
        listOf("delete", "remove", "erase", "uninstall", "unsubscribe").forEach { put(it, CommitKind.DELETE) }
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
        // "pin: 4321", "cvv is 123". Digits are required, so "the pin is ready" passes.
        Regex("""(?i)\b(?:pin|cvv|passcode)\b\s*(?:is|:|=)\s*\d{3,8}\b"""),
        // "password is hunter2". A token holding a digit or symbol, so "password is wrong" passes.
        Regex("""(?i)\bpassword\b\s*(?:is|:|=)\s*(?=\S*[\d!@#$%^&*])\S{4,}"""),
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
