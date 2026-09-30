package com.aura.aura_ui.agent.conversation

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Every parsed Live frame the audio controller needs. Default no-op methods keep call sites (and the
 * debug harness) terse — implement only what you consume. The session dispatches tool calls itself;
 * [onToolStarted]/[onToolFinished] bracket each dispatch so the controller can pause/resume the mic.
 */
interface LiveSessionListener {
    fun onSetupComplete() {}
    fun onUserTranscript(text: String) {}
    fun onModelTranscript(text: String) {}
    fun onAudio(pcm: ByteArray) {}
    fun onTurnComplete() {}
    fun onGenerationComplete() {}
    fun onInterrupted() {}
    fun onToolStarted(name: String) {}
    fun onToolFinished(name: String) {}

    /**
     * A call turned out to be long-running and has been acknowledged — a phone task is now
     * driving the screen.
     *
     * Separate from [onToolStarted] because the two no longer coincide. With one universal tool
     * the controller cannot tell a phone task from a spoken answer by name, and keying the UI
     * handoff off the name would minimise the chat window every time the user asked a question.
     */
    fun onLongRunningStarted(label: String) {}

    /** The long-running call finished, was cancelled, or failed. Always paired with the above. */
    fun onLongRunningFinished() {}
    fun onToolCancelled(ids: List<String>) {}
    fun onResumeHandle(handle: String?) {}
    fun onUsage(total: Int) {}
    fun onGoAway() {}
    fun onClosed() {}
    /** A frame LiveProtocol could not classify, or a socket close/failure reason. Diagnostics only —
     *  Google sends setup-rejection/error details as frames we don't model, so surface them here. */
    fun onUnparsed(raw: String) {}
}

/**
 * Direct-to-Google Gemini Live (BidiGenerateContent) bidi session. Thin glue over the tested pure
 * pieces: LiveProtocol frames the setup + parses inbound, CompanionTools dispatches tool calls. Every
 * parsed frame is surfaced through [LiveSessionListener]; untrusted/malformed input degrades to
 * Unparsed and is ignored, never crashing the session. [connectKey] is an ephemeral token when one
 * could be minted, otherwise the raw BYOK key.
 */
class LiveSession(
    private val connectKey: String,
    private val config: LiveSessionConfig,
    private val tools: CompanionToolHandler,
    private val scope: CoroutineScope,
    private val listener: LiveSessionListener,
    /**
     * How the socket is opened. Defaults to the shared OkHttp client; a test supplies a fake so the
     * dispatch + terminal-state contracts can be driven without a network.
     */
    private val socketFactory: (Request, WebSocketListener) -> WebSocket =
        { request, wsListener -> client.newWebSocket(request, wsListener) },
) {
    private var ws: WebSocket? = null

    // ── Terminal-state discipline (field incident 2026-07-15) ────────────────────
    // OkHttp fires onClosing AND onClosed for one death, and fires them for OUR OWN
    // close() too. Forwarding each naively gave the controller multiple onClosed()
    // per death — overlapping reconnects then closed each other's fresh sessions in
    // a self-sustaining storm (~9 sessions/sec, speech arriving 30-40 s late).
    // Rules: an intentionally closed session never signals; a session signals
    // onClosed at most ONCE; a closed session delivers no further frames.
    private val closedByUs = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closedSignalled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The one long-running call in flight, and its progress throttle. */
    private val tracker = LiveTaskTracker()

    /**
     * Did this session ever send an early acknowledgement? Only then can a 1007 rejection be
     * attributed to asynchronous function calling — see [noteAsyncRejectionIfAny].
     */
    private val sentEarlyAck = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun signalClosedOnce() {
        if (!closedByUs.get() && !closedSignalled.getAndSet(true)) listener.onClosed()
    }

    fun connect(): Boolean {
        val url = "$ENDPOINT?key=$connectKey"
        ws = socketFactory(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(socket: WebSocket, response: Response) {
                    socket.send(LiveProtocol.buildSetup(config))
                }
                // The Live API sends server frames as BINARY (JSON bytes), so both overloads route
                // to the same handler — handling only the text overload silently drops every frame.
                override fun onMessage(socket: WebSocket, bytes: okio.ByteString) { handleFrame(socket, bytes.utf8()) }
                override fun onMessage(socket: WebSocket, text: String) { handleFrame(socket, text) }

                fun handleFrame(socket: WebSocket, text: String) {
                    if (closedByUs.get()) return   // stale session must not touch controller state
                    val msg0 = LiveProtocol.parseServerMessage(text)
                    // Diagnostic: inbound frame arrival — correlates "when did the server's
                    // transcript/interrupt actually reach the device" against send-side backlog.
                    when (msg0) {
                        is LiveServerMessage.ServerContent -> {
                            if (msg0.userTranscript != null || msg0.interrupted ||
                                msg0.turnComplete || msg0.generationComplete
                            ) {
                                Log.d(
                                    TAG,
                                    "diag: in userTx=${msg0.userTranscript != null} " +
                                        "int=${msg0.interrupted} gen=${msg0.generationComplete} " +
                                        "turn=${msg0.turnComplete} audio=${msg0.audio?.size ?: 0}B",
                                )
                            }
                        }
                        !is LiveServerMessage.Unparsed -> Log.d(TAG, "diag: in ${msg0::class.simpleName}")
                        else -> {}
                    }
                    when (val msg = msg0) {
                        is LiveServerMessage.SetupComplete -> listener.onSetupComplete()
                        is LiveServerMessage.ServerContent -> {
                            msg.userTranscript?.let(listener::onUserTranscript)
                            msg.modelTranscript?.let(listener::onModelTranscript)
                            msg.audio?.let(listener::onAudio)
                            if (msg.interrupted) listener.onInterrupted()
                            if (msg.generationComplete) listener.onGenerationComplete()
                            if (msg.turnComplete) listener.onTurnComplete()
                        }
                        // One coroutine PER CALL, not per batch: the old code ran a batch
                        // sequentially inside a single launch, so a minutes-long task blocked
                        // every sibling call that arrived with it.
                        //
                        // The gate runs HERE rather than inside dispatchToolCall because it has
                        // to see the whole batch at once — "is this the second device change in
                        // one turn" is not a question a single call can answer about itself.
                        is LiveServerMessage.ToolCall -> {
                            val decision = ToolBatchGate.apply(msg.calls)
                            val applied = decision.allowed
                                .firstOrNull { it.name in LiveToolDeclarations.STATE_CHANGING }
                            decision.refused.forEach { call ->
                                Log.w(TAG, "batch guard refused '${call.name}' — one device change per turn")
                                socket.send(
                                    LiveProtocol.buildToolResponse(
                                        call.id,
                                        call.name,
                                        ToolBatchGate.refusalFor(call, applied),
                                        null,
                                    ),
                                )
                            }
                            decision.allowed.forEach { call -> dispatchToolCall(socket, call) }
                        }
                        is LiveServerMessage.ToolCallCancellation -> listener.onToolCancelled(msg.ids)
                        is LiveServerMessage.SessionResumptionUpdate -> listener.onResumeHandle(msg.handle)
                        is LiveServerMessage.Usage -> listener.onUsage(msg.totalTokens)
                        is LiveServerMessage.GoAway -> listener.onGoAway()
                        is LiveServerMessage.Unparsed -> listener.onUnparsed(msg.raw)
                    }
                }
                override fun onClosing(socket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "Live socket closing: code=$code reason=$reason ours=${closedByUs.get()}")
                    noteAsyncRejectionIfAny(code, reason)
                    listener.onUnparsed("CLOSING code=$code reason=$reason")
                    signalClosedOnce()
                }
                override fun onClosed(socket: WebSocket, code: Int, reason: String) { signalClosedOnce() }
                override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                    val body = runCatching { response?.body?.string() }.getOrNull()
                    Log.w(TAG, "Live socket failed: ${t.message} http=${response?.code} body=$body")
                    listener.onUnparsed("FAILURE ${t.message} http=${response?.code} body=$body")
                    signalClosedOnce()
                }
            },
        )
        return ws != null
    }

    /**
     * Dispatch one function call, with a DEFERRED acknowledgement.
     *
     * ### Why the ack moved
     *
     * It used to be sent up front, decided from the tool's NAME (`drive_phone` = long-running).
     * With one universal tool that no longer works: `ask_aura` covers both "what's the capital of
     * France" (about a second) and "order me a coffee" (a minute and a half), and only the handler
     * — after the brain lane has looked at the request — can tell which.
     *
     * So nothing is sent until the handler says it is escalating, via [EscalationSink]:
     *
     *  - **Never escalates** → one `functionResponse`, no `scheduling`. Exactly an ordinary
     *    blocking tool, which is what a question should feel like.
     *  - **Escalates** → SILENT ack at that moment, progress while it runs, then the real outcome
     *    with INTERRUPT so AURA reports the finish rather than sitting on it until a lull.
     *
     * The property that mattered is preserved: the model never sits with an unanswered call for
     * the length of a task. It now waits the length of one brain call before hearing *something*,
     * which is what every blocking tool already does.
     */
    private fun dispatchToolCall(socket: WebSocket, call: CompanionToolCall) {
        scope.launch {
            listener.onToolStarted(call.name)
            var escalated = false

            val sink = EscalationSink { label ->
                when {
                    // The endpoint rejected asynchronous function calling earlier in this process.
                    // The work should still happen; its result is simply delivered the old way, as
                    // this call's single reply. Degraded (the model waits in silence) but working.
                    !LiveTaskTracker.isAsyncSupported -> Escalation.BLOCKING

                    // Something long-running already holds the slot. Refuse rather than queue:
                    // two agent runs on one screen undo each other's navigation.
                    !tracker.onStarted(call.id, call.name, label) -> {
                        Log.w(TAG, "'${call.name}' tried to escalate while a task was in flight — refused")
                        Escalation.REFUSED
                    }

                    else -> {
                        escalated = true
                        sentEarlyAck.set(true)
                        // The brain lane must know a task is underway, or it will happily offer to
                        // start the same work again on the user's next sentence.
                        com.aura.aura_ui.agent.state.AuraStateStore.onTaskStarted(label)
                        buildEscalationFrames(call.id, call.name, label).forEach(socket::send)
                        Log.i(TAG, "'${call.name}' escalated and acked; task now running in background")
                        listener.onLongRunningStarted(label)
                        Escalation.ACKED
                    }
                }
            }

            try {
                val result = runCatching { tools.handle(call, sink) }
                    .getOrElse { CompanionToolResult("Tool ${call.name} failed: ${it.message}", isError = true) }
                if (escalated) {
                    buildFinishFrames(call.id, call.name, result.text).forEach(socket::send)
                } else {
                    socket.send(LiveProtocol.buildToolResponse(call.id, call.name, result.text))
                }
            } finally {
                // In a finally because a handler that throws AFTER escalating would otherwise
                // leave the tracker slot claimed forever — every later task silently refused,
                // with nothing in the logs to say why.
                if (escalated) {
                    tracker.onFinished()
                    com.aura.aura_ui.agent.state.AuraStateStore.onTaskFinished()
                    listener.onLongRunningFinished()
                }
                listener.onToolFinished(call.name)
            }
        }
    }

    /**
     * Phase 2 — push a live progress line into the model's context while a long task runs.
     *
     * `SILENT` on purpose: the model should KNOW where the task is without being pushed to narrate
     * every step aloud. That knowledge is what lets it answer "how's it going?" from fact instead of
     * inventing an answer. Throttled and de-duplicated by [LiveTaskTracker.shouldEmitProgress];
     * a no-op when nothing is running.
     */
    fun sendTaskProgress(text: String) {
        if (closedByUs.get()) return
        val flight = tracker.inFlight ?: return
        // Fed before the throttle: the store should hold the freshest progress even on the ticks
        // that are too close together to be worth telling the model about.
        com.aura.aura_ui.agent.state.AuraStateStore.onTaskProgress(text)
        if (!tracker.shouldEmitProgress(text)) return
        ws?.send(
            LiveProtocol.buildToolResponse(
                flight.callId,
                flight.name,
                "progress: $text",
                LiveTaskTracker.SCHEDULING_SILENT,
            ),
        )
    }

    /** True while a long-running tool call is in flight on this session. */
    val isTaskRunning: Boolean get() = tracker.isRunning

    /**
     * A session we acked early that the server then rejected as invalid (1007) is the one signal
     * that this model/endpoint does not honour asynchronous function calling. Demote once, process
     * wide, so the reconnect comes back on the blocking path instead of failing the same way
     * forever. Any other close code is ordinary link churn and must NOT demote.
     */
    private fun noteAsyncRejectionIfAny(code: Int, reason: String) {
        if (code != CLOSE_INVALID_ARGUMENT) return
        if (!sentEarlyAck.get()) return
        if (LiveTaskTracker.demoteToBlocking()) {
            Log.w(
                TAG,
                "server rejected a session that used async function calling (code=$code reason=$reason) — " +
                    "falling back to blocking tool responses for this process",
            )
        }
    }

    // Diagnostic: outbound backlog. OkHttp's send() keeps returning true while quietly queueing
    // up to 16 MB on a stalled socket — the user experiences that as their speech reaching the
    // server (and the transcript coming back) tens of seconds late. queueSize() is the truth.
    private var audioChunksSent = 0L

    fun sendAudio(pcm: ByteArray) {
        if (closedByUs.get()) return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val socket = ws
        val ok = socket?.send(LiveProtocol.buildRealtimeAudio(b64)) ?: false
        if (++audioChunksSent % QUEUE_LOG_EVERY == 0L) {
            val backlog = socket?.queueSize() ?: -1
            Log.d(TAG, "diag: sent=$audioChunksSent outboundQueue=${backlog}B")
        }
        if (!ok && !closedSignalled.get()) {
            Log.w(TAG, "sendAudio rejected — socket dead/saturated; signalling closed for reconnect")
            signalClosedOnce()
        }
    }

    /** Tell the server the mic stream paused (>1 s) so it flushes cached audio + VAD state. */
    fun sendAudioStreamEnd() { ws?.send(LiveProtocol.buildAudioStreamEnd()) }

    /**
     * Drive the session with a TEXT turn instead of audio. Same setup + tool loop as a spoken turn, so
     * the debug harness can exercise persona / tool-calling / drive_phone with a keyboard -- no mic, no
     * speaker, no overlay. Production audio still flows through [sendAudio]; this adds a path, changes none.
     */
    fun sendText(text: String) { ws?.send(LiveProtocol.buildClientContentText(text)) }

    fun updateSystemInstruction(text: String) {
        ws?.send(
            buildJsonObject {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                }
            }.toString(),
        )
    }

    fun close() {
        // Mark BEFORE closing: the close's own onClosing/onClosed callbacks must not
        // be forwarded as a failure (that feedback loop was the reconnect storm).
        closedByUs.set(true)
        ws?.close(1000, "bye")
        ws = null
    }

    private companion object {
        const val TAG = "LiveSession"
        const val QUEUE_LOG_EVERY = 25L   // one diag line per second at 40 ms chunks

        /**
         * WebSocket close code Google uses for "invalid argument" — the same code a pre-setup
         * client turn earns. If it lands on a session that used asynchronous function calling,
         * that is our evidence the endpoint does not support it. See [noteAsyncRejectionIfAny].
         */
        const val CLOSE_INVALID_ARGUMENT = 1007
        const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        // ONE client for all sessions: each OkHttpClient owns thread pools — the storm was
        // creating ~9/sec. pingInterval makes OkHttp detect a dead link and fire onFailure
        // instead of silently queueing outbound audio forever.
        val client: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(java.time.Duration.ofSeconds(20))
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build()
    }
}

/**
 * The frames one escalation puts on the wire, in the order they must be sent.
 *
 * ### Why this is a function and not two `socket.send` lines
 *
 * `dispatchToolCall` cannot be unit-tested without OkHttp and a live endpoint, so the dispatch
 * test used to re-implement the branch and assert against its own copy — green no matter what
 * production did. Everything about escalation that is worth pinning is pure (which frames, with
 * which `scheduling`, in which order), so it lives here and BOTH production and the test call it.
 *
 * Two frames, and the order is load-bearing:
 *
 *  1. **The ack** — `scheduling: SILENT`, which by contract means "absorb this fact, do not
 *     respond". It answers the outstanding call and states BOTH things the model needs: the task
 *     is running, and it does not have an outcome yet. Without the second half, "started" reads
 *     close enough to "done" for a model to report success.
 *  2. **The spoken turn** — the part the user actually hears. SILENT is doing exactly what it says
 *     on frame 1, so on its own it left AURA mute from the moment a task was accepted until the
 *     moment it landed: you spoke a task and got nothing back. This is a client turn rather than a
 *     different `scheduling` value because a `scheduling` enum is the wrong home for what to SAY —
 *     that belongs in [Persona] beside the other internal cues — and because this is the same
 *     mechanism the greeting and the spoken `ask_user` question already use.
 *
 * Both frames sit in the escalation branch, so a connection demoted to blocking function calls
 * returns before either of them and stays silent for the length of a task. Separate fix.
 *
 * Sending the spoken turn FIRST would open a new turn while a function call is still unanswered.
 *
 * Once-per-task is not this function's job: the caller reaches it only when
 * [LiveTaskTracker.onStarted] returned true, and that returns false while anything is in flight.
 */
internal fun buildEscalationFrames(callId: String, name: String, label: String): List<String> = listOf(
    LiveProtocol.buildToolResponse(callId, name, ackTextFor(label), LiveTaskTracker.SCHEDULING_SILENT),
    LiveProtocol.buildClientContentText(Persona.TASK_STARTED_TRIGGER + label),
)

/**
 * The frames a finished escalated task puts on the wire — the mirror of [buildEscalationFrames].
 *
 * The result used to go out as one `functionResponse` with `scheduling: INTERRUPT`, trusting the
 * server to turn it into speech. It sometimes didn't: the task landed and AURA said nothing. The
 * start of a task hit the same wall and was fixed with a client turn, so the end uses the same fix:
 * the result is absorbed SILENT (keeps the call's bookkeeping correct), then a client turn carrying
 * the result guarantees exactly one spoken report. Order matters for the same reason as the start.
 */
internal fun buildFinishFrames(callId: String, name: String, resultText: String): List<String> = listOf(
    LiveProtocol.buildToolResponse(callId, name, resultText, LiveTaskTracker.SCHEDULING_SILENT),
    LiveProtocol.buildClientContentText(Persona.TASK_FINISHED_TRIGGER + resultText),
)

/** @see buildEscalationFrames — frame 1's text. */
internal fun ackTextFor(label: String): String =
    "started: the phone task is now running in the background" +
        (if (label.isNotBlank()) " (\"$label\")" else "") +
        ". You do NOT have its result yet — do not say it is done, sent, played, ordered or " +
        "found. Keep talking normally; the real outcome will be delivered to you when it lands."
