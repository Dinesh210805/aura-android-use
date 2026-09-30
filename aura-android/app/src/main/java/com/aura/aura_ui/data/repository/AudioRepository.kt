package com.aura.aura_ui.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing audio-related data.
 */
interface AudioRepository {
    /**
     * Observes the current audio level.
     */
    fun observeAudioLevel(): Flow<Float>

    /**
     * Updates the current audio level.
     */
    suspend fun updateAudioLevel(level: Float)

    /**
     * Observes the recording status.
     */
    fun observeRecordingStatus(): Flow<Boolean>

    /**
     * Updates the recording status.
     */
    suspend fun updateRecordingStatus(isRecording: Boolean)
}
