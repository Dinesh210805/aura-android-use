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
    /** The newest screen's `SEEN:` caption (app and top labels), for the deny list on a commit tap. */
    val screenTitle: String? = null,
    /** The text this turn last typed into a field (`type_text`, `set_value`), for a commit tap. */
    val typed: String? = null,
)

/**
 * What the user approved with `propose_action`, kept on the chat (see `ToolHub.grants`).
 * - [message] is the exact text approved; null means any text to [to] in [app] for the rest of the
 *   chat ("don't ask again in this chat"), which is never offered once untrusted text was read.
 */
data class Grant(val app: String, val to: String, val message: String?, val expiresAt: Long, val linkUsed: Boolean = false)

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
data class SafetySettings(
    val mode: ApprovalMode = ApprovalMode.CONFIRM_SENDS,
    /** Apps AURA must never act in or open, by name as the user typed them ("Instagram"). */
    val deniedApps: Set<String> = emptySet(),
    /** People AURA must never message, by name or number as the user typed them. */
    val deniedContacts: Set<String> = emptySet(),
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
        blocked(call, settings)?.let { return Verdict.Deny(it) }
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

    /**
     * Why the user's own deny lists forbid [call], or null. An app counts when it is in front for an
     * action or named by an opener; a person counts when named in what is sent or, for a commit tap,
     * on the screen being committed. Word-bounded, so "Ram" does not match "Ramesh".
     */
    internal fun blocked(call: ToolCallRequest, settings: SafetySettings): String? {
        if (call.risk == ToolRisk.READ && call.tool != PROPOSE_ACTION) return null
        val opener = call.tool in OPENS_SOMETHING
        settings.deniedApps.firstOrNull { app ->
            mentions(call.appLabel, app) || (opener && mentions(call.argsText, app))
        }?.let { return "policy_blocked: the user does not let AURA use $it. Tell them you cannot do this there." }
        val sends = call.egress || call.tool == PROPOSE_ACTION || call.origin == ToolOrigin.CONNECTOR ||
            CommitLabels.isCommit(call.targetLabel)
        if (!sends) return null
        settings.deniedContacts.firstOrNull { who ->
            mentions(call.argsText, who) || (CommitLabels.isCommit(call.targetLabel) && mentions(call.screenTitle, who))
        }?.let { return "policy_blocked: the user does not let AURA message $it. Tell them you cannot send this." }
        return null
    }

    private fun mentions(text: String?, name: String): Boolean {
        val n = name.trim()
        if (text == null || n.isEmpty()) return false
        if (n.any(Char::isDigit)) {
            val digits = n.filter(Char::isDigit).takeLast(PHONE_TAIL)
            return digits.length >= MIN_DENY_DIGITS && digits in text.filter(Char::isDigit)
        }
        return Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(n) + "(?![\\p{L}\\p{N}])").containsMatchIn(text)
    }

    /**
     * The [Grant] that already covers this [ask], so the user is not asked twice; null to ask.
     * - A Send or Post tap in the granted app (exact name), with the recipient named on screen and
     *   the approved text on it, where the text in the box is known to be exactly the approved one:
     *   the turn's last typed text ([ToolCallRequest.typed]) equals it, or it arrived through a link
     *   this grant covered. Or a chat-wide grant for that recipient, which never covers the
     *   private-content send-out ask.
     * - A connector call, not after an earlier link: its string arguments are exactly the approved
     *   text and exactly the recipient, nothing else.
     * - A link, not after an earlier link: to the granted app's own link host, carrying only the
     *   approved text and a number in [knownNumbers] (said by the user or returned by `resolve_contact`).
     * - Never orders, bookings or deletes: those always get their own card.
     * - Why exact equality: "contains" let extra text ride along before or after the approved words.
     * - Contract: the caller marks the grant used ([used]); a covered call never unlocks other sends.
     */
    internal fun covered(
        call: ToolCallRequest,
        ask: Verdict.Ask,
        grants: List<Grant>,
        screen: String?,
        now: Long,
        knownNumbers: Set<String> = emptySet(),
    ): Grant? {
        val live = grants.filter { it.expiresAt > now }
        if (live.isEmpty()) return null
        if (effectiveRisk(call) == ToolRisk.COMMIT) {
            if (CommitLabels.kind(call.targetLabel) !in GRANTABLE) return null
            val app = call.appLabel ?: return null
            if (screen == null) return null
            val shown = normal(screen)
            return live.firstOrNull { g ->
                sameApp(g.app, app) && mentions(screen, g.to) && when (val m = g.message) {
                    null -> !ask.unlocksEgress
                    // After a covered link the hub clears `typed`, so anything typed since must equal it too.
                    else -> normal(m) in shown && call.typed.let { t -> if (t == null) g.linkUsed else normal(t) == normal(m) }
                }
            }
        }
        if (call.origin == ToolOrigin.CONNECTOR) {
            // Every value is exactly the message or exactly the recipient: a subject, a cc, or a
            // "to" listing a second address is not approved. A name grant vs an address arg asks.
            val values = argumentValues(call.argsText, withParts = false)
            return live.firstOrNull { g ->
                val m = g.message ?: return@firstOrNull false
                !g.linkUsed && values.any { normal(it) == normal(m) } && values.any { normal(it) == normal(g.to) } &&
                    values.all { normal(it) == normal(m) || normal(it) == normal(g.to) }
            }
        }
        if (call.egress) {
            val urls = argumentValues(call.argsText, withParts = false).filter { "://" in it || it.startsWith("sms") }
            val url = urls.singleOrNull() ?: return null
            return live.firstOrNull { g -> g.message != null && !g.linkUsed && linkCarriesOnly(url, g, knownNumbers) }
        }
        return null
    }

    /**
     * True when [url] goes to [grant]'s app by its own link host and carries nothing but the
     * approved text and a recipient number the user said or `resolve_contact` returned.
     */
    private fun linkCarriesOnly(url: String, grant: Grant, knownNumbers: Set<String>): Boolean {
        val hosts = APP_LINKS[grant.app.trim().lowercase()] ?: return false
        val scheme = url.substringBefore(':').lowercase()
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase()
        if (!hosts.any { it == "$scheme:" || it == host }) return false
        val parts = linkParts(url).filter { it.isNotBlank() }
        var carriesText = false
        val ok = parts.all { p ->
            val digits = p.filter(Char::isDigit)
            when {
                normal(p) == normal(grant.message.orEmpty()) -> true.also { carriesText = true }
                p.trimStart('+').all(Char::isDigit) && digits.takeLast(PHONE_TAIL) in knownNumbers -> true
                p.lowercase() in LINK_ROUTING -> true
                else -> false
            }
        }
        return ok && carriesText
    }

    /**
     * Every string value in a call's JSON arguments, plus, for each that is a link, its path parts
     * and decoded query values: the pieces a sent text can be.
     */
    internal fun argumentValues(argsText: String, withParts: Boolean = true): List<String> {
        val strings = mutableListOf<String>()
        fun walk(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is kotlinx.serialization.json.JsonObject -> e.values.forEach(::walk)
                is kotlinx.serialization.json.JsonArray -> e.forEach(::walk)
                is kotlinx.serialization.json.JsonPrimitive -> if (e.isString) strings += e.content
            }
        }
        runCatching { walk(kotlinx.serialization.json.Json.parseToJsonElement(argsText)) }
        if (!withParts) return strings
        return strings + strings.filter { "://" in it || it.startsWith("intent:") || it.startsWith("sms") }.flatMap(::linkParts)
    }

    /** A link's path parts (after the host), query names and values, and fragment, decoded. */
    private fun linkParts(url: String): List<String> {
        val rest = url.substringAfter("://", url.substringAfter(':'))
        val path = rest.substringBefore('?').substringBefore('#').split('/').let { if ("://" in url) it.drop(1) else it }
        val query = rest.substringAfter('?', "").substringBefore('#').split('&').flatMap { listOf(it.substringBefore('='), it.substringAfter('=', "")) }
        val fragment = rest.substringAfter('#', "")
        return (path + query + fragment).map { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }

    /** [grants] after [grant] covered [call]: a tap uses it up, a link marks it so a second link asks. */
    internal fun used(grants: List<Grant>, grant: Grant, call: ToolCallRequest): List<Grant> =
        if (effectiveRisk(call) == ToolRisk.COMMIT) {
            if (grant.message == null) grants else grants - grant
        } else {
            grants.map { if (it == grant) it.copy(linkUsed = true) else it }
        }

    private fun sameApp(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    private fun normal(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()

    /** The link hosts and schemes each messaging app opens a chat with; a link elsewhere is not covered. */
    private val APP_LINKS = mapOf(
        "whatsapp" to setOf("wa.me", "api.whatsapp.com", "whatsapp:"),
        "telegram" to setOf("t.me", "tg:"),
        "messages" to setOf("sms:", "smsto:"),
        "sms" to setOf("sms:", "smsto:"),
        "signal" to setOf("signal.me", "sgnl:"),
    )

    /** Fixed path and parameter words a chat link may hold besides the text and the number. */
    private val LINK_ROUTING = setOf("send", "msg", "message", "chat", "compose", "text", "body", "phone", "to")

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
    const val PROPOSE_ACTION = "propose_action"
    private val GRANTABLE = setOf(CommitKind.SEND, CommitKind.POST)
    private const val PHONE_TAIL = 10
    private const val MIN_DENY_DIGITS = 6

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
