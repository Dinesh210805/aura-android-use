package com.aura.aura_ui.data.repository

import com.aura.aura_ui.data.database.AudioDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Implementation of AudioRepository.
 */
class AudioRepositoryImpl
    @Inject
    constructor(
        private val audioDao: AudioDao,
    ) : AudioRepository {
        private val _audioLevel = MutableStateFlow(0f)
        private val _recordingStatus = MutableStateFlow(false)

        override fun observeAudioLevel(): Flow<Float> = _audioLevel.asStateFlow()

        override suspend fun updateAudioLevel(level: Float) {
            _audioLevel.value = level
        }

        override fun observeRecordingStatus(): Flow<Boolean> = _recordingStatus.asStateFlow()

        override suspend fun updateRecordingStatus(isRecording: Boolean) {
            _recordingStatus.value = isRecording
        }
    }
