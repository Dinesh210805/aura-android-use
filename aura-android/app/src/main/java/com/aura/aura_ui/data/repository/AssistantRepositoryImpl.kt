package com.aura.aura_ui.data.repository

import com.aura.aura_ui.domain.model.VoiceSessionState
import com.aura.aura_ui.network.ConnectionManager
import com.aura.aura_ui.network.ConnectionState
import com.aura.aura_ui.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AssistantRepository"

@Singleton
class AssistantRepositoryImpl
    @Inject
    constructor(
        private val connectionManager: ConnectionManager,
        private val logger: Logger,
    ) : AssistantRepository {

        private val _voiceSessionState = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Idle)
        override val voiceSessionState: StateFlow<VoiceSessionState> = _voiceSessionState.asStateFlow()

        /** Exposes the WebSocket connection state so the UI can show a reconnect banner. */
        val connectionState: StateFlow<ConnectionState> = connectionManager.state

        override suspend fun startListening() {
            logger.d(TAG, "Starting voice listening")
            _voiceSessionState.value = VoiceSessionState.Listening()
        }

        override suspend fun stopListening() {
            logger.d(TAG, "Stopping voice listening")
            _voiceSessionState.value = VoiceSessionState.Idle
        }

        override suspend fun processTextCommand(text: String) {
            logger.d(TAG, "Processing text command: $text")
            _voiceSessionState.value = VoiceSessionState.Processing("Sending command to AURA backend")

            val currentState = connectionManager.state.value
            if (currentState !is ConnectionState.Connected) {
                logger.w(TAG, "Cannot send command: not connected (state=$currentState)")
                _voiceSessionState.value = VoiceSessionState.Error(
                    "Not connected to AURA backend",
                    canRetry = true,
                )
                delay(2000)
                _voiceSessionState.value = VoiceSessionState.Idle
                return
            }

            val sent = connectionManager.send(text)
            if (sent) {
                logger.d(TAG, "Command sent via WebSocket")
                // Response arrives asynchronously via ConnectionManager.onMessage
                _voiceSessionState.value = VoiceSessionState.Idle
            } else {
                logger.w(TAG, "WebSocket send failed — message queued")
                _voiceSessionState.value = VoiceSessionState.Error(
                    "Failed to send command — message queued for retry",
                    canRetry = true,
                )
                delay(2000)
                _voiceSessionState.value = VoiceSessionState.Idle
            }
        }

        override suspend fun initializeSession() {
            logger.d(TAG, "Initializing voice session")
            _voiceSessionState.value = VoiceSessionState.Connecting

            when (val state = connectionManager.state.value) {
                is ConnectionState.Connected -> {
                    logger.d(TAG, "Connected to ${state.serverUrl}")
                    _voiceSessionState.value = VoiceSessionState.Responding("Connected to AURA backend")
                    delay(1500)
                    _voiceSessionState.value = VoiceSessionState.Idle
                }
                else -> {
                    logger.d(TAG, "Awaiting connection (state=$state)")
                    _voiceSessionState.value = VoiceSessionState.Error(
                        "Connecting to AURA backend…",
                        canRetry = true,
                    )
                    delay(2000)
                    _voiceSessionState.value = VoiceSessionState.Idle
                }
            }
        }

        override suspend fun cleanup() {
            logger.d(TAG, "Cleaning up repository")
            _voiceSessionState.value = VoiceSessionState.Idle
        }
    }
