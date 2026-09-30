package com.aura.aura_ui.agent.hitl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * One in-flight question the agent is waiting on. [options] are tap-to-answer
 * chips (0–4); the UI always adds an "Other…" free-text affordance on top.
 */
data class PendingQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
)

sealed interface AskUserAnswer {
    /** The user tapped an option or typed a free-text answer. */
    data class Answered(val text: String) : AskUserAnswer

    /** The user explicitly closed the question without answering. */
    data object Dismissed : AskUserAnswer

    /** No answer arrived within the deadline. */
    data object Timeout : AskUserAnswer

    /** A question was already pending — questions never stack. */
    data object Busy : AskUserAnswer
}

/**
 * HITL rendezvous between the agent loop (suspends in [ask]) and whatever UI
 * is showing (observes [pending], calls [answer]/[dismiss]).
 *
 * Deliberately UI-agnostic: the overlay chat renders chips, the Live voice
 * plane speaks the question, a notification can mirror it — all against this
 * one StateFlow. Single-question discipline: the agent is sequential, so a
 * second concurrent [ask] gets [AskUserAnswer.Busy] instead of clobbering the
 * first (and the model is told not to stack questions).
 */
class AskUserBroker {

    private val _pending = MutableStateFlow<PendingQuestion?>(null)
    val pending: StateFlow<PendingQuestion?> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<AskUserAnswer>? = null
    private val seq = AtomicLong(0)

    suspend fun ask(question: String, options: List<String>, timeoutMs: Long): AskUserAnswer {
        val d = CompletableDeferred<AskUserAnswer>()
        val q = PendingQuestion("q${seq.incrementAndGet()}", question, options)
        synchronized(this) {
            if (deferred != null) return AskUserAnswer.Busy
            deferred = d
            _pending.value = q
        }
        return try {
            withTimeoutOrNull(timeoutMs) { d.await() } ?: AskUserAnswer.Timeout
        } finally {
            synchronized(this) {
                if (deferred === d) {
                    deferred = null
                    _pending.value = null
                }
            }
        }
    }

    /** UI answer path. False when [id] is stale (question already resolved/replaced). */
    fun answer(id: String, text: String): Boolean = synchronized(this) {
        val q = _pending.value ?: return false
        if (q.id != id) return false
        deferred?.complete(AskUserAnswer.Answered(text)) ?: return false
        true
    }

    /** UI close-without-answer path. */
    fun dismiss(id: String) {
        synchronized(this) {
            val q = _pending.value ?: return
            if (q.id != id) return
            deferred?.complete(AskUserAnswer.Dismissed)
        }
    }

    /** Run teardown: resolve anything still waiting so the agent never hangs. */
    fun cancelAll() {
        synchronized(this) { deferred?.complete(AskUserAnswer.Dismissed) }
    }

    companion object {
        /** Process-wide instance shared by the agent loop, overlay UI, and Live plane. */
        val shared = AskUserBroker()
    }
}
