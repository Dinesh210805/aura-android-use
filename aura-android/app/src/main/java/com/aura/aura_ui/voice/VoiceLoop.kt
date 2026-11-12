package com.aura.aura_ui.voice

import android.content.Context
import android.util.Log
import com.aura.aura_ui.agent.research.TaskResearch
import com.aura.aura_ui.agent.conversation.CompanionConfig
import com.aura.aura_ui.services.AgentStatusRegistry
import com.aura.aura_ui.services.ControlLockStore
import com.aura.aura_ui.voice.live.LiveVoice
import com.aura.aura_ui.voice.local.LocalVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One voice conversation: the glue between AURA's ears and mouth ([VoiceIo]) and its brain
 * (AgentCore, reached through [Brain]). Plain code, no model: it opens and closes the mic, hands
 * each finished utterance to the brain, and speaks what the brain answers.
 *
 * - Contract: [start] opens the voice the user chose (Gemini 3.8 Live with a saved Gemini key and
 *   Live switched on, else Moonshine + Piper) and greets only when asked to, which the overlay does
 *   for the AURA button and the wake word. A task typed as text never starts a conversation.
 *   After a reply the mic follows [After]: a short follow-up window for an answer, closed after a
 *   task. While the mic is open, touching the screen never pauses AURA (it is the person talking to
 *   it). The conversation ends itself after [IDLE_END_MS] with the mic closed.
 * - Fails: a voice that cannot open reports through [trouble] and leaves [phase] OFF.
 * - Change together: `AuraOverlayService` (the only owner) and the overlay's listening bar, which
 *   draws [phase], [level] and [hearing].
 */
class VoiceLoop(
    private val context: Context,
    private val scope: CoroutineScope,
    private val brain: Brain,
) {
    /** Where finished utterances go. The overlay routes them: an answer, a change to the running task, or a new task. */
    fun interface Brain {
        fun onHeard(text: String)
    }

    enum class Phase { OFF, CONNECTING, LISTENING, THINKING, SPEAKING, IDLE }

    /** What the mic does once AURA has finished saying something. */
    enum class After {
        /** A short window for a follow-up, then closed. */
        FOLLOW_UP,

        /** AURA asked a question: listen for the answer. */
        ANSWER,

        /** A task finished: stay quiet until the wake word, the AURA button or typing. */
        CLOSE,
    }

    private val _phase = MutableStateFlow(Phase.OFF)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _hearing = MutableStateFlow("")
    val hearing: StateFlow<String> = _hearing.asStateFlow()

    private val _trouble = MutableStateFlow<String?>(null)
    val trouble: StateFlow<String?> = _trouble.asStateFlow()

    private var io: VoiceIo? = null
    private var after = After.FOLLOW_UP
    private var windowJob: Job? = null
    private var idleJob: Job? = null
    private var opening: Job? = null

    /** True from [start] until [end]. */
    val active: Boolean get() = io != null || opening?.isActive == true

    val usesLive: Boolean get() = io is LiveVoice

    private val listener = object : VoiceIo.Listener {
        override fun onUserSpeech(started: Boolean) {
            if (started) {
                windowJob?.cancel()
                idleJob?.cancel()
                if (_phase.value != Phase.SPEAKING) _phase.value = Phase.LISTENING
            }
        }

        override fun onHearing(partial: String) {
            _hearing.value = partial
            if (partial.isNotBlank() && partial != "…") AgentStatusRegistry.setHeard(partial)
        }

        override fun onHeard(text: String) {
            scope.launch { heard(text) }
        }

        override fun onSpeaking(words: String) {
            AgentStatusRegistry.setSpoken(words)
        }

        override fun onSpoken(completed: Boolean) {
            scope.launch { spoken(completed) }
        }

        override fun onLevel(level: Float) {
            _level.value = level
        }

        override fun onTrouble(message: String) {
            Log.w(TAG, message)
            _trouble.value = message
        }
    }

    /**
     * Open the conversation. With [greet], AURA says hello first and then listens; otherwise it
     * just listens. Calling it while open only reopens the mic.
     */
    fun start(greet: Boolean) {
        if (active) {
            listen()
            return
        }
        _trouble.value = null
        _phase.value = Phase.CONNECTING
        opening = scope.launch {
            val voice = chooseVoice()
            if (!voice.open()) {
                voice.close()
                _phase.value = Phase.OFF
                return@launch
            }
            io = voice
            if (greet) reply(greeting(), After.FOLLOW_UP) else listen()
        }
    }

    /** Open the mic for the person's next words (the AURA button, the wake word, "Speak" on a question). */
    fun listen(windowMs: Long? = null) {
        val voice = io ?: return
        if (voice.speaking) voice.stopSpeaking()
        idleJob?.cancel()
        _hearing.value = ""
        voice.listen(true)
        ControlLockStore.shared.userWithAura = true
        _phase.value = Phase.LISTENING
        windowJob?.cancel()
        if (windowMs != null) {
            windowJob = scope.launch {
                delay(windowMs)
                if (_phase.value == Phase.LISTENING && _hearing.value.isBlank()) quiet()
            }
        }
    }

    /** Close the mic; the conversation stays open for the next wake word or tap. */
    fun quiet() {
        windowJob?.cancel()
        io?.listen(false)
        ControlLockStore.shared.userWithAura = false
        _hearing.value = ""
        _level.value = 0f
        if (io != null) _phase.value = Phase.IDLE
        armIdleEnd()
    }

    /**
     * Speak [text], then do [then] with the mic. The mic stays open while AURA speaks so the person
     * can talk over it. No-op without an open conversation: typed work stays silent.
     */
    fun reply(text: String, then: After) {
        val voice = io ?: return
        if (text.isBlank()) {
            applyAfter(then)
            return
        }
        after = then
        windowJob?.cancel()
        idleJob?.cancel()
        _phase.value = Phase.SPEAKING
        if (!voice.listening) voice.listen(true)
        ControlLockStore.shared.userWithAura = true
        voice.say(text)
    }

    /** A short line while the brain works ("On it"), only if nobody is talking. */
    fun note(text: String) {
        val voice = io ?: return
        if (voice.speaking || _phase.value == Phase.LISTENING) return
        after = After.CLOSE
        _phase.value = Phase.SPEAKING
        voice.say(text)
    }

    /** The brain is working on what was heard. */
    fun thinking() {
        if (io != null) _phase.value = Phase.THINKING
    }

    fun stopSpeaking() = io?.stopSpeaking()

    /** End the conversation and release the mic, the speaker and any Live connection. */
    fun end() {
        opening?.cancel()
        windowJob?.cancel()
        idleJob?.cancel()
        io?.close()
        io = null
        ControlLockStore.shared.userWithAura = false
        _hearing.value = ""
        _level.value = 0f
        _phase.value = Phase.OFF
    }

    private fun heard(text: String) {
        windowJob?.cancel()
        io?.listen(false)
        ControlLockStore.shared.userWithAura = false
        _hearing.value = ""
        _phase.value = Phase.THINKING
        AgentStatusRegistry.setHeard(text)
        brain.onHeard(text)
    }

    private fun spoken(completed: Boolean) {
        if (!completed) {
            // Cut off: the person is talking, so keep listening for what they say.
            if (io?.listening == true) {
                _phase.value = Phase.LISTENING
                windowJob?.cancel()
                windowJob = scope.launch {
                    delay(FOLLOW_UP_MS)
                    if (_phase.value == Phase.LISTENING && _hearing.value.isBlank()) quiet()
                }
            } else {
                quiet()
            }
            return
        }
        applyAfter(after)
    }

    private fun applyAfter(then: After) {
        when (then) {
            After.FOLLOW_UP -> listen(FOLLOW_UP_MS)
            After.ANSWER -> listen(ANSWER_MS)
            After.CLOSE -> quiet()
        }
    }

    private fun armIdleEnd() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_END_MS)
            // A task the person started by voice still has a spoken reply to come.
            if (ControlLockStore.shared.localRunning.value) armIdleEnd() else if (_phase.value == Phase.IDLE) end()
        }
    }

    private fun chooseVoice(): VoiceIo {
        val config = CompanionConfig(context)
        val key = config.byokKey()
        return if (config.useGeminiLive && !key.isNullOrBlank()) {
            LiveVoice(context, key, config.voice(), ::vocabulary, scope, listener)
        } else {
            LocalVoice(context, scope, listener)
        }
    }

    /** Words the transcriber should expect: AURA's name and the apps on this phone. */
    private fun vocabulary(): List<String> =
        (listOf("AURA") + TaskResearch.launcherApps(context).map { it.label })
            .map(String::trim).filter { it.length in 2..40 }.distinct().take(VOCABULARY_MAX)

    private fun greeting(): String {
        val hour = java.time.LocalTime.now().hour
        val part = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$part. What can I do for you?"
    }

    private companion object {
        const val TAG = "VoiceLoop"
        const val FOLLOW_UP_MS = 8_000L
        const val ANSWER_MS = 10_000L
        const val IDLE_END_MS = 120_000L
        const val VOCABULARY_MAX = 100
    }
}
