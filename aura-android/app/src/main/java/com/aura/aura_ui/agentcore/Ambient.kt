package com.aura.aura_ui.agentcore

/**
 * The always-on side of AURA: what happens when nobody is talking to it.
 *
 * It is a pipeline, not an agent. Events arrive (a notification, a schedule), rules decide what
 * they are worth, an on-device model words the result when one is available, and the user gets
 * one notification. No model plans anything here and no tool is called.
 *
 * - Contract: nothing in this file sends notification text off the device. A cloud model is only
 *   ever reached by the user opening the digest and asking about it, which is an ordinary
 *   foreground turn in [AgentEngine].
 * - Why rules first and the model last: a rule costs nothing, runs in Doze and is right about
 *   "this is an OTP". A small on-device model is good at wording a summary of five messages and
 *   unreliable at deciding what matters. So rules decide, and the model only phrases.
 * - Fails: with no local model the digest is built by [DigestBuilder.byRules]; the feature works
 *   on every phone, and reads better on the ones that can run a model.
 * - Change together: `AmbientAndPolicyTest`.
 */

/** A notification, reduced to what triage needs. Built by the notification listener. */
data class NotificationSignal(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val text: String?,
    /** `Notification.category`, for example "msg", "email", "promo". */
    val category: String?,
    /** True for a person-to-person message (MessagingStyle or a conversation shortcut). */
    val isConversation: Boolean,
    val isOngoing: Boolean,
    val postedAtMs: Long,
)

enum class Urgency {
    /** Never shown or summarised by AURA. */
    DROP,

    /** Held for the next digest. */
    DIGEST,

    /** Worth telling the user about now. */
    NOW,
}

data class TriagePrefs(
    /** Sender names the user marked as always urgent, lower-cased. */
    val priorityPeople: Set<String> = emptySet(),
    val mutedPackages: Set<String> = emptySet(),
)

object NotificationTriage {
    private val dropCategories = setOf(
        "call", "alarm", "promo", "recommendation", "progress", "transport", "sys", "service", "status", "navigation",
    )
    private val digestCategories = setOf("msg", "email", "event", "reminder", "social")
    private val code = Regex("""(?i)\b(otp|code|passcode|verification|one[- ]time)\b.{0,40}\b\d{4,8}\b|\b\d{4,8}\b.{0,40}\b(otp|code|passcode)\b""")

    /**
     * [isSensitivePackage] answers for banking, payment, authenticator and password apps; pass
     * `SensitivePolicy.screenPackage` from `:mcp-server` so there is one list.
     */
    fun classify(n: NotificationSignal, prefs: TriagePrefs, isSensitivePackage: (String) -> Boolean): Urgency {
        val body = listOfNotNull(n.title, n.text).joinToString(" ")
        return when {
            n.isOngoing || body.isBlank() -> Urgency.DROP
            n.packageName in prefs.mutedPackages -> Urgency.DROP
            isSensitivePackage(n.packageName) || code.containsMatchIn(body) -> Urgency.DROP
            n.category in dropCategories -> Urgency.DROP
            n.isConversation && n.title?.lowercase() in prefs.priorityPeople -> Urgency.NOW
            n.isConversation || n.category in digestCategories -> Urgency.DIGEST
            else -> Urgency.DROP
        }
    }
}

/**
 * An on-device model, used only to phrase text.
 *
 * - Contract: [summarize] returns null when the model is missing, busy, too hot or too slow.
 *   Callers must have a rules-only answer ready.
 */
interface LocalModel {
    val available: Boolean

    suspend fun summarize(text: String, maxWords: Int): String?
}

/** The model on a phone that cannot run one. */
object NoLocalModel : LocalModel {
    override val available: Boolean = false

    override suspend fun summarize(text: String, maxWords: Int): String? = null
}

data class Digest(val title: String, val lines: List<String>)

object DigestBuilder {
    private const val MAX_APPS = 6
    private const val MAX_SENDERS = 3
    private const val SUMMARY_WORDS = 18
    private const val MAX_MODEL_INPUT = 1_500

    /** One line per app: "WhatsApp: 5 from Priya, Arun". No model, no message text. */
    fun byRules(signals: List<NotificationSignal>): Digest? {
        if (signals.isEmpty()) return null
        return Digest(title(signals), busiestApps(signals).map { (app, items) -> ruleLine(app, items) })
    }

    /** As [byRules], with each app's line replaced by the model's summary where it produced one. */
    suspend fun build(signals: List<NotificationSignal>, model: LocalModel): Digest? {
        if (signals.isEmpty() || !model.available) return byRules(signals)
        val lines = busiestApps(signals).map { (app, items) ->
            val text = items.joinToString("\n") { "${it.title.orEmpty()}: ${it.text.orEmpty()}" }
            model.summarize(text.take(MAX_MODEL_INPUT), SUMMARY_WORDS)?.let { "$app: $it" } ?: ruleLine(app, items)
        }
        return Digest(title(signals), lines)
    }

    private fun title(signals: List<NotificationSignal>) = "${signals.size} new while you were away"

    private fun busiestApps(signals: List<NotificationSignal>): List<Pair<String, List<NotificationSignal>>> =
        signals.groupBy { it.appLabel }.toList().sortedByDescending { it.second.size }.take(MAX_APPS)

    private fun ruleLine(app: String, items: List<NotificationSignal>): String {
        val senders = items.mapNotNull { it.title }.distinct()
        val who = senders.take(MAX_SENDERS).joinToString(", ") +
            if (senders.size > MAX_SENDERS) " and ${senders.size - MAX_SENDERS} more" else ""
        return if (who.isBlank()) "$app: ${items.size}" else "$app: ${items.size} from $who"
    }
}

/**
 * Holds notifications between digests.
 *
 * - Contract: thread-safe, bounded to [capacity] (oldest dropped), one entry per notification key.
 *   In memory only: a digest lost to a process death is cheaper than notification text on disk.
 */
class AmbientInbox(private val capacity: Int = 200) {
    private val pending = LinkedHashMap<String, NotificationSignal>()

    @Synchronized
    fun add(signal: NotificationSignal) {
        pending.remove(signal.key)
        pending[signal.key] = signal
        while (pending.size > capacity) pending.remove(pending.keys.first())
    }

    @Synchronized
    fun remove(key: String) {
        pending.remove(key)
    }

    @Synchronized
    fun drain(): List<NotificationSignal> = pending.values.toList().also { pending.clear() }
}

/**
 * Wires the pieces: call [onPosted] and [onRemoved] from the notification listener and [flush]
 * from a periodic worker.
 */
class AmbientCoordinator(
    private val prefs: () -> TriagePrefs,
    private val isSensitivePackage: (String) -> Boolean,
    private val model: LocalModel = NoLocalModel,
    /** Show an urgent notification's sender to the user now. */
    private val notifyNow: (NotificationSignal) -> Unit,
    private val notifyDigest: (Digest) -> Unit,
) {
    private val inbox = AmbientInbox()

    fun onPosted(signal: NotificationSignal) {
        when (NotificationTriage.classify(signal, prefs(), isSensitivePackage)) {
            Urgency.NOW -> notifyNow(signal)
            Urgency.DIGEST -> inbox.add(signal)
            Urgency.DROP -> Unit
        }
    }

    /** The user dealt with it themselves, so it does not belong in the digest. */
    fun onRemoved(key: String) = inbox.remove(key)

    suspend fun flush() {
        DigestBuilder.build(inbox.drain(), model)?.let(notifyDigest)
    }
}
