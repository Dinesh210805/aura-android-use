package com.aura.aura_ui.conversation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Agent output entry for thinking/reasoning trace
 */
data class AgentOutput(
    val id: String = UUID.randomUUID().toString(),
    val agent: String,
    val output: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class TaskProgress(
    val goal: String,
    val tasks: List<String>,
    val current: Int,
    val total: Int,
    val isComplete: Boolean,
)

/** Lifecycle of a single on-device agent step (one tool call). */
enum class AgentStepStatus { RUNNING, DONE, FAILED }

/**
 * One step in the on-device agent's run, shown in the chat's agent-process timeline.
 * A step is created RUNNING when a tool starts and transitions to DONE/FAILED when it
 * finishes — so the UI shows the call and its outcome as one grouped entry (an "agent
 * feel"), not two flat log lines. Fed by [ConversationViewModel.startAgentStep] /
 * [completeAgentStep] / [failAgentStep] from the agent event stream.
 */
data class AgentStep(
    val id: String = UUID.randomUUID().toString(),
    val tool: String,
    val title: String,   // human verb, e.g. "Opening", "Seeing", "Checking"
    val detail: String,  // short args (RUNNING/DONE) or error (FAILED)
    val status: AgentStepStatus,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * ViewModel managing conversation state and lifecycle
 */
class ConversationViewModel : ViewModel() {
    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val messages: StateFlow<List<ConversationMessage>> = _messages.asStateFlow()

    // IDs of in-progress streaming bubbles (updated in place as words arrive)
    private var streamingUserMessageId: String? = null
    private var streamingAiMessageId: String? = null

    // Agent outputs for "thinking" trace - shows agent pipeline activity
    private val _agentOutputs = MutableStateFlow<List<AgentOutput>>(emptyList())
    val agentOutputs: StateFlow<List<AgentOutput>> = _agentOutputs.asStateFlow()
    
    // Latest agent output for animated toast display
    private val _latestAgentOutput = MutableStateFlow<AgentOutput?>(null)
    val latestAgentOutput: StateFlow<AgentOutput?> = _latestAgentOutput.asStateFlow()

    companion object {
        private const val TAG = "ConversationViewModel"
        private const val MAX_AGENT_OUTPUTS = 50 // Keep last 50 agent outputs
        private const val DUPLICATE_SUPPRESSION_WINDOW_MS = 6000L
    }

    private fun normalizeTranscript(text: String): String =
        text.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun shouldSuppressDuplicateFinalMessage(text: String, isUser: Boolean): Boolean {
        val normalized = normalizeTranscript(text)
        val now = System.currentTimeMillis()
        val lastFinal = _messages.value
            .asReversed()
            .firstOrNull { it.isUser == isUser && !it.isStreaming }
            ?: return false

        val sameText = normalizeTranscript(lastFinal.text) == normalized
        val withinWindow = (now - lastFinal.timestamp) <= DUPLICATE_SUPPRESSION_WINDOW_MS
        return sameText && withinWindow
    }

    /**
     * Initialize a new conversation session
     */
    fun startSession(sessionId: String = UUID.randomUUID().toString()) {
        Log.d(TAG, "Starting conversation session: $sessionId")
        streamingUserMessageId = null
        streamingAiMessageId = null
        _state.value =
            _state.value.copy(
                sessionId = sessionId,
                conversationState = ConversationPhase.IDLE,
                isActive = true,
            )
        _messages.value = emptyList()
    }

    /**
     * Update conversation phase
     */
    fun updatePhase(phase: ConversationPhase) {
        Log.d(TAG, "Phase transition: ${_state.value.conversationState} -> $phase")
        _state.value = _state.value.copy(conversationState = phase)
    }

    /** Build 3: set/clear the resumable-run offer shown as a chip on the resting overlay. */
    fun setResumeOffer(offer: com.aura.aura_ui.agent.ledger.ResumeOffer?) {
        _state.value = _state.value.copy(resumeOffer = offer)
    }

    /**
     * Add user transcript
     */
    fun addUserMessage(transcript: String) {
        if (transcript.isBlank()) return

        viewModelScope.launch {
            if (shouldSuppressDuplicateFinalMessage(transcript, isUser = true)) {
                Log.d(TAG, "Suppressed duplicate user message: ${transcript.take(60)}")
                return@launch
            }
            val message =
                ConversationMessage(
                    text = transcript,
                    isUser = true,
                    timestamp = System.currentTimeMillis(),
                )
            _messages.value = _messages.value + message
            Log.d(TAG, "User message added: $transcript")
        }
    }

    /**
     * Add assistant response
     */
    fun addAssistantMessage(response: String) {
        if (response.isBlank()) return

        viewModelScope.launch {
            if (shouldSuppressDuplicateFinalMessage(response, isUser = false)) {
                Log.d(TAG, "Suppressed duplicate assistant message: ${response.take(60)}")
                return@launch
            }
            val message =
                ConversationMessage(
                    text = response,
                    isUser = false,
                    timestamp = System.currentTimeMillis(),
                )
            _messages.value = _messages.value + message
            Log.d(TAG, "Assistant message added: $response")
        }
    }

    /**
     * Start or update a streaming user message bubble (grows in place as words arrive).
     * Call with each partial transcript fragment; the same bubble is updated each time.
     */
    fun startOrUpdateStreamingUserMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val id = streamingUserMessageId
            if (id == null) {
                val newId = UUID.randomUUID().toString()
                streamingUserMessageId = newId
                _messages.value = _messages.value + ConversationMessage(
                    id = newId, text = text, isUser = true, isStreaming = true
                )
            } else {
                _messages.value = _messages.value.map {
                    if (it.id == id && it.text != text) it.copy(text = text) else it
                }
            }
        }
    }

    /**
     * Finalize the in-progress streaming user bubble with the definitive transcript text.
     * If no streaming bubble exists, adds a normal message instead.
     */
    fun finalizeStreamingUserMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            if (shouldSuppressDuplicateFinalMessage(text, isUser = true)) {
                streamingUserMessageId = null
                Log.d(TAG, "Suppressed duplicate final user transcript: ${text.take(60)}")
                return@launch
            }
            val id = streamingUserMessageId
            streamingUserMessageId = null
            if (id != null) {
                _messages.value = _messages.value.map {
                    if (it.id == id) it.copy(text = text, isStreaming = false) else it
                }
            } else {
                _messages.value = _messages.value + ConversationMessage(text = text, isUser = true)
            }
        }
    }

    /**
     * Start or update a streaming AI message bubble (grows in place as words arrive).
     */
    fun startOrUpdateStreamingAiMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val id = streamingAiMessageId
            if (id == null) {
                val newId = UUID.randomUUID().toString()
                streamingAiMessageId = newId
                _messages.value = _messages.value + ConversationMessage(
                    id = newId, text = text, isUser = false, isStreaming = true
                )
            } else {
                _messages.value = _messages.value.map {
                    if (it.id == id && it.text != text) it.copy(text = text) else it
                }
            }
        }
    }

    /**
     * Finalize the in-progress streaming AI bubble with the definitive transcript text.
     * If no streaming bubble exists, adds a normal message instead.
     */
    fun finalizeStreamingAiMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            if (shouldSuppressDuplicateFinalMessage(text, isUser = false)) {
                streamingAiMessageId = null
                Log.d(TAG, "Suppressed duplicate final AI transcript: ${text.take(60)}")
                return@launch
            }
            val id = streamingAiMessageId
            streamingAiMessageId = null
            if (id != null) {
                _messages.value = _messages.value.map {
                    if (it.id == id) it.copy(text = text, isStreaming = false) else it
                }
            } else {
                _messages.value = _messages.value + ConversationMessage(text = text, isUser = false)
            }
        }
    }

    /**
     * Cancel any in-progress streaming bubbles (e.g. on barge-in or session end).
     * Removes partial-only messages; keeps finalized ones.
     */
    fun cancelStreamingMessages() {
        viewModelScope.launch {
            val userIdToRemove = streamingUserMessageId
            val aiIdToRemove = streamingAiMessageId
            streamingUserMessageId = null
            streamingAiMessageId = null
            if (userIdToRemove != null || aiIdToRemove != null) {
                _messages.value = _messages.value.filter {
                    it.id != userIdToRemove && it.id != aiIdToRemove
                }
            }
        }
    }

    /**
     * Update partial transcript (live preview)
     */
    fun updatePartialTranscript(text: String) {
        _state.value = _state.value.copy(partialTranscript = text)
    }

    /**
     * Set error state
     */
    fun setError(error: String) {
        Log.e(TAG, "Error: $error")
        _state.value =
            _state.value.copy(
                error = error,
                conversationState = ConversationPhase.ERROR,
            )
    }

    /**
     * Clear error
     */
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * End conversation session
     */
    fun endSession() {
        Log.d(TAG, "Ending conversation session: ${_state.value.sessionId}")
        _state.value =
            _state.value.copy(
                isActive = false,
                conversationState = ConversationPhase.IDLE,
            )
    }

    /**
     * Reset to idle state
     */
    fun resetToIdle() {
        _state.value =
            _state.value.copy(
                conversationState = ConversationPhase.IDLE,
                partialTranscript = "",
                error = null,
            )
    }

    /**
     * Update server connection status
     */
    fun updateServerConnection(isConnected: Boolean) {
        if (_state.value.isServerConnected != isConnected) {
            Log.d(TAG, "Server connection changed: $isConnected")
            _state.value = _state.value.copy(isServerConnected = isConnected)
        }
    }

    /**
     * Delete a message by ID
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            _messages.value = _messages.value.filter { it.id != messageId }
            Log.d(TAG, "Message deleted: $messageId")
        }
    }

    /**
     * Clear all messages in conversation
     */
    fun clearAllMessages() {
        viewModelScope.launch {
            _messages.value = emptyList()
            Log.d(TAG, "All messages cleared")
        }
    }

    /**
     * Update processing context (what AURA is doing)
     */
    fun updateProcessingContext(context: String) {
        _state.value = _state.value.copy(processingContext = context)
    }

    /**
     * Update suggested commands
     */
    fun updateSuggestedCommands(commands: List<String>) {
        _state.value = _state.value.copy(suggestedCommands = commands)
    }

    /**
     * Add to recent commands (max 5)
     */
    fun addRecentCommand(command: String) {
        val current = _state.value.recentCommands.toMutableList()
        current.remove(command) // Remove if exists
        current.add(0, command) // Add to front
        _state.value = _state.value.copy(
            recentCommands = current.take(5) // Keep only 5 most recent
        )
    }
    
    /**
     * Add agent output to thinking trace
     * Shows real-time agent activity during command execution
     */
    fun addAgentOutput(agent: String, output: String) {
        val agentOutput = AgentOutput(
            agent = agent,
            output = output,
            timestamp = System.currentTimeMillis()
        )
        
        // Update latest for animated toast
        _latestAgentOutput.value = agentOutput
        
        // Add to history (keep bounded)
        val current = _agentOutputs.value.toMutableList()
        current.add(agentOutput)
        if (current.size > MAX_AGENT_OUTPUTS) {
            current.removeAt(0)
        }
        _agentOutputs.value = current
        
        Log.d(TAG, "Agent output: $agent → $output")
    }
    
    /**
     * Clear agent outputs (call when new command starts)
     */
    fun clearAgentOutputs() {
        _agentOutputs.value = emptyList()
        _latestAgentOutput.value = null
        _taskProgress.value = null
        Log.d(TAG, "Agent outputs cleared")
    }

    // ── On-device agent step timeline ────────────────────────────────────────
    private val _agentSteps = MutableStateFlow<List<AgentStep>>(emptyList())
    val agentSteps: StateFlow<List<AgentStep>> = _agentSteps.asStateFlow()

    /** A tool call started → append a RUNNING step. */
    fun startAgentStep(tool: String, title: String, detail: String) {
        val step = AgentStep(
            tool = tool, title = title, detail = detail, status = AgentStepStatus.RUNNING,
        )
        _agentSteps.value = (_agentSteps.value + step).takeLast(MAX_AGENT_OUTPUTS)
    }

    /** The most recent RUNNING step for [tool] finished successfully. */
    fun completeAgentStep(tool: String, detail: String) {
        _agentSteps.value = markLatestRunning(tool, AgentStepStatus.DONE, detail)
    }

    /** The most recent RUNNING step for [tool] failed. */
    fun failAgentStep(tool: String, detail: String) {
        _agentSteps.value = markLatestRunning(tool, AgentStepStatus.FAILED, detail)
    }

    /** Transition the latest RUNNING step for [tool] (agent runs tools sequentially). */
    private fun markLatestRunning(
        tool: String,
        status: AgentStepStatus,
        detail: String,
    ): List<AgentStep> {
        val list = _agentSteps.value
        val idx = list.indexOfLast { it.tool == tool && it.status == AgentStepStatus.RUNNING }
        if (idx < 0) return list
        return list.toMutableList().also { mutable ->
            val prev = mutable[idx]
            mutable[idx] = prev.copy(status = status, detail = detail.ifBlank { prev.detail })
        }
    }

    /** Clear the step timeline (call when a new command starts). */
    fun clearAgentSteps() {
        _agentSteps.value = emptyList()
    }
    
    // Task progress state for skeleton steps display
    private val _taskProgress = MutableStateFlow<TaskProgress?>(null)
    val taskProgress: StateFlow<TaskProgress?> = _taskProgress.asStateFlow()
    
    /**
     * Update task progress (skeleton steps from planner)
     */
    fun updateTaskProgress(goal: String, tasks: List<String>, current: Int, total: Int, isComplete: Boolean) {
        _taskProgress.value = TaskProgress(goal, tasks, current, total, isComplete)
    }
    
    /**
     * Dismiss latest agent output (after animation completes)
     */
    fun dismissLatestAgentOutput() {
        _latestAgentOutput.value = null
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared")
    }
}

/**
 * Conversation state data class
 */
data class ConversationState(
    val sessionId: String? = null,
    val conversationState: ConversationPhase = ConversationPhase.IDLE,
    val isActive: Boolean = false,
    val partialTranscript: String = "",
    val error: String? = null,
    val isServerConnected: Boolean = false,
    val processingContext: String = "",
    val suggestedCommands: List<String> = listOf(
        "Open camera",
        "Turn on WiFi",
        "What's on screen?"
    ),
    val recentCommands: List<String> = emptyList(),
    // Build 3: a persisted interrupted run worth resuming, surfaced as a chip. Null = no offer.
    val resumeOffer: com.aura.aura_ui.agent.ledger.ResumeOffer? = null,
)

/**
 * Conversation phases matching backend states
 */
enum class ConversationPhase {
    IDLE, // Ready to start
    LISTENING, // Capturing audio
    THINKING, // Processing backend request
    RESPONDING, // Playing audio response
    ERROR, // Error state
}

/**
 * Conversation message model with enhanced properties
 */
data class ConversationMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isPartial: Boolean = false,
    val isStreaming: Boolean = false,
    val status: MessageStatus = MessageStatus.SENT,
) {
    /**
     * Format timestamp as relative time string
     */
    fun getRelativeTime(): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> "${diff / 3600_000}h ago"
            else -> "${diff / 86400_000}d ago"
        }
    }
}

/**
 * Message delivery status
 */
enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    ERROR
}
