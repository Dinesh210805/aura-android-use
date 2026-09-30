package com.aura.aura_ui.voice

/**
 * Defines the listening modes for AURA voice assistant.
 * 
 * CRITICAL: Wake word detection and STT must NEVER run simultaneously.
 * Only ONE component can hold the AudioRecord at any time.
 */
enum class ListeningMode {
    /**
     * Wake word detection active.
     * Low-power Porcupine running continuously.
     * Transitions to ACTIVE when wake word detected.
     */
    PASSIVE,
    
    /**
     * Full STT streaming active.
     * User is speaking a command, audio streams to backend.
     * Timeout: 10-15 seconds of silence.
     * Transitions to PASSIVE when command completes.
     */
    ACTIVE,
    
    /**
     * Human-in-the-loop clarification mode.
     * Backend requested more information.
     * Same as ACTIVE but with shorter timeout (~5s).
     * Transitions to PASSIVE after response or timeout.
     */
    HITL,
    
    /**
     * All listening stopped.
     * Neither wake word nor STT active.
     * Used when user disables assistant or permissions missing.
     */
    OFF
}

/**
 * Configuration for listening mode behavior.
 */
data class ListeningModeConfig(
    /**
     * Timeout for ACTIVE mode in milliseconds.
     * User command times out after this duration of silence.
     */
    val activeTimeoutMs: Long = 15_000L,
    
    /**
     * Timeout for HITL mode in milliseconds.
     * Clarification request times out faster.
     */
    val hitlTimeoutMs: Long = 5_000L,
    
    /**
     * Silence duration before auto-stop in ACTIVE mode.
     */
    val silenceBeforeStopMs: Long = 2_000L,
    
    /**
     * Whether to auto-return to PASSIVE after command completes.
     */
    val autoReturnToPassive: Boolean = true,
    
    /**
     * Wake word sensitivity (0.0 - 1.0).
     * Higher = more sensitive but more false positives.
     */
    val wakeWordSensitivity: Float = 0.5f
)
