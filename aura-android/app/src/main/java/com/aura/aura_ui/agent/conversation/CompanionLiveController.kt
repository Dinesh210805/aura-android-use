package com.aura.aura_ui.agent.conversation

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import com.aura.aura_ui.agent.ledger.ActiveRunRegistry
import com.aura.aura_ui.agent.ledger.ResumeOffer
import com.aura.aura_ui.agent.ledger.RunLedgerStore
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.agent.memory.EncryptedMemoryService
import com.aura.aura_ui.agent.memory.MemoryType
import com.aura.aura_ui.agent.voice.vad.SpeechDetectorFactory
import com.aura.aura_ui.agent.voice.vad.SpeechEvent
import com.aura.aura_ui.audio.PcmStreamPlayer
import com.aura.aura_ui.conversation.ConversationPhase
import com.aura.aura_ui.conversation.ConversationViewModel
import com.aura.aura_ui.mcp.bridge.AppDeviceBridge
import com.aura.aura_ui.mcp.bridge.AppDeviceContextProvider
import com.aura.aura_ui.mcp.bridge.AppDeviceControls
import com.aura.aura_ui.mcp.bridge.AppMediaBridge
import com.aura.aura_ui.overlay.AuraOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How far through AURA's reply the caption has been revealed.
 *
 * Pulled out of the pacer loop because it is the part that was WRONG, and a loop that reads a
 * live `AudioTrack` is not something a test can drive. The rule it encodes:
 *
 *  - **While the speaker is running**, the reveal tracks the playback head — the only clock that
 *    counts audio which genuinely came out of the speaker.
 *  - **Once the speaker has been released**, everything that was going to be spoken has been, so
 *    the reveal snaps to the end. This is the fix: `PcmStreamPlayer.playedSeconds()` reads the
 *    track's playback head and `stop()` releases the track, after which it returns 0f again —
 *    "nothing spoken yet", which is exactly backwards at the one moment it matters. The reveal
 *    collapsed to 0, `currentSentence` returned "", the blank was (correctly) never published,
 *    and the caption sat frozen on whatever the previous tick had put up. A real session left
 *    `AURA  Hey, Dines` on screen — one letter short of "Dinesh" — for four seconds.
 *  - **Before any audio has played**, the reveal stays where it is. Transcript deltas arrive
 *    before the first PCM frame, and `!playing` is true then too; snapping on that would dump
 *    the whole reply on screen ahead of the voice.
 *
 * Monotonic throughout: the drain restarts the player for the next turn and the playback head
 * starts from zero again, so a reveal that could move backwards would rewind mid-caption.
 */
internal object CaptionReveal {

    /**
     * Speaking rate used to turn heard-seconds into characters.
     *
     * ~15 chars/s is ordinary conversational English (about 150 wpm), which is where Gemini
     * Live sits. It is a calibration knob, not a derived constant: a real voice does not speak
     * at a fixed rate, and the only way to be exact would be per-word timings the API does not
     * send. Raise it if the caption lags the voice, lower it if it runs ahead.
     *
     * Being an estimate is survivable precisely BECAUSE of the snap above — an under-estimate
     * used to mean the caption never caught up with the last word of a sentence.
     */
    const val CHARS_PER_SECOND = 15f

    fun next(
        previous: Int,
        playing: Boolean,
        heardAnything: Boolean,
        playedSeconds: Float,
        fullLength: Int,
    ): Int = when {
        playing -> maxOf(previous, (playedSeconds * CHARS_PER_SECOND).toInt())
        // The voice is done but the text may not be. See [CATCH_UP_CHARS_PER_TICK].
        heardAnything -> maxOf(previous, minOf(fullLength, previous + CATCH_UP_CHARS_PER_TICK))
        else -> previous
    }

    /**
     * How fast the caption runs to the finish line once the speaker has fallen silent.
     *
     * This used to be an instant jump to `fullLength`, and the jump is what threw words
     * away. [CHARS_PER_SECOND] is an ESTIMATE, and an estimate that runs slow leaves the
     * reveal short of the text when the audio ends — so the jump landed the reveal on the
     * LAST sentence of the reply and every word still queued behind it was never drawn.
     * The user's report: *"the text is slower than voice a bit and because of that the last
     * few words are not even shown."*
     *
     * There is no way to remove the estimate. Live API sends utterance-level timing only —
     * no per-word timestamps — so reconciling at end-of-audio is not a workaround, it is
     * the design. What was wrong was reconciling by discarding rather than by catching up.
     *
     * 4 characters per 80 ms tick = 50 chars/s, a bit over 3× speaking rate: fast enough to
     * read as "the caption is finishing up" rather than a second paragraph, slow enough that
     * every word is actually on the glass. A 40-character tail clears in ~800 ms.
     *
     * Two properties this must keep, both pinned by [CaptionRevealTest]:
     *  - it always REACHES `fullLength` — the freeze it replaced was a caption stopping one
     *    letter short for four seconds, and a catch-up that could stall would be that bug;
     *  - it is a floor, never an assignment — `maxOf` keeps a reveal that already ran past a
     *    still-growing buffer from being dragged backwards.
     *
     * The dwell rides on top of this: `startTranscriptPacer` only calls a turn finished once
     * `spoken >= full.length`, so the 1.5 s the last sentence stays readable now begins after
     * the catch-up has landed rather than in place of it.
     */
    const val CATCH_UP_CHARS_PER_TICK = 4

    /**
     * The sentence containing the [spoken]th character, truncated to whole words.
     *
     * Cut on sentence enders rather than on a fixed length so the caption always begins at
     * a natural start. Falls back to the whole string when the reply has no punctuation yet,
     * which is the normal case for the first moments of a turn.
     *
     * Lives here rather than on the controller because it is pure, and because the
     * word-boundary rule below is the part that was wrong twice.
     */
    fun sentence(full: String, spoken: Int): String {
        if (full.isEmpty() || spoken <= 0) return ""
        // WHOLE WORDS ONLY. [spoken] is a character count derived from a speaking-rate
        // estimate, so it lands mid-word on most ticks: "one" spent 80 ms on screen as "on",
        // and at the end of a sentence it was the last word that stayed clipped. Cutting back
        // to the last space costs at most one word of lag and removes the artefact entirely.
        //
        // It is also what makes the strip look calm. Revealing by character updates the view
        // ~12×/s with a one-letter delta — visually, letters trickling. Revealing by word
        // updates it at the rate the voice actually produces words, which is the rhythm the
        // eye is already following.
        //
        // The tail is the exception: once the reveal has caught up with everything that has
        // arrived, the final word is complete by definition and holding it back would end
        // every sentence one word short — the same bug, moved.
        val end = when {
            spoken >= full.length -> full.length
            else -> full.lastIndexOf(' ', spoken - 1).coerceAtLeast(0)
        }
        if (end == 0) return ""
        val head = full.take(end)
        val startOfSentence = head.indexOfLast { it in SENTENCE_ENDERS }
            .let { if (it < 0) 0 else it + 1 }
        return head.substring(startOfSentence).trimStart()
    }

    /**
     * What ends a sentence, for deciding where the caption restarts.
     *
     * A String rather than a CharArray so a newline can be written as an escape without a
     * literal line break sitting inside a character literal.
     */
    const val SENTENCE_ENDERS = ".!?\n"
}

/**
 * BYOK Gemini Live voice — the audio body around [LiveSession]. Adapts GeminiLiveController's proven
 * machinery (VOICE_COMMUNICATION mic + hardware AEC, single-consumer PcmStreamPlayer drain, barge-in)
 * but targets the DIRECT BYOK transport with an in-process action plane (no backend). Drives
 * [ConversationViewModel] so the existing overlay UI renders unchanged.
 *
 * Turn model: mic streams continuously (server VAD owns turn boundaries); the speaker stays open with
 * AEC so the user can barge in. During a phone task the mic stays hot but is gated to speech only
 * ([TaskAudioGate]), so the user can still ask, correct or stop while automation noise is dropped.
 */
class CompanionLiveController(
    private val context: Context,
    private val viewModel: ConversationViewModel,
    private val scope: CoroutineScope,
    private val onAmplitude: (Float) -> Unit,
) {
    private val cfg = CompanionConfig(context)
    // Through the factory, not a bare constructor: that is what makes the reminders it saves ring.
    private val memory = com.aura.aura_ui.agent.memory.memoryService(context)

    /** Checks each extracted fact against what is already known before it lands. */
    private val factReconciler = brainFactReconciler(context, memory)
    private val tools = CompanionTools(
        memory, AuraAgentPhoneRunner(context),
        // The reasoning half. Live decides only "device knob or everything else"; this decides
        // what the user actually meant, and whether it needs the phone at all.
        brain = AuraBrainLane(context, memory),
        onTaskProgress = { progress ->
            scope.launch {
                // Chat agent-output area (visible when restored) AND the minimized pill (visible while
                // the task runs) — same surfaces the classic voice path drives during automation.
                viewModel.addAgentOutput("AURA", progress)
                AuraOverlayService.getInstance()?.updateLiveNotification("AURA", progress)
            }
            // …and, since the fix, a THIRD consumer: the model itself. Pushed as a SILENT function
            // response so AURA knows where the task actually is without being prompted to narrate
            // each step. This is what turns "is it done yet?" from a guess into a fact. Throttled
            // and de-duplicated inside LiveSession/LiveTaskTracker, so a chatty task cannot flood
            // the socket. Fire-and-forget: progress must never be able to break a run.
            runCatching { session?.sendTaskProgress(progress) }
                .onFailure { Log.w(TAG, "progress → model failed: ${it.message}") }
        },
        onEndConversation = { endConversationFromModel() },
        // The LIVE ledger of the running agent, straight from the registry P0 added.
        //
        // This used to scan the encrypted store for "newest un-finalized ledger started at or
        // after this task began" — a heuristic, because nothing outside a run could reach its
        // controller. It had two ways to be wrong: RunLedgerPersister debounces writes by 500 ms,
        // so a fresh answer could be read stale, and "un-finalized" is also the app-death signal,
        // so a different run could satisfy the same predicate. The registry hands us the exact
        // controller of the run that is executing, so both disappear — and no disk read is needed
        // to answer "how's it going?".
        taskStatus = TaskStatusProvider {
            ActiveRunRegistry.shared.current?.snapshot()?.let(TaskStatusSummary::of)
        },
        // Enough turns for the lane to resolve "it", "that one", "the second one" — not the whole
        // session, which would grow the lane's prompt without bound over a long conversation.
        recentTranscript = { transcript.recent(TRANSCRIPT_TURNS_FOR_LANE) },
        // Surfaced so the lane can offer to pick up an interrupted run WITH its ledger, rather
        // than starting the same goal again from scratch and throwing away its plan and findings.
        pendingResume = {
            withContext(Dispatchers.IO) {
                runCatching {
                    val store = RunLedgerStore(EncryptedJsonStore(context, RunLedgerStore.STORE_NAME))
                    ResumeOffer.from(store.latestResumable())?.voicePrompt()
                }.getOrNull()
            }
        },
    )

    // Fast lane: the four instant device controls Live dispatches itself. The router sends each
    // function call to whichever handler owns it — these, or CompanionTools for everything else.
    private val mediaBridge = AppMediaBridge(context)
    private val directTools = CompanionDirectTools(
        media = mediaBridge,
        deviceContext = AppDeviceContextProvider(context, mediaBridge),
        deviceControls = AppDeviceControls(context, AppDeviceBridge(context)),
    )
    private val toolRouter = CompanionToolRouter(primary = tools, direct = directTools)

    // P0 — automatic conversation memory: accumulate the session's turns, then at REAL session end
    // (cleanup only) summarize + persist an episode memory and durable facts so AURA remembers.
    // Durable conversation log (2026-08-05). The action plane has had a full on-disk trace
    // since Phase 10B; this plane had none, so a reported "AURA said it hit a snag while the
    // task was still running" could not be investigated at all. Writes through to the same
    // mcp_logs store under source="live".
    private val conversationLog = com.aura.aura_ui.mcp.log.LiveConversationLogger(context)
    private val transcript = SessionTranscript(sink = conversationLog::record)
    // Reuses the agent's configured brain (endpoint/key/model) for the text summary — not a hardcoded model.
    private val summarizer: ConversationSummarizer = AgentBrainSummarizer(context)
    // Survives cleanup()'s teardown of `scope`, so the end-of-session summary still lands.
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val summarized = AtomicBoolean(false)
    // Partial checkpointing: durable facts saved mid-session survive a drop. Dedup within a session
    // so a fact re-surfaced at a later checkpoint isn't saved twice. Touched only from persistScope.
    private val savedFacts = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val lastCheckpointTurn = java.util.concurrent.atomic.AtomicInteger(0)
    private val checkpointing = AtomicBoolean(false)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val player = PcmStreamPlayer()
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    // Drops the cancelled turn's audio after a barge-in — server-signalled (LV11) or local (LV14).
    private val interruptGate = InterruptGate()
    // On-device speech detection so barge-in does not depend on the server hearing the user
    // through AEC-suppressed mic audio (LV14). Silero, with an energy-RMS fallback.
    private val segmenter = SpeechDetectorFactory.create(context)
    /** Mid-task mic policy: speech reaches the model, automation noise does not. */
    private val taskGate = TaskAudioGate(chunkMs = CHUNK_MS)
    // When the current playback began — the AEC convergence guard measures against this.
    @Volatile
    private var playbackStartedAtMs = 0L

    private val sessionActive = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private val driving = AtomicBoolean(false)   // drive_phone in flight → mic paused
    private var recordingJob: Job? = null
    // Guards the drainJob slot + player ownership handoff (barge-in vs next-turn race).
    private val drainLock = Any()
    private var drainJob: Job? = null

    /** Reveals the model's transcript at the pace the speaker is actually saying it. */
    private var transcriptPacerJob: Job? = null

    /**
     * Show the sentence AURA is saying right now, revealed at the pace it is being said.
     *
     * ### Why this is paced at all
     *
     * The Live API offers nothing to synchronise against: output transcription carries no
     * timestamps, and text and audio arrive as independent streams. The model generates far
     * faster than realtime, so the entire reply lands seconds before the speaker finishes
     * it. Published as it arrives, the caption simply runs away from the voice.
     *
     * ### Why by TIME and not by proportion
     *
     * The first attempt revealed a fraction of the text equal to played-frames over
     * written-frames. That fraction is worthless early in a turn — audio arrives in chunks,
     * so after the first chunk very little is written and playback has nearly caught up
     * with it, giving ~1.0 while most of the sentence is still being generated. The caption
     * dumped everything immediately and then froze. [PcmStreamPlayer.playedSeconds] is
     * absolute and cannot do that: it only counts audio that actually left the speaker.
     *
     * ### Why a SENTENCE and not a scrolling window
     *
     * Keeping "the last N characters" made the caption page: three lines would fill, then
     * the window slid and the text appeared to restart from the top with the next three,
     * while the voice was still somewhere in the first. Anchoring on the sentence being
     * spoken removes the window entirely — the caption grows within one sentence, then
     * swaps to the next. Sentences are short enough to fit without paging, which is also
     * why the strip can stop truncating speech.
     */
    private fun startTranscriptPacer() {
        if (transcriptPacerJob?.isActive == true) return
        transcriptPacerJob = scope.launch {
            var finishedAtMs = 0L
            var lengthAtFinish = -1
            var revealed = 0
            var heardAnything = false
            while (isActive) {
                val full = captionBuf.toString()
                val playing = player.isPlaying
                if (playing) heardAnything = true
                revealed = CaptionReveal.next(
                    previous = revealed,
                    playing = playing,
                    heardAnything = heardAnything,
                    playedSeconds = player.playedSeconds(),
                    fullLength = full.length,
                )
                val spoken = revealed.coerceIn(0, full.length)

                // A BLANK caption is never published while the voice is still going.
                //
                // Empty ticks are routine, not exceptional: at the very start `playedSeconds`
                // is still 0, and every time the reveal point crosses a full stop the new
                // sentence is momentarily zero characters long. Holding the previous text
                // through those gaps is what keeps one utterance readable end to end.
                //
                // This used to be load-bearing for a second reason — a blank detached the whole
                // status strip, so one empty tick took the window with it. It no longer does:
                // `AgentStatusOverlay` keeps the window for as long as it is enabled and only
                // dims it, after a linger. This guard is now about readability alone, which is
                // why it is safe here and was never safe as the only copy.
                val caption = CaptionReveal.sentence(full, spoken)
                if (caption.isNotBlank()) {
                    com.aura.aura_ui.services.AgentStatusRegistry.setSpoken(caption)
                }

                // Done when the speaker has fallen silent AND the caption has caught up with
                // everything that arrived. Then hold briefly so the last words are readable,
                // and clear — a caption that outlives the voice is the thing that made the
                // strip look stuck.
                val finished = !playing && heardAnything && spoken >= full.length
                if (finished) {
                    // AUDIO STOPPING IS NOT THE SAME AS THE TURN ENDING. The drain releases the
                    // player after DRAIN_IDLE_GRACE_MS (300 ms) of no chunks, which a network
                    // hiccup can trigger mid-sentence — and the snap above makes that look
                    // exactly like a finished turn. Clearing then would wipe the caption while
                    // AURA is still talking and restart it from the top of `modelBuf` on the
                    // next delta: the reported bug, in a rarer path.
                    //
                    // Growing text is the tell. Transcript deltas run AHEAD of the audio, so a
                    // turn that is merely starved is still producing them, while a turn that is
                    // genuinely over has stopped. Restarting the dwell on any growth means the
                    // clear needs 1.5 s of BOTH silence and no new text.
                    if (finishedAtMs == 0L || full.length != lengthAtFinish) {
                        finishedAtMs = System.currentTimeMillis()
                        lengthAtFinish = full.length
                    }
                    if (System.currentTimeMillis() - finishedAtMs >= CAPTION_DWELL_MS) {
                        stopTranscriptPacer(clear = true)
                        return@launch
                    }
                } else {
                    finishedAtMs = 0L
                }
                delay(TRANSCRIPT_TICK_MS)
            }
        }
    }

    /** Stop pacing and drop whatever was on screen — the turn is over or was cut short. */
    private fun stopTranscriptPacer(clear: Boolean) {
        transcriptPacerJob?.cancel()
        transcriptPacerJob = null
        captionBuf.setLength(0)
        if (clear) com.aura.aura_ui.services.AgentStatusRegistry.setSpoken("")
    }
    // Reconnect discipline: one reconnect in flight, drop streak resets on setupComplete.
    private val reconnecting = AtomicBoolean(false)
    private val consecutiveDrops = java.util.concurrent.atomic.AtomicInteger(0)
    // Greeting must wait for the server's SetupComplete: streaming mic audio (startCapture) or a
    // text turn BEFORE setup completes makes Google reject the whole session with close 1007
    // ("invalid argument"). These gate greet() so it fires exactly once, only after setup.
    private val setupDone = AtomicBoolean(false)
    private val greetRequested = AtomicBoolean(false)
    private val greeted = AtomicBoolean(false)
    private var resumeHandle: String? = null
    private var resolvedModel: String? = null
    private var session: LiveSession? = null

    /** Set once the model calls `end_conversation`; guards against a double teardown. */
    private val endRequested = AtomicBoolean(false)

    // Live sends transcripts as incremental DELTAS; the chat bubbles use replace-semantics (each call
    // sets the full text). So accumulate per turn and hand the ViewModel the running total. Touched only
    // from the single WS-callback thread, so a plain StringBuilder is safe.
    private val userBuf = StringBuilder()
    private val modelBuf = StringBuilder()

    /**
     * The caption's own copy of the reply. NOT [modelBuf].
     *
     * `finalizeTurn` empties `modelBuf` the instant `generationComplete` lands — and the model
     * finishes GENERATING seconds before the speaker finishes SAYING it, which is the premise
     * the whole pacer is built on. A caption reading `modelBuf` therefore went blank mid-turn,
     * froze on whatever prefix it had last published, and got cleared by the dwell while AURA
     * was still talking. Measured on a real session: last caption 10:00:00.959, `gen=true`
     * 10:00:01.020, audio still playing until 10:00:07.
     *
     * Owned by the pacer's lifecycle instead: written by the model deltas, emptied only by
     * [stopTranscriptPacer]. Same single WS-callback thread as the buffers above.
     */
    private val captionBuf = StringBuilder()

    // Did each side actually make a sound this turn? Set by the local VAD (user) and by
    // arriving audio frames (AURA), cleared at every turn boundary. Without them, an empty
    // buffer at turn end is ambiguous between "this speaker never took a turn" and "they
    // spoke and the transcription was lost" — and the log recorded both as the latter,
    // which is how the first real Live session came back 11 gaps in 26 entries. Touched
    // from the WS-callback thread and the mic thread, hence atomic.
    private val userSpokeThisTurn = AtomicBoolean(false)
    private val modelSpokeThisTurn = AtomicBoolean(false)

    val isSessionActive: Boolean get() = sessionActive.get()

    private val listener = object : LiveSessionListener {
        override fun onUserTranscript(text: String) {
            userBuf.append(text)
            val full = userBuf.toString()
            // The status strip shows the barge-in as it arrives. Published from the growing
            // buffer, not the finalized turn: the whole point is that the user sees their
            // interruption register WHILE they are still talking.
            com.aura.aura_ui.services.AgentStatusRegistry.setHeard(full)
            ui { viewModel.startOrUpdateStreamingUserMessage(full); viewModel.updatePartialTranscript(full) }
        }
        override fun onModelTranscript(text: String) {
            // The user finished speaking once the model starts replying — lock their bubble in.
            // The user's turn is now committed to the log, so clear the "they spoke" flag —
            // otherwise the turn boundary below would see an empty buffer plus live evidence
            // of speech and record a gap for words that were in fact captured here.
            if (userBuf.isNotEmpty()) {
                val u = userBuf.toString(); userBuf.clear(); userSpokeThisTurn.set(false)
                transcript.addUser(u); ui { viewModel.finalizeStreamingUserMessage(u) }; maybeCheckpoint()
                // While the agent is suspended on ask_user, what the user just SAID is the answer.
                // Routed to the same broker the tapped chip and the typed line use, so the three
                // input modes resolve one question exactly once (answer() is synchronized and
                // rejects a stale id). Without this the voice lane could hear the question and
                // have no way to answer it — see AuraOverlayService.answerPendingQuestion.
                answerPendingQuestion(u)
            }
            // An empty modelBuf means finalizeTurn already ran, so this delta opens a NEW
            // turn: retire the previous turn's pacer (which may still be sitting in its dwell
            // with a stale `revealed`, and would otherwise dump this reply unpaced) and start
            // the caption over. Not `clear = true` — the old sentence stays up until the new
            // one has something to say, which is what stops the strip blinking between turns.
            if (modelBuf.isEmpty()) stopTranscriptPacer(clear = false)
            modelBuf.append(text)
            captionBuf.append(text)
            // Live speaks through PcmStreamPlayer, not AuraTTSManager, so the strip would
            // otherwise show nothing for the whole Live plane. Paced against the audio rather
            // than published outright — see [startTranscriptPacer].
            startTranscriptPacer()
            val full = modelBuf.toString()
            ui { viewModel.startOrUpdateStreamingAiMessage(full); viewModel.updatePartialTranscript("") }
        }
        override fun onAudio(pcm: ByteArray) {
            // LV11: frames of a turn the user already barged in on are still in flight when
            // `interrupted` arrives. Accepting them here would restart the drain and let the
            // dead turn keep talking, so drop everything inside the post-interrupt window.
            if (interruptGate.shouldDrop(System.currentTimeMillis())) return
            // Audio that will actually be played is the evidence that AURA spoke this turn —
            // so a missing model transcript afterwards is a real gap, not an unused turn.
            modelSpokeThisTurn.set(true)
            audioQueue.offer(pcm); ensureDrain()
        }
        override fun onInterrupted() {
            // Barge-in kills the audio mid-sentence, so the caption for words that will now
            // never be spoken has to go with it — leaving it up would show AURA saying
            // something the user cut off and never heard.
            stopTranscriptPacer(clear = true)
            // Barge-in: cut the speaker NOW. interrupt() (pause+flush) discards the committed
            // buffer instead of playing it out; all under drainLock so a stale drain job can't
            // race the next turn's player. userBuf is NOT cleared — the transcription deltas of
            // the barge-in utterance itself may already be accumulating there.
            interruptGate.onInterrupted(System.currentTimeMillis())
            synchronized(drainLock) {
                audioQueue.clear()
                drainJob?.cancel(); drainJob = null
                player.interrupt()
            }
            val spoken = modelBuf.toString(); modelBuf.clear()
            transcript.addModel(spoken, spoke = modelSpokeThisTurn.getAndSet(false))
            ui {
                // Server history keeps what was already spoken — lock the partial bubble in
                // rather than deleting it, so chat matches what the user actually heard.
                if (spoken.isNotBlank()) viewModel.finalizeStreamingAiMessage(spoken)
                viewModel.updatePhase(ConversationPhase.LISTENING)
            }
        }
        // Native-audio models signal end-of-turn with generationComplete (NOT turnComplete); finalize
        // on whichever arrives so the AI bubble closes and the next turn gets a fresh bubble.
        // The killed turn is over — reopen the audio gate immediately so the NEXT reply is never
        // clipped by the local barge-in backstop (LV14).
        override fun onGenerationComplete() { interruptGate.clear(); finalizeTurn() }
        override fun onTurnComplete() { interruptGate.clear(); finalizeTurn() }
        // Task mode is driven by ESCALATION, not by tool name. With one universal tool the name
        // no longer tells a phone task from a spoken answer — keying off it would minimise the
        // chat window every time the user asked a question.
        override fun onLongRunningStarted(label: String) { enterTaskMode() }
        override fun onLongRunningFinished() { exitTaskMode() }
        override fun onToolCancelled(ids: List<String>) { exitTaskMode() }
        override fun onResumeHandle(handle: String?) { resumeHandle = handle }
        override fun onUsage(total: Int) { Log.d(TAG, "live usage: $total tokens") }
        // A session that completed setup is a healthy link — reset the drop streak. Now the server
        // is ready for client turns, so a greeting requested during connect can safely fire.
        override fun onSetupComplete() {
            consecutiveDrops.set(0)
            setupDone.set(true)
            if (greetRequested.get()) performGreeting()
        }
        override fun onGoAway() { scheduleReconnect() }
        override fun onClosed() { scheduleReconnect() }
    }

    /** Connect (mints an ephemeral token first; falls back to the raw key). Returns false if no key. */
    /**
     * The opening briefing ([GreetingBriefing]). "First today" is decided once per calendar day and
     * cached for the controller's life, so a mid-session reconnect keeps the same answer.
     */
    private fun briefing(now: Long): String? {
        val today = java.time.LocalDate.now().toString()
        val first = firstToday ?: run {
            val prefs = context.getSharedPreferences(GREETING_PREFS, Context.MODE_PRIVATE)
            (prefs.getString(KEY_LAST_GREET_DAY, null) != today).also {
                prefs.edit().putString(KEY_LAST_GREET_DAY, today).apply()
            }
        }.also { firstToday = it }
        return GreetingBriefing.build(
            notable = com.aura.aura_ui.agent.state.AuraStateStore.current().notableFacts(),
            history = memory.allEntries(),
            pending = memory.pendingCommitments(),
            nowEpochMs = now,
            firstToday = first,
        )
    }

    private var firstToday: Boolean? = null

    suspend fun connect(): Boolean {
        val key = cfg.byokKey() ?: return false
        // User's explicit picker choice wins if set; otherwise ask the key which Live model it
        // actually has (free tier / preview-id churn) and fall back to the configured default.
        // Resolve once and cache so reconnects reuse it (a mid-session picker change takes effect
        // on the next fresh connect, same as any other config the controller reads at connect time).
        val model = (resolvedModel ?: cfg.liveModel ?: LiveModelResolver.resolve(key) ?: CompanionConfig.LIVE_MODEL)
            .also { resolvedModel = it }
        Log.i(TAG, "Live model: $model")
        // Connect with the raw BYOK key (v1beta ?key=). Ephemeral tokens need the separate v1alpha
        // BidiGenerateContentConstrained endpoint + ?access_token= — deferred; the key is already on
        // the user's own device, so the security delta is marginal here.
        val connectKey = key
        val config = LiveSessionConfig(
            model = model,
            voice = cfg.voice(),
            systemInstruction = CompanionPromptAssembler(
                memory,
                ownerName = PersonaOwner.get(context),
                // Same profile the agent lane renders into its system prompt - one source of
                // truth for "what may AURA speak", so the two planes cannot disagree.
                languages = runCatching {
                    com.aura.aura_ui.agent.memory.UserProfileStore(context).load().languages
                }.getOrDefault(emptyList()),
            ).assemble(
                System.currentTimeMillis(),
                // Build 3: surface any interrupted run so the model can proactively offer to resume.
                pendingResume = runCatching {
                    val store = RunLedgerStore(EncryptedJsonStore(context, RunLedgerStore.STORE_NAME))
                    ResumeOffer.from(store.latestResumable())?.voicePrompt()
                }.getOrNull(),
                briefing = runCatching { briefing(System.currentTimeMillis()) }.getOrNull(),
            ),
            tools = LiveToolDeclarations.functionDeclarations(),
            triggerTokens = CompanionConfig.TRIGGER_TOKENS,
            slidingWindow = CompanionConfig.SLIDING_WINDOW,
            // Language is deliberately never pinned. Blank omits the field entirely (see
            // LiveProtocol), which is what lets the model follow a code-mixed speaker — Tanglish,
            // Hinglish — sentence by sentence. Pinning en-US on the half-cascade path used to fight
            // exactly that, and the persona now asks the model to mirror the user's language, so a
            // fixed code here would contradict the instruction. Native-audio also rejects
            // thinkingConfig, hence the separate guard below.
            languageCode = "",
            thinkingLevel = if (model.contains("native-audio")) "" else CompanionConfig.THINKING_LEVEL,
            silenceDurationMs = CompanionConfig.SILENCE_DURATION_MS,
            resumeHandle = resumeHandle,
        )
        session = LiveSession(connectKey, config, toolRouter, scope, listener)
        return session?.connect() ?: false
    }

    /** Begin the continuous session: mic streams until [cancelCapture]. */
    fun startCapture() {
        sessionActive.set(true)
        consecutiveDrops.set(0)
        // A stale drop window from a previous barge-in must never mute a fresh session's first reply.
        interruptGate.reset()
        // Fresh conversation → clear the transcript + re-arm the summarize/checkpoint guards.
        // The LOG is not cleared — reset() is a summarizer-scope operation, and a new
        // conversation opens a new logged session rather than erasing the last one.
        conversationLog.startSession(modelLabel = "Gemini Live")
        transcript.note(speaker = "system", text = "Live session started", kind = "lifecycle")
        transcript.reset()
        summarized.set(false)
        savedFacts.clear()
        lastCheckpointTurn.set(0)
        startMic()
    }

    fun stopCapture() {
        stopMic()
        // Mic pausing >1 s while the session stays up requires audioStreamEnd so the server
        // flushes cached audio + VAD state (else the next turn misbehaves).
        session?.sendAudioStreamEnd()
        ui { viewModel.updatePhase(ConversationPhase.THINKING) }
    }

    fun cancelCapture() {
        sessionActive.set(false); driving.set(false)
        stopMic()
        synchronized(drainLock) {
            audioQueue.clear()
            drainJob?.cancel(); drainJob = null
            player.interrupt()   // user hit stop — cut the speaker now, don't drain the buffer
        }
        clearCommRouting()
        onAmplitude(0f)
        ui { viewModel.updatePhase(ConversationPhase.IDLE); viewModel.updatePartialTranscript("") }
    }

    fun sendTextCommand(text: String) { session?.sendText(text) }

    /**
     * Have the Live model ask the user a question the AGENT needs answered, in its own voice.
     *
     * Sent as a sentinel-prefixed internal turn, the same shape as [Persona.GREETING_TRIGGER]:
     * the persona instruction tells the model to relay what follows the marker and then stop and
     * listen, rather than answering it itself or reading the marker aloud.
     *
     * Capture starts first, exactly as the greeting does — the user will reply immediately, and a
     * mic that opens after the question has been asked misses the beginning of the answer.
     *
     * The reply comes back through the ordinary transcript path and is routed to the broker at
     * the user-turn boundary above, so nothing here has to wait for or correlate an answer.
     */
    fun askUserAloud(question: String) {
        startCapture()
        runCatching { session?.sendText(Persona.ASK_USER_TRIGGER + question) }
            .onFailure { Log.w(TAG, "ask_user relay failed: ${it.message}") }
    }

    /**
     * Resolve a pending `ask_user` question with what the user just said.
     *
     * Contained: HITL is never load-bearing for the conversation itself, and a broker that has
     * already been answered by a tap must not take the voice lane down with it.
     */
    private fun answerPendingQuestion(spoken: String) {
        runCatching {
            val pending = com.aura.aura_ui.agent.hitl.AskUserBroker.shared.pending.value ?: return
            if (spoken.isBlank()) return
            if (com.aura.aura_ui.agent.hitl.AskUserBroker.shared.answer(pending.id, spoken.trim())) {
                Log.i(TAG, "ask_user answered by voice: \"${spoken.trim()}\"")
            }
        }.onFailure { Log.w(TAG, "ask_user voice answer failed: ${it.message}") }
    }

    /**
     * Request a proactive opening greeting for this fresh session (wake word or manual overlay
     * open) so AURA comes alive instead of sitting silent. The greeting is model-generated
     * ([Persona.GREETING_TRIGGER]) so it stays warm, varied, and in the Live voice.
     *
     * Deferred until the server's SetupComplete: [connect] returns as soon as the socket opens,
     * but sending mic audio or a text turn before setup completes makes Google reject the session
     * (close 1007). So this only records the request; [performGreeting] runs it once setup lands
     * (or immediately if setup already completed).
     */
    fun greet() {
        greetRequested.set(true)
        if (setupDone.get()) performGreeting()
    }

    /**
     * Actually speak the greeting: start mic capture FIRST so the user can barge in over it or
     * answer immediately (the greeting is an ordinary model turn, so the existing barge-in path
     * interrupts it for free), then send the greeting trigger. Once-only across a session (a
     * reconnect's second SetupComplete must not re-greet). Fire-and-forget on the send.
     */
    private fun performGreeting() {
        if (!greeted.compareAndSet(false, true)) return
        startCapture()
        runCatching { session?.sendText(Persona.GREETING_TRIGGER) }
            .onFailure { Log.w(TAG, "greeting send failed: ${it.message}") }
    }

    /**
     * Cancel ONLY the in-flight drive_phone task (pill Cancel button). The Live
     * conversation stays up: the tool returns an honest "cancelled" result, the
     * model can speak to it, and the run's ledger stays resumable. Returns
     * false when no task is running (caller falls back to session teardown).
     */
    fun cancelTask(): Boolean = tools.cancelCurrentTask()

    fun cleanup() {
        summarizeSession()
        // Close the logged session here rather than in stopCapture(): cleanup() is the REAL
        // end of a conversation (stopCapture also fires between turns), and a log that
        // closed on every pause would fragment one conversation across a dozen sessions.
        transcript.note(speaker = "system", text = "Live session ended", kind = "lifecycle")
        conversationLog.endSession("closed")
        cancelCapture(); session?.close(); session = null
    }

    /**
     * The model called `end_conversation` — the user signed off.
     *
     * Teardown is DEFERRED until the farewell has actually been heard. The tool result is produced
     * synchronously (so the model's turn can complete), but tearing the session down right here
     * would cut AURA off mid-goodbye, since its audio is still draining through [player]. So we
     * wait for the speaker to go quiet, bounded by [END_FAREWELL_TIMEOUT_MS] in case the turn never
     * lands cleanly.
     *
     * A phone task still in flight is deliberately left ALONE: ending the conversation ends the
     * conversation, not the user's work. The run keeps going and owns the notification pill from
     * here, exactly as it does when the overlay is minimised — so signing off mid-task never
     * silently discards it.
     */
    private fun endConversationFromModel() {
        if (!endRequested.compareAndSet(false, true)) return
        Log.i(TAG, "end_conversation — closing the session once the farewell finishes")
        scope.launch {
            val deadline = System.currentTimeMillis() + END_FAREWELL_TIMEOUT_MS
            // Let the goodbye play out: wait for the audio queue to drain and the player to stop.
            while (System.currentTimeMillis() < deadline &&
                (player.isPlaying || audioQueue.isNotEmpty())
            ) {
                delay(END_FAREWELL_POLL_MS)
            }
            cleanup()
            // Dismiss the overlay and re-arm the wake word. Guarded: with no service instance
            // (headless test harness) the session teardown above is still the meaningful part.
            withContext(Dispatchers.Main) {
                runCatching { AuraOverlayService.getInstance()?.endVoiceConversation() }
                    .onFailure { Log.w(TAG, "overlay dismiss failed: ${it.message}") }
            }
        }
    }

    /**
     * Mid-session checkpoint: every [CHECKPOINT_EVERY_TURNS] user turns, extract durable facts from
     * the conversation-so-far and persist the NEW ones immediately. This is what lets important info
     * survive a connection DROP — we don't wait for a clean end, and we store only facts (not the
     * whole context). Single-flight; dedup against facts already saved this session.
     */
    private fun maybeCheckpoint() {
        val turns = transcript.userTurnCount()
        if (turns - lastCheckpointTurn.get() < CHECKPOINT_EVERY_TURNS) return
        if (!checkpointing.compareAndSet(false, true)) return
        lastCheckpointTurn.set(turns)
        val rendered = transcript.render()
        persistScope.launch {
            try {
                runCatching { persistFacts(summarizer.extractFacts(rendered)) }
                    .onFailure { Log.w(TAG, "checkpoint failed: ${it.message}") }
            } finally {
                checkpointing.set(false)
            }
        }
    }

    /**
     * End-of-session (real teardown OR a link that gave up): summarize the conversation and persist
     * an episode memory plus any not-yet-saved durable facts. One-shot (guards a double cleanup /
     * cleanup racing a give-up), min-length guarded, launched on [persistScope] so it survives the
     * teardown [cleanup] performs on [scope].
     */
    private fun summarizeSession() {
        if (!summarized.compareAndSet(false, true)) return
        if (transcript.userTurnCount() < MIN_TURNS_TO_REMEMBER) return
        val rendered = transcript.render()
        persistScope.launch {
            runCatching {
                summarizer.summarize(rendered)?.let { s ->
                    memory.appendEpisode(s.summary)
                    persistFacts(s.facts)
                    persistStyle(s.style)
                }
            }.onFailure { Log.w(TAG, "session summarize failed: ${it.message}") }
        }
    }

    /** Save durable facts not already stored this session (in-session dedup; cap per batch). */
    private suspend fun persistFacts(facts: List<String>) {
        facts.take(MAX_FACTS)
            .filter { savedFacts.add(it) }   // add() is false if already present → skip
            .forEach { factReconciler.reconcile(it) }
    }

    /**
     * Persist how the user speaks. Unlike facts these are not deduped in-session: a restatement is
     * the point — [MemoryService.save] merges near-identical notes and bumps their confirmation
     * count, so a style the user shows session after session outranks a one-off observation.
     */
    private suspend fun persistStyle(notes: List<String>) {
        notes.take(MAX_STYLE_NOTES).forEach { memory.save(MemoryType.STYLE, it) }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Close out the current turn's bubbles with the accumulated text. Idempotent: a second call
     *  (e.g. generationComplete then turnComplete) is a harmless no-op (buffers already cleared). */
    private fun finalizeTurn() {
        val u = userBuf.toString(); val m = modelBuf.toString()
        userBuf.clear(); modelBuf.clear()
        // Consume both flags on EVERY turn boundary, even when nothing gets recorded, so a
        // "the user spoke" from this turn can never leak forward and make the next turn's
        // silence look like a lost transcription.
        val userSpoke = userSpokeThisTurn.getAndSet(false)
        val modelSpoke = modelSpokeThisTurn.getAndSet(false)
        // No early return on two blank buffers: a side that made noise without producing text
        // is exactly the absence this log exists to record, and addUser/addModel now drop the
        // turns where nothing happened at all.
        transcript.addUser(u, spoke = userSpoke)
        transcript.addModel(m, spoke = modelSpoke)
        ui {
            if (u.isNotBlank()) viewModel.finalizeStreamingUserMessage(u)
            if (m.isNotBlank()) viewModel.finalizeStreamingAiMessage(m)
            viewModel.updatePartialTranscript("")
        }
    }

    /**
     * Single-flight, backed-off reconnect (field incident 2026-07-15: overlapping reconnects
     * closed each other's fresh sessions → ~9 sessions/sec storm; user speech arrived 30-40 s
     * late). Only ONE reconnect runs at a time; the first drop retries immediately (server
     * resets are routine, resumption handle keeps context), repeated drops back off; a dead
     * link gives up with a visible error instead of storming.
     */
    private fun scheduleReconnect() {
        if (!sessionActive.get()) return
        if (driving.get()) {
            // A reconnect mid-task cannot deliver the pending functionResponse for the call the
            // task belongs to, so we deliberately do not attempt one. But since the mic now streams
            // continuously through a task, the user is likely mid-sentence — and silence would read
            // as "the mid-task listening feature is broken" rather than "the link dropped". Say so
            // on the pill, which is the surface they are actually looking at while minimized.
            Log.w(TAG, "live link dropped during a task — not reconnecting; task continues")
            ui {
                AuraOverlayService.getInstance()
                    ?.updateLiveNotification("AURA", "Lost the voice connection — still working on your task")
            }
            return
        }
        if (!reconnecting.compareAndSet(false, true)) return
        scope.launch {
            try {
                val drops = consecutiveDrops.incrementAndGet()
                if (ReconnectPolicy.shouldGiveUp(drops)) {
                    Log.w(TAG, "live link dropped $drops times in a row — giving up")
                    summarizeSession()   // a dropped-out session must still persist its memory
                    cancelCapture()
                    ui { viewModel.setError("I keep losing the connection. Check the internet and tap the mic to retry.") }
                    return@launch
                }
                delay(ReconnectPolicy.delayForMs(drops - 1))
                if (!sessionActive.get()) return@launch
                session?.close(); session = null
                if (connect()) startMic() else Log.w(TAG, "reconnect attempt failed (drop streak $drops)")
            } finally {
                reconnecting.set(false)
            }
        }
    }

    private fun startMic() {
        // `driving` is deliberately NOT a bar any more: a task no longer means a dead mic, it means
        // a GATED one ([TaskAudioGate], applied in streamMic). Keeping the old guard here would
        // silently defeat mid-task listening whenever the mic had to be (re)started during a run.
        if (recording.get() || session == null) return
        try {
            ensureCommRouting()
            val chunk = (SAMPLE_RATE * CHUNK_MS / 1000) * 2
            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT), chunk,
            )
            @Suppress("MissingPermission")
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, CHANNEL, FORMAT, bufferSize,
            )
            val sid = record.audioSessionId
            if (sid != AudioManager.ERROR && AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sid)?.enabled = true
            }
            record.startRecording()
            recording.set(true)
            ui { viewModel.updatePhase(ConversationPhase.LISTENING) }
            recordingJob = scope.launch(Dispatchers.IO) { streamMic(record, chunk) }
        } catch (e: Exception) {
            Log.e(TAG, "startMic failed: ${e.message}", e)
        }
    }

    private suspend fun streamMic(record: AudioRecord, chunk: Int) {
        val buffer = ByteArray(chunk)
        segmenter.reset()
        while (recording.get()) {
            val n = record.read(buffer, 0, buffer.size)
            if (n <= 0) continue
            onAmplitude(amplitude(buffer, n))
            // LV14: on-device barge-in. Google's server VAD cannot hear the user while AURA is
            // speaking (hardware AEC suppresses the mic against the far-end reference), which is
            // why a real session logs `int=false` on every frame and the model talks straight
            // through an interruption. The cascade voice path already proved Silero DOES hear the
            // user through playback, so detect the barge-in here and cut the speaker locally.
            val events = segmenter.acceptPcm(buffer, n)
            val chunk = buffer.copyOf(n)
            if (driving.get()) {
                // Mid-task: the phone is being driven, so the mic is hearing app sounds, keyboard
                // clicks and notification chimes as well as the user. Forward speech only — see
                // [TaskAudioGate]. Outside a task everything goes, unchanged: the server's own VAD
                // owns turn-taking and second-guessing it would only add latency.
                //
                // Order matters: the gate runs BEFORE the barge-in decision so both come from the
                // SAME judgement about what is speech. Previously barge-in read the raw segmenter
                // events directly, so mid-task we gated what reached the server but not what cut
                // AURA off — a video the agent had just started could keep killing the speaker
                // while none of that audio was being forwarded. One decision, used twice.
                taskGate.accept(chunk, events).forEach { session?.sendAudio(it) }
                if (taskGate.isOpen && events.any { it is SpeechEvent.SpeechStart }) onLocalSpeechStart()
            } else {
                for (event in events) {
                    if (event is SpeechEvent.SpeechStart) onLocalSpeechStart()
                }
                session?.sendAudio(chunk)
            }
        }
        runCatching { record.stop(); record.release() }
    }

    /**
     * Sustained on-device speech. Only a barge-in when the model is actually speaking — otherwise
     * this is a normal turn and the server's own VAD owns the turn boundary, unchanged.
     */
    private fun onLocalSpeechStart() {
        // Recorded before the barge-in gate below: Silero heard the user either way, and that
        // is what makes a later empty transcription a genuine gap rather than a non-turn.
        userSpokeThisTurn.set(true)
        if (!player.isPlaying) return
        val now = System.currentTimeMillis()
        // Hardware AEC needs a moment to converge after playback starts; onsets inside that window
        // are residual echo of AURA's own first syllable, not the user (ported from the cascade path).
        val since = now - playbackStartedAtMs
        if (since < AEC_CONVERGENCE_MS) {
            Log.d(TAG, "barge-in ignored (AEC convergence window, ${since}ms)")
            return
        }
        Log.i(TAG, "🗣️ local barge-in — cutting the speaker, user has the floor")
        // Logged because a cut-off reply is one of the ways AURA "goes quiet" from the
        // user's side, and without a record it is indistinguishable from a dropped turn.
        transcript.note(speaker = "system", text = "user barge-in — AURA cut off mid-reply", kind = "interrupted")
        // Mute the dead turn until its generationComplete lands (or the backstop expires): the
        // server has not been told anything yet and keeps streaming audio we must not play.
        interruptGate.onLocalBargeIn(now)
        synchronized(drainLock) {
            audioQueue.clear()
            drainJob?.cancel(); drainJob = null
            player.interrupt()
        }
        val spoken = modelBuf.toString(); modelBuf.clear()
        transcript.addModel(spoken, spoke = modelSpokeThisTurn.getAndSet(false))
        ui {
            if (spoken.isNotBlank()) viewModel.finalizeStreamingAiMessage(spoken)
            viewModel.updatePhase(ConversationPhase.LISTENING)
        }
    }

    private fun stopMic() {
        recording.set(false)
        recordingJob?.cancel(); recordingJob = null
        onAmplitude(0f)
    }

    /**
     * A phone task started. The chat MINIMIZES to the pill (the agent needs the screen it is about
     * to drive) and progress renders there — but the microphone now STAYS OPEN.
     *
     * It used to be switched off for the whole task so automation noise could not reach the model.
     * That left the user unable to ask how it was going, correct a wrong turn, or say stop, while
     * `drive_phone`'s NON_BLOCKING declaration let the model keep talking — AURA could speak, the
     * user could not. What made the pause unnecessary is [TaskAudioGate]: the mic stays hot and
     * only speech is forwarded, so taps, chimes and app audio are dropped before they reach the
     * server's VAD.
     *
     * No `audioStreamEnd` here any more either: that frame exists to tell the server the mic
     * *paused*. Sending it while we intend to keep streaming would flush the VAD state we are
     * about to keep using.
     */
    private fun enterTaskMode() {
        driving.set(true)
        taskGate.reset()
        // LISTENING, not THINKING: the mic really is live, and the phase drives the waveform the
        // user reads as "it can hear me". Claiming THINKING here would be the UI telling a lie.
        ui {
            viewModel.updatePhase(ConversationPhase.LISTENING)
            viewModel.addAgentOutput("AURA", "Working on it…")
            AuraOverlayService.minimize(context)
            AuraOverlayService.getInstance()?.updateLiveNotification("AURA", "Working on it…")
        }
        // The mic is normally already running; start it if the session was idle when the task began
        // (e.g. a task issued from a text turn rather than speech).
        if (sessionActive.get() && !recording.get()) startMic()
    }

    /** Task finished: restore the chat and drop back to ungated streaming. */
    private fun exitTaskMode() {
        if (!driving.getAndSet(false)) return
        taskGate.reset()
        ui { AuraOverlayService.restore(context); viewModel.updatePhase(ConversationPhase.LISTENING) }
        if (sessionActive.get() && !recording.get()) startMic()
    }

    /** Single-consumer drain: only one coroutine ever writes to PcmStreamPlayer. */
    private fun ensureDrain() {
        synchronized(drainLock) {
            if (drainJob?.isActive == true) return
            drainJob = scope.launch(Dispatchers.IO) {
                val self = coroutineContext[Job]
                ensureCommRouting()
                player.start()
                // Stamp playback start so the AEC convergence guard can tell AURA's own first
                // syllable (residual echo) from a genuine user barge-in.
                playbackStartedAtMs = System.currentTimeMillis()
                ui { viewModel.updatePhase(ConversationPhase.RESPONDING) }
                // LV12: tear the player down only after a REAL gap. The old loop broke out after a
                // single empty poll plus 15 ms, but Live audio chunks do not arrive on a sub-15 ms
                // cadence — so mid-turn starvation destroyed the AudioTrack and the next chunk had
                // to rebuild it, giving chopped speech and per-rebuild allocation latency.
                var starvedMs = 0L
                while (isActive) {
                    val c = audioQueue.poll()
                    if (c != null) {
                        starvedMs = 0L
                        // Drive the chat waveform with the MODEL's voice too — the same
                        // amplitude channel the mic uses, so speaking animates the wave.
                        onAmplitude(amplitude(c, c.size))
                        player.writeChunk(c)
                    } else {
                        if (starvedMs >= DRAIN_IDLE_GRACE_MS) break
                        delay(DRAIN_POLL_MS)
                        starvedMs += DRAIN_POLL_MS
                    }
                }
                // Teardown belongs to the CURRENT owner only. After a barge-in, onInterrupted has
                // already stopped the player and the next turn's drain may be running — a stale
                // job stopping the shared player here killed the new reply's first audio.
                synchronized(drainLock) {
                    if (drainJob !== self) return@launch
                    drainJob = null
                }
                player.stop()
                onAmplitude(0f)
                if (sessionActive.get() && !driving.get()) ui { viewModel.updatePhase(ConversationPhase.LISTENING) }
            }
        }
    }

    /**
     * Comm-mode + loudspeaker routing for the Live voice loop. MODE_IN_COMMUNICATION arms the
     * platform echo path; with the player on USAGE_VOICE_COMMUNICATION the hardware AEC gets the
     * model's voice as far-end reference (the whole point — barge-in needs the user's speech to
     * survive over the speaker output). Loudspeaker is forced explicitly because comm usage would
     * otherwise default to the earpiece; isSpeakerphoneOn is deprecated + a silent no-op on S+.
     */
    private fun ensureCommRouting() {
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val speaker = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null && audioManager.communicationDevice?.id != speaker.id) {
                audioManager.setCommunicationDevice(speaker)
            }
        } else {
            @Suppress("DEPRECATION")
            if (!audioManager.isSpeakerphoneOn) audioManager.isSpeakerphoneOn = true
        }
    }

    private fun clearCommRouting() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun ui(block: () -> Unit) { scope.launch(Dispatchers.Main) { block() } }

    private fun amplitude(buf: ByteArray, n: Int): Float {
        if (n < 2) return 0f
        var sumSq = 0.0; var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            val norm = s / 32768.0; sumSq += norm * norm; i += 2
        }
        return Math.sqrt(sumSq / (n / 2)).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val GREETING_PREFS = "aura_greeting"
        const val KEY_LAST_GREET_DAY = "last_greet_day"

        /**
         * How often the paced transcript catches up with the speaker.
         *
         * 80 ms is under the threshold at which text appearing in steps reads as stepping
         * rather than flowing, and cheap: it does no work beyond reading a frame counter.
         */
        const val TRANSCRIPT_TICK_MS = 80L

        /** How long the finished sentence stays readable before the caption clears. */
        const val CAPTION_DWELL_MS = 1_500L

        const val TAG = "CompanionLiveCtrl"
        // P0 conversation memory: don't remember a bare greeting; cap durable facts per batch;
        // checkpoint durable facts every few user turns so a drop can't lose them.
        const val MIN_TURNS_TO_REMEMBER = 2
        const val MAX_FACTS = 4

        /** Style notes kept per session — the persona only ever renders a few. */
        const val MAX_STYLE_NOTES = 2
        const val CHECKPOINT_EVERY_TURNS = 4

        /**
         * Turns of conversation handed to the brain lane. Enough to resolve a pronoun or a
         * follow-up ("make it a large one"), short enough that a long session does not grow the
         * lane's prompt without bound.
         */
        const val TRANSCRIPT_TURNS_FOR_LANE = 8
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // Live best-practices: send audio in 20-40 ms chunks. 100 ms chunks added up to ~80 ms
        // to barge-in detection latency and gave the server VAD coarser framing.
        const val CHUNK_MS = 40
        /** Playback-queue poll interval while the model is mid-utterance. */
        const val DRAIN_POLL_MS = 15L
        /**
         * How long the queue must stay empty before the drain tears the AudioTrack down. Must
         * exceed normal inter-chunk network jitter, or the track is destroyed and rebuilt
         * mid-sentence (chopped speech + allocation latency on every rebuild).
         */
        const val DRAIN_IDLE_GRACE_MS = 300L
        /**
         * Hardware AEC convergence window after playback starts. Speech onsets inside it are
         * residual echo of AURA's own voice, not the user — same guard the cascade path uses.
         */
        const val AEC_CONVERGENCE_MS = 500L

        /**
         * Longest we wait for AURA's goodbye to finish playing before tearing the session down
         * anyway. Generous enough for a sentence, short enough that a turn which never completes
         * cannot leave the overlay stuck open after the user said goodbye.
         */
        const val END_FAREWELL_TIMEOUT_MS = 8_000L
        const val END_FAREWELL_POLL_MS = 100L
    }
}
