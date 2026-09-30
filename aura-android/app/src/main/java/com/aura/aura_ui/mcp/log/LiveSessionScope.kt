package com.aura.aura_ui.mcp.log

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Where an agent run finds the conversation that asked for it.
 *
 * A phone task started by voice is one story: "open Instagram and go to my profile", then the
 * taps that carried it out, then AURA saying it is done. Logged as two sessions it reads as two
 * unrelated fragments, and the interesting question — did the phone do what the user actually
 * asked? — needs both open side by side. So a run the Live plane started writes into the
 * conversation's session instead of opening its own.
 *
 * Deliberately NOT ambient adoption of whatever session happens to be open: a run the user
 * starts from the app's own UI while a conversation is idle in the background is a separate
 * story and keeps its own session. Only a run wrapped in [expectRun] joins, and only the first
 * one — [claim] takes the intent, so a second run cannot inherit a stale flag.
 *
 * Single agent run at a time is assumed (the run ledger enforces it); with concurrent runs the
 * first to start would take the intent, which is also the one the conversation asked for.
 */
object LiveSessionScope {

    private val writer = AtomicReference<SessionLogWriter?>(null)
    private val runExpected = AtomicBoolean(false)

    /** Called by the conversation logger when a Live session opens (null when it ends). */
    fun setConversation(current: SessionLogWriter?) {
        writer.set(current)
        if (current == null) runExpected.set(false)
    }

    /**
     * Run [block] with the next agent run marked as belonging to the current conversation.
     * The flag is cleared afterwards so a task that fails before opening a log cannot leave
     * the next unrelated run adopting a conversation it had nothing to do with.
     */
    suspend fun <T> expectRun(block: suspend () -> T): T {
        runExpected.set(true)
        return try {
            block()
        } finally {
            runExpected.set(false)
        }
    }

    /**
     * The conversation's writer if this run was started by it, else null. Consumes the
     * intent — one marked run adopts one session.
     */
    fun claim(): SessionLogWriter? = if (runExpected.getAndSet(false)) writer.get() else null
}
