package com.aura.aura_ui.domain.model

/**
 * Sealed class representing the different states of a voice interaction session
 */
sealed class VoiceSessionState {
    /**
     * The voice assistant is idle and waiting for user interaction
     */
    data object Idle : VoiceSessionState()

    /**
     * The voice assistant is actively listening for user input
     * @param amplitude Current audio amplitude level (0.0 to 1.0)
     * @param duration How long the session has been listening in milliseconds
     */
    data class Listening(
        val amplitude: Float = 0f,
        val duration: Long = 0L,
    ) : VoiceSessionState()

    /**
     * The voice assistant is processing the captured audio
     * @param transcript The current transcript being processed (may be partial)
     */
    data class Processing(
        val transcript: String = "",
    ) : VoiceSessionState()

    /**
     * The voice assistant is generating and delivering a response
     * @param response The response text being delivered
     * @param isPlayingAudio Whether audio response is currently playing
     */
    data class Responding(
        val response: String,
        val isPlayingAudio: Boolean = false,
    ) : VoiceSessionState()

    /**
     * An error occurred during the voice session
     * @param error The error message
     * @param canRetry Whether the operation can be retried
     */
    data class Error(
        val error: String,
        val canRetry: Boolean = true,
    ) : VoiceSessionState()

    /**
     * The voice session is being initialized
     */
    data object Initializing : VoiceSessionState()

    /**
     * The voice session is connecting to backend services
     */
    data object Connecting : VoiceSessionState()
}

/**
 * Extension function to check if the session is in an active state
 */
fun VoiceSessionState.isActive(): Boolean {
    return when (this) {
        is VoiceSessionState.Listening,
        is VoiceSessionState.Processing,
        is VoiceSessionState.Responding,
        -> true
        else -> false
    }
}

/**
 * Extension function to check if the session can be interrupted
 */
fun VoiceSessionState.canBeInterrupted(): Boolean {
    return when (this) {
        is VoiceSessionState.Idle,
        is VoiceSessionState.Listening,
        is VoiceSessionState.Error,
        -> true
        else -> false
    }
}

/**
 * Extension function to get a user-friendly description of the state
 */
fun VoiceSessionState.getDisplayText(): String {
    return when (this) {
        is VoiceSessionState.Idle -> "Tap to start listening"
        is VoiceSessionState.Listening -> "Listening..."
        is VoiceSessionState.Processing -> "Processing your request..."
        is VoiceSessionState.Responding -> "Responding..."
        is VoiceSessionState.Error -> "Error: $error"
        is VoiceSessionState.Initializing -> "Initializing..."
        is VoiceSessionState.Connecting -> "Connecting to AURA..."
    }
}
