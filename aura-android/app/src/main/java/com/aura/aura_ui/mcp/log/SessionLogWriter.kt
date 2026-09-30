package com.aura.aura_ui.mcp.log

import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Sole owner of one session's `metadata.json`: the directory, the in-memory [SessionLog], the
 * lock around it, and the whole-file rewrite on every change.
 *
 * ### Why one owner
 *
 * [AgentRunLogger] and [LiveConversationLogger] were built independently and each kept its own
 * scope, mutex, `session` field and `persist()`. That is fine while they write different files
 * and fatal the moment they should write the same one: two writers each rewriting the whole file
 * from a private copy means the last writer wins and the other plane's entries vanish. A
 * conversation and the phone task it triggered can only share a session if a single writer owns
 * the file, so that ownership lives here and both loggers became views on it.
 *
 * Every mutation runs on an IO scope under [mutex] and persists before releasing it, so a
 * caller never blocks and readers never see a half-written array. Failures are logged, never
 * thrown: a logging glitch must not cost a conversation turn or crash an agent run.
 */
class SessionLogWriter(
    private val rootDir: File,
    private val tag: String,
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.w(tag, "session log write failed: ${t.message}")
    }
    /**
     * ONE worker, so queued work runs in submission order.
     *
     * Plain `Dispatchers.IO` is multi-threaded and gives no such guarantee: an append submitted
     * immediately after the open could reach the lock first, find no session and be dropped —
     * which is exactly how a conversation lost its opening turns, since the previous logger
     * responded to that empty state by opening a *second* session and orphaning them there.
     * The mutex stays on top because an edit may suspend mid-way (the agent logger captures a
     * screenshot inside one) and must not let the next edit interleave.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + exceptionHandler,
    )
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    private var session: SessionLog? = null
    private var sessionDir: File? = null

    /**
     * How to build the session, held from [open] until the first edit that deserves one.
     *
     * Nothing is created on disk until then. A Live connect that dies before anyone speaks used
     * to leave a directory holding "session started / session ended" and nothing else — a card
     * in the Logs list for a conversation that never happened.
     */
    private var builder: ((String, Long) -> SessionLog)? = null

    /**
     * Set by [close]. The session is kept in memory afterwards so late arrivals still land in
     * the file they belong to, but it must not be mistaken for a live one: the NEXT [open] is a
     * new conversation and has to replace it, not be ignored as a duplicate.
     */
    private var closed = false

    /**
     * Declare a session, to be created on disk by the first materializing [edit].
     *
     * Re-opening while a session is live or already declared is ignored: that caller is adopting
     * an existing session (an agent run joining a conversation), not starting a new one.
     */
    fun open(build: (sessionId: String, nowMillis: Long) -> SessionLog) {
        scope.launch {
            mutex.withLock {
                if (!closed && (session != null || builder != null)) return@withLock
                session = null
                sessionDir = null
                closed = false
                builder = build
            }
        }
    }

    /**
     * Mutate the session and persist, creating it first if this is the first entry worth a
     * session at all.
     *
     * [block] may suspend — the agent logger captures a screenshot inside its edit, and forcing
     * that outside the lock would let a later tool call renumber the invocation it belongs to.
     *
     * Pass `materialize = false` for entries that must never bring a session into existence on
     * their own (session-lifecycle notes). Such an entry is recorded when a session already
     * exists and dropped when none does, which is the difference between "a conversation, with
     * its start noted" and "a directory whose entire contents are a start and an end".
     */
    fun edit(materialize: Boolean = true, block: suspend (session: SessionLog, dir: File) -> Unit) {
        scope.launch {
            mutex.withLock {
                if (session == null && materialize) materializeLocked()
                val s = session ?: return@withLock
                val d = sessionDir ?: return@withLock
                runCatching { block(s, d) }.onFailure { Log.w(tag, "session edit failed: ${it.message}") }
                persist()
            }
        }
    }

    /**
     * Apply a final mutation and persist.
     *
     * The session stays in memory afterwards, deliberately. A conversation's last words arrive
     * *after* its teardown begins — a greeting still in flight from a dying socket, the closing
     * note itself — and a writer that forgot the session would treat each straggler as the first
     * entry of a new one. That is exactly what left single-line orphan sessions on disk. They
     * belong to the conversation that just ended, so they land in its file; only [open] starts
     * something new.
     */
    fun close(block: (session: SessionLog) -> Unit) {
        scope.launch {
            mutex.withLock {
                builder = null
                closed = true
                val s = session ?: return@withLock
                runCatching { block(s) }.onFailure { Log.w(tag, "session close failed: ${it.message}") }
                persist()
            }
        }
    }

    private fun materializeLocked() {
        val build = builder ?: return
        val now = System.currentTimeMillis()
        val id = "$now-${UUID.randomUUID().toString().substringBefore('-')}"
        session = build(id, now)
        sessionDir = File(rootDir, id).apply { mkdirs() }
    }

    private fun persist() {
        val s = session ?: return
        val dir = sessionDir ?: return
        runCatching { File(dir, "metadata.json").writeText(json.encodeToString(SessionLog.serializer(), s)) }
            .onFailure { Log.w(tag, "could not write session ${s.sessionId}: ${it.message}") }
    }
}
