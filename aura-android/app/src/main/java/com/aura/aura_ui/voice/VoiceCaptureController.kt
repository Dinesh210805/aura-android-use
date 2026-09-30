package com.aura.aura_ui.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.audio.AuraTTSManager
import com.aura.aura_ui.conversation.ConversationPhase
import com.aura.aura_ui.conversation.ConversationViewModel
import com.aura.aura_ui.overlay.AuraOverlayService
import com.aura.aura_ui.overlay.HITLHandler
import com.aura.aura_ui.overlay.VisualFeedbackHandler
import com.aura.aura_ui.utils.ContactResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Voice capture controller with WebRTC VAD and streaming support
 * Manages audio recording, VAD-based auto-stop, and websocket communication
 * 
 * Coordinates with ListeningModeController to avoid conflicts with wake word detection.
 * CRITICAL: Wake word and STT must never run simultaneously.
 */
class VoiceCaptureController(
    private val context: Context,
    private val serverUrl: String,
    private val viewModel: ConversationViewModel,
    private val scope: CoroutineScope,
    private val onAmplitudeUpdate: ((Float) -> Unit)? = null,
    /**
     * When true, this controller acts as a silent gesture-execution pipe only.
     * Microphone capture and TTS playback are completely disabled.
     * Set to true when GeminiLiveController is handling all voice I/O.
     */
    val deviceControlOnly: Boolean = false,
) {
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    private var sessionId: String? = null
    private val ttsManager = AuraTTSManager(context)
    private var offlineSpeechRecognizer: SpeechRecognizer? = null
    
    // Listening mode coordination
    private val listeningModeController: ListeningModeController by lazy {
        ListeningModeController.getInstance(context)
    }

    // VAD state
    private val vadDetector = SimpleVAD()
    private var silenceFrameCount = 0
    private val silenceThreshold = 20 // ~2 seconds at 100ms chunks (20 * 100ms = 2000ms)

    companion object {
        private const val TAG = "VoiceCaptureController"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_DURATION_MS = 100 // Increased from 50ms for smoother streaming
        private const val VERSION = "2.1" // Force rebuild
    }

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    /**
     * Initialize websocket connection
     */
    suspend fun connect(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                sessionId = UUID.randomUUID().toString()
                val request =
                    Request.Builder()
                        .url("$serverUrl/ws/conversation")
                        .build()

                // Use CompletableDeferred to wait for connection result
                val connectionResult = CompletableDeferred<Boolean>()

                webSocket =
                    client.newWebSocket(
                        request,
                        object : WebSocketListener() {
                            override fun onOpen(
                                webSocket: WebSocket,
                                response: Response,
                            ) {
                                Log.i(TAG, "✅ WebSocket connected successfully")
                                scope.launch {
                                    viewModel.startSession(sessionId!!)
                                    viewModel.updateServerConnection(true)
                                    viewModel.updatePartialTranscript("Connected! Press mic to speak")
                                    
                                    // Send device info with actual screen dimensions
                                    sendDeviceInfo(webSocket)
                                    
                                    // Setup HITL response callback
                                    HITLHandler.setResponseCallback { response ->
                                        Log.i(TAG, "📤 Sending HITL response via WebSocket")
                                        webSocket.send(response.toString())
                                    }
                                }
                                connectionResult.complete(true)
                            }

                            override fun onMessage(
                                webSocket: WebSocket,
                                text: String,
                            ) {
                                handleWebSocketMessage(text)
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                Log.e(TAG, "WebSocket error: ${t.message}")
                                // Restore keyboard so it's never permanently hidden after a drop
                                AuraAccessibilityService.instance?.restoreKeyboard()
                                scope.launch {
                                    viewModel.updateServerConnection(false)
                                    viewModel.setError("Connection failed: ${t.message}")
                                }
                                connectionResult.complete(false)
                            }

                            override fun onClosing(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String,
                            ) {
                                Log.d(TAG, "WebSocket closing: $reason")
                                // Session ending — restore keyboard so IME is available again
                                AuraAccessibilityService.instance?.restoreKeyboard()
                                scope.launch {
                                    viewModel.updateServerConnection(false)
                                }
                            }
                        },
                    )

                // Wait for connection to complete (max 5 seconds)
                withTimeout(5000) {
                    connectionResult.await()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Connection timeout after 5 seconds")
                viewModel.setError("Connection timeout")
                false
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                viewModel.setError("Failed to connect: ${e.message}")
                false
            }
        }

    /**
     * Start voice capture with VAD auto-stop.
     * No-op when deviceControlOnly=true (Gemini Live owns the mic).
     */
    fun startCapture() {
        if (deviceControlOnly) {
            Log.d(TAG, "🔇 startCapture ignored — deviceControlOnly mode (Gemini Live active)")
            return
        }
        Log.i(TAG, "🎤 START CAPTURE CALLED")
        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return
        }

        // Stop any ongoing TTS playback before recording
        ttsManager.stop()

        // Notify mode controller that STT is starting
        listeningModeController.markSTTStarted()

        // Offline mode: server not connected — use on-device speech recognition
        if (webSocket == null) {
            startOfflineSpeechRecognition()
            return
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

            audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize,
                )

            audioRecord?.startRecording()
            isRecording.set(true)
            silenceFrameCount = 0
            vadDetector.reset()

            Log.i(
                TAG,
                "🎙️ Audio capture started - chunk: ${CHUNK_DURATION_MS}ms, silence threshold: ${silenceThreshold * CHUNK_DURATION_MS}ms",
            )

            // Notify backend
            sendStartMessage()

            // Update state
            viewModel.updatePhase(ConversationPhase.LISTENING)
            viewModel.updatePartialTranscript("")

            // Start streaming with VAD
            recordingJob =
                scope.launch(Dispatchers.IO) {
                    streamAudioWithVAD()
                }

            Log.i(TAG, "✅ Voice capture started with VAD - recording now!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture: ${e.message}")
            viewModel.setError("Recording error: ${e.message}")
            listeningModeController.markSTTStopped()
            cleanup()
        }
    }

    /**
     * Offline voice capture using Android's on-device SpeechRecognizer.
     * Invoked when the server WebSocket is not connected.
     *
     * Must run on the main thread (SpeechRecognizer requirement).
     */
    private fun startOfflineSpeechRecognition() {
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        if (!onDeviceAvailable && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            listeningModeController.markSTTStopped()
            scope.launch {
                viewModel.addAssistantMessage(
                    "Offline voice commands require Android 12 or later. Connect to the server or type your command."
                )
                viewModel.updatePhase(ConversationPhase.IDLE)
            }
            return
        }

        isRecording.set(true)
        viewModel.updatePhase(ConversationPhase.LISTENING)
        viewModel.updatePartialTranscript("Listening (offline)…")
        Log.i(TAG, "🎙️ Starting offline speech recognition (on-device: $onDeviceAvailable)")

        scope.launch(Dispatchers.Main) {
            val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && onDeviceAvailable) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            offlineSpeechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    viewModel.updatePartialTranscript("Speak now…")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    onAmplitudeUpdate?.invoke((rmsdB.coerceIn(-2f, 10f) + 2f) / 12f)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    viewModel.updatePartialTranscript("Processing…")
                    viewModel.updatePhase(ConversationPhase.THINKING)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    viewModel.updatePartialTranscript(partial)
                }
                override fun onResults(results: Bundle?) {
                    isRecording.set(false)
                    offlineSpeechRecognizer = null
                    recognizer.destroy()
                    listeningModeController.markSTTStopped()

                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()

                    if (text.isNullOrBlank()) {
                        viewModel.updatePartialTranscript("")
                        viewModel.updatePhase(ConversationPhase.IDLE)
                        listeningModeController.onCommandComplete()
                        return
                    }

                    Log.i(TAG, "🗣️ Offline transcript: $text")
                    viewModel.updatePartialTranscript("")
                    sendTextCommand(text)
                    listeningModeController.onCommandComplete()
                }
                override fun onError(error: Int) {
                    isRecording.set(false)
                    offlineSpeechRecognizer = null
                    recognizer.destroy()
                    listeningModeController.markSTTStopped()
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — please try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                        else -> "Speech recognition error ($error)."
                    }
                    scope.launch {
                        viewModel.addAssistantMessage(msg)
                        viewModel.updatePhase(ConversationPhase.IDLE)
                    }
                    listeningModeController.onCommandComplete()
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer.startListening(intent)
        }
    }

    /**
     * Stop voice capture
     */
    fun stopCapture() {
        if (!isRecording.get()) return

        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        offlineSpeechRecognizer?.cancel()
        offlineSpeechRecognizer?.destroy()
        offlineSpeechRecognizer = null

        // Reset amplitude
        onAmplitudeUpdate?.invoke(0f)

        sendEndTurnMessage()
        viewModel.updatePhase(ConversationPhase.THINKING)

        // Notify mode controller that STT stopped
        listeningModeController.markSTTStopped()

        Log.d(TAG, "Voice capture stopped")
    }

    /**
     * Cancel voice capture without sending to backend
     * Used when user presses cancel/close button
     */
    fun cancelCapture() {
        if (!isRecording.get()) return

        Log.d(TAG, "🚫 Voice capture cancelled by user")
        
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        offlineSpeechRecognizer?.cancel()
        offlineSpeechRecognizer?.destroy()
        offlineSpeechRecognizer = null

        // Reset amplitude
        onAmplitudeUpdate?.invoke(0f)

        // Don't send end_turn - just reset to idle
        viewModel.updatePhase(ConversationPhase.IDLE)
        viewModel.updatePartialTranscript("")

        // Notify mode controller and return to passive (for wake word)
        listeningModeController.markSTTStopped()
        listeningModeController.onCommandComplete()
    }

    /**
     * Stream audio with VAD-based auto-stop
     */
    private suspend fun streamAudioWithVAD() {
        val chunkSize = (SAMPLE_RATE * CHUNK_DURATION_MS) / 1000 * 2 // bytes for 100ms
        val buffer = ByteArray(chunkSize)

        var speechDetected = false
        var continuousSpeechFrames = 0
        val pendingBuffers = mutableListOf<ByteArray>()
        var frameCount = 0
        var calibrationComplete = false

        while (isRecording.get()) {
            val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

            if (bytesRead > 0) {
                frameCount++

                // Calculate and emit amplitude for waveform visualization
                val amplitude = calculateAmplitude(buffer, bytesRead)
                withContext(Dispatchers.Main) {
                    onAmplitudeUpdate?.invoke(amplitude)
                }

                // Show calibration progress (first 30 frames = ~3 seconds)
                if (!calibrationComplete && frameCount == 30) {
                    calibrationComplete = true
                    Log.i(TAG, "✅ Noise calibration complete, ready for speech")
                    withContext(Dispatchers.Main) {
                        viewModel.updatePartialTranscript("Ready - speak now!")
                    }
                }

                // VAD check
                val isSpeech = vadDetector.isSpeech(buffer, bytesRead)

                if (isSpeech) {
                    silenceFrameCount = 0
                    continuousSpeechFrames++

                    if (!speechDetected) {
                        // First speech detected - buffer it
                        pendingBuffers.add(buffer.copyOf(bytesRead))
                        speechDetected = true
                        Log.d(TAG, "🎤 First speech frame detected, buffering...")
                    } else if (continuousSpeechFrames >= 2) {
                        // Confirmed continuous speech - send buffered + current
                        if (pendingBuffers.isNotEmpty()) {
                            Log.d(TAG, "📤 Confirmed speech! Sending ${pendingBuffers.size} buffered frames")
                            pendingBuffers.forEach { bufferedChunk ->
                                webSocket?.send(bufferedChunk.toByteString())
                            }
                            pendingBuffers.clear()
                        }
                        webSocket?.send(buffer.copyOf(bytesRead).toByteString())
                    } else {
                        // Still within initial confirmation window - keep buffering
                        pendingBuffers.add(buffer.copyOf(bytesRead))
                    }
                } else {
                    // Not speech
                    if (speechDetected) {
                        // Silence after speech
                        silenceFrameCount++

                        // If silence but we haven't confirmed speech yet (false alarm)
                        if (continuousSpeechFrames < 2 && silenceFrameCount >= 3) {
                            Log.d(TAG, "🗑️ False alarm - clearing ${pendingBuffers.size} buffered frames")
                            pendingBuffers.clear()
                            speechDetected = false
                            continuousSpeechFrames = 0
                        }
                    }
                }

                // Auto-stop after silence threshold (only if we've sent audio)
                if (speechDetected && pendingBuffers.isEmpty() && silenceFrameCount >= silenceThreshold) {
                    Log.i(TAG, "✋ VAD detected end of speech (${silenceFrameCount * CHUNK_DURATION_MS}ms silence), auto-stopping")
                    withContext(Dispatchers.Main) {
                        stopCapture()
                    }
                    break
                }
            }

            // No delay needed - AudioRecord.read() is blocking and paced by audio capture
        }
    }

    /**
     * Handle incoming websocket messages
     */
    private fun handleWebSocketMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.getString("type")

            when (type) {
                "connected" -> {
                    Log.d(TAG, "Conversation connected")
                }

                "recording" -> {
                    viewModel.updatePhase(ConversationPhase.LISTENING)
                }

                "transcript" -> {
                    val transcript = json.getString("text")
                    val isFinal = json.optBoolean("final", false)

                    // Mirror onto the on-screen status strip, partial and final alike, so
                    // the user can see they were heard without opening the chat overlay.
                    // Blank is skipped, never forwarded: a blank clears the registry and
                    // detaches the whole strip, so an empty partial would make the caption
                    // flicker out mid-sentence.
                    if (transcript.isNotBlank()) {
                        com.aura.aura_ui.services.AgentStatusRegistry.setHeard(transcript)
                    }

                    if (isFinal) {
                        // Promote the streaming bubble to final in-place (no duplicate).
                        // Falls back to a new bubble if no streaming bubble exists.
                        viewModel.finalizeStreamingUserMessage(transcript)
                        viewModel.updatePartialTranscript("")

                    } else {
                        // Grow the streaming bubble in-place as words arrive.
                        viewModel.startOrUpdateStreamingUserMessage(transcript)
                        viewModel.updatePartialTranscript(transcript)
                    }
                }

                "response" -> {
                    val responseText = json.getString("text")
                    // Use has() + getString() for more reliable audio extraction
                    val audioBase64 =
                        if (json.has("audio") && !json.isNull("audio")) {
                            json.getString("audio")
                        } else {
                            ""
                        }
                    val state = json.optString("state", "")
                    val intentType = json.optString("intent_type", "")
                    val readyForNext = json.optBoolean("ready_for_next_turn", true)

                    Log.d(
                        TAG,
                        "Response - state: $state, intent: $intentType, ready: $readyForNext, hasAudio: ${audioBase64.isNotEmpty()}, audioLen: ${audioBase64.length}",
                    )

                    viewModel.addAssistantMessage(responseText)
                    viewModel.updatePhase(ConversationPhase.RESPONDING)
                    
                    // Restore overlay if minimized (automation complete)
                    if (readyForNext) {
                        AuraOverlayService.restore(context)
                    }

                    // Speak response — suppressed when Gemini Live owns voice I/O
                    if (deviceControlOnly) {
                        Log.d(TAG, "🔇 TTS suppressed — deviceControlOnly mode (Gemini Live speaks)")
                        if (readyForNext) {
                            viewModel.resetToIdle()
                            listeningModeController.onCommandComplete()
                        }
                    } else {
                        val voicePrefs = context.getSharedPreferences("aura_voice_settings", Context.MODE_PRIVATE)
                        val voiceId = voicePrefs.getString("selected_voice_id", "en-US-AriaNeural") ?: "en-US-AriaNeural"
                        ttsManager.speak(responseText, voiceId) {
                            if (readyForNext) {
                                viewModel.resetToIdle()
                                listeningModeController.onCommandComplete()
                            }
                        }
                    }
                }

                "error" -> {
                    val message = json.optString("message", "Unknown error")
                    viewModel.setError(message)
                }

                "goodbye" -> {
                    viewModel.endSession()
                }

                "request_ui" -> {
                    // DEPRECATED: Use request_ui_tree instead
                    Log.w(TAG, "⚠️ DEPRECATED: request_ui received - use request_ui_tree instead")
                }

                "request_ui_tree" -> {
                    // Backend requests UI tree via Perception Controller
                    val requestId = json.optString("request_id", "")
                    val reason = json.optString("reason", "")
                    Log.i(TAG, "📋 Backend requested UI tree: request_id=$requestId, reason=$reason")
                    handleRequestUiTree(requestId, reason)
                }

                "request_screenshot" -> {
                    // Backend requests screenshot via Perception Controller
                    val requestId = json.optString("request_id", "")
                    val reason = json.optString("reason", "")
                    Log.i(TAG, "📸 Backend requested screenshot: request_id=$requestId, reason=$reason")
                    handleRequestScreenshot(requestId, reason)
                }

                "request_screen_capture_permission" -> {
                    // Backend requests us to prompt user for screen capture permission
                    Log.i(TAG, "🔓 Backend requested screen capture permission prompt")
                    handleRequestScreenCapturePermission()
                }

                "execute_step" -> {
                    // Backend wants to execute a step
                    val stepId = json.optString("step_id", "")
                    val action = json.optJSONObject("action")
                    Log.i(TAG, "⚡ Backend requested step execution: $stepId")
                    handleExecuteStep(stepId, action)
                }

                "launch_app" -> {
                    // Backend wants to launch an app via WebSocket
                    val packageName = json.optString("package_name", "")
                    val packageCandidates = json.optJSONArray("package_candidates")
                    val commandId = json.optString("command_id", "")
                    Log.i(TAG, "🚀 Launch app received: package=$packageName, commandId=$commandId")
                    handleLaunchApp(packageName, packageCandidates, commandId)
                }

                "launch_deep_link" -> {
                    // Backend wants to launch a deep link via WebSocket
                    val uri = json.optString("uri", "")
                    val packageName = json.optString("package_name", "").ifEmpty { null }
                    val commandId = json.optString("command_id", "")
                    Log.i(TAG, "🔗 Launch deep link received: uri=$uri, commandId=$commandId")
                    handleLaunchDeepLink(uri, packageName, commandId)
                }

                "resolve_contact" -> {
                    // Backend wants to resolve a contact name to phone number
                    val contactName = json.optString("contact_name", "")
                    val requestId = json.optString("request_id", "")
                    Log.i(TAG, "📞 Contact resolution requested: $contactName, requestId=$requestId")
                    handleResolveContact(contactName, requestId)
                }

                "execute_gesture" -> {
                    // Backend wants to execute a gesture via WebSocket
                    val gesture = json.optJSONObject("gesture")
                    val commandId = gesture?.optString("command_id", "")
                    Log.i(TAG, "⚡ Gesture received: commandId=$commandId, gesture=$gesture")
                    if (gesture != null) {
                        handleExecuteGesture(gesture, commandId)
                    }
                }

                "visual_feedback" -> {
                    // Backend wants to show visual feedback (edge glow / tap ripple)
                    Log.i(TAG, "✨ Visual feedback command received")
                    VisualFeedbackHandler.handleMessage(json)
                }
                
                "task_progress" -> {
                    // Backend sends task progress updates for todo-style display
                    val goal = json.optString("goal", "")
                    val tasks = json.optJSONArray("tasks")
                    val currentTask = json.optInt("current_task", 0)
                    val totalTasks = json.optInt("total_tasks", 0)
                    val isComplete = json.optBoolean("is_complete", false)
                    val isAborted = json.optBoolean("is_aborted", false)
                    
                    Log.i(TAG, "📋 Task progress: $currentTask/$totalTasks - $goal")
                    handleTaskProgress(goal, tasks, currentTask, totalTasks, isComplete, isAborted)
                }
                
                "agent_status" -> {
                    // Backend sends real-time agent activity updates
                    val agent = json.optString("agent", "Agent")
                    val output = json.optString("output", "")
                    Log.d(TAG, "🤖 Agent status: $agent → $output")
                    handleAgentStatus(agent, output)
                }
                
                "hitl_question" -> {
                    // Backend requests user input via HITL dialog.
                    // Speak the TTS announcement first (if provided) so the user hears
                    // "I have a question — which SIM card should I use?" before the
                    // dialog card animates in.
                    Log.i(TAG, "HITL question received")
                    val ttsText = json.optString("tts_text", "")
                    if (ttsText.isNotEmpty() && !deviceControlOnly) {
                        val voicePrefs = context.getSharedPreferences(
                            "aura_voice_settings", Context.MODE_PRIVATE
                        )
                        val voiceId = voicePrefs.getString(
                            "selected_voice_id", "en-US-AriaNeural"
                        ) ?: "en-US-AriaNeural"
                        ttsManager.speak(ttsText, voiceId) {
                            // Show the dialog only after TTS finishes so the user
                            // isn't reading and listening at the same time.
                            scope.launch(Dispatchers.Main) {
                                HITLHandler.handleMessage(json)
                            }
                        }
                    } else {
                        HITLHandler.handleMessage(json)
                    }
                }
                
                "hitl_dismiss" -> {
                    // Backend dismisses active HITL dialog
                    Log.i(TAG, "🔇 HITL dismiss received")
                    HITLHandler.handleDismiss(json)
                }

                else -> {
                    Log.w(TAG, "⚠️ Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}", e)
        }
    }
    
    /**
     * Handle agent_status message - show real-time agent activity
     * Updates ViewModel (for overlay UI) and Live Update notification (for minimized state)
     */
    private fun handleAgentStatus(agent: String, output: String) {
        scope.launch(Dispatchers.Main) {
            viewModel.addAgentOutput(agent, output)
            // Update the Live Update notification when minimized
            AuraOverlayService.getInstance()?.updateLiveNotification(agent, output)
        }
    }

    /**
     * Handle execute_step message - execute action and report result
     */
    private fun handleExecuteStep(
        stepId: String,
        action: JSONObject?,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                if (action == null) {
                    sendStepResult(stepId, false, "No action provided", null)
                    return@launch
                }

                val service = AuraAccessibilityService.instance
                if (service == null) {
                    sendStepResult(stepId, false, "AccessibilityService not available", null)
                    return@launch
                }

                val actionType = action.optString("type", "")
                val success = executeAction(service, actionType, action)

                // Brief delay to let UI update
                kotlinx.coroutines.delay(300)

                // Capture UI after action
                val uiAfter = service.getUITree()

                sendStepResult(stepId, success, if (success) null else "Action failed", uiAfter)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing step: ${e.message}")
                sendStepResult(stepId, false, e.message, null)
            }
        }
    }

    /**
     * Execute a single action via AccessibilityService
     */
    private fun executeAction(
        service: AuraAccessibilityService,
        actionType: String,
        action: JSONObject,
    ): Boolean {
        return when (actionType.lowercase()) {
            "tap", "click" -> {
                val x = action.optInt("x", -1)
                val y = action.optInt("y", -1)
                if (x >= 0 && y >= 0) {
                    service.performClick(x, y)
                    true
                } else {
                    false
                }
            }
            "swipe" -> {
                val x1 = action.optInt("x1", action.optInt("startX", -1))
                val y1 = action.optInt("y1", action.optInt("startY", -1))
                val x2 = action.optInt("x2", action.optInt("endX", -1))
                val y2 = action.optInt("y2", action.optInt("endY", -1))
                val duration = action.optLong("duration", 300)
                if (x1 >= 0 && y1 >= 0 && x2 >= 0 && y2 >= 0) {
                    service.performSwipe(x1, y1, x2, y2, duration)
                    true
                } else {
                    false
                }
            }
            "scroll_up" -> {
                service.performScroll("up")
                true
            }
            "scroll_down" -> {
                service.performScroll("down")
                true
            }
            "back" -> {
                service.performBack()
                true
            }
            "home" -> {
                service.performHome()
                true
            }
            "dismiss_keyboard" -> {
                service.dismissKeyboard()
                true
            }
            "press_enter" -> {
                service.performEnterAction()
            }
            "press_search" -> {
                service.pressKeyEvent(android.view.KeyEvent.KEYCODE_SEARCH)
                true
            }
            "type", "text_input", "input" -> {
                val text = action.optString("text", "")
                if (text.isNotEmpty()) {
                    val focusX = action.optInt("focus_x", -1)
                    val focusY = action.optInt("focus_y", -1)
                    service.performTextInput(text, focusX, focusY)
                } else {
                    false
                }
            }
            else -> false
        }
    }

    /**
     * Handle launch_app message - launch app via WebSocket
     */
    private fun handleLaunchApp(
        packageName: String,
        packageCandidates: org.json.JSONArray?,
        commandId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                // Minimize overlay during automation
                withContext(Dispatchers.Main) {
                    AuraOverlayService.minimize(context)
                }
                
                val executor = com.aura.aura_ui.executor.CommandExecutor(context)
                
                // Try package candidates in order
                val candidates = mutableListOf<String>()
                if (packageName.isNotEmpty()) {
                    candidates.add(packageName)
                }
                if (packageCandidates != null) {
                    for (i in 0 until packageCandidates.length()) {
                        val candidate = packageCandidates.optString(i, "")
                        if (candidate.isNotEmpty() && !candidates.contains(candidate)) {
                            candidates.add(candidate)
                        }
                    }
                }
                
                var success = false
                var error: String? = null
                
                for (pkg in candidates) {
                    val result = executor.executeCommand(
                        com.aura.aura_ui.data.Command(
                            commandId = commandId,
                            commandType = "launch_app",
                            payload = mapOf("package_name" to pkg),
                            createdAt = System.currentTimeMillis().toString(),
                        )
                    )
                    
                    if (result.success) {
                        success = true
                        Log.i(TAG, "✅ App launched: $pkg")
                        break
                    } else {
                        error = result.error
                        Log.w(TAG, "⚠️ Failed to launch $pkg: ${result.error}")
                    }
                }
                
                // Send result back to backend
                sendCommandResult(commandId, "launch_app", success, error)
            } catch (e: Exception) {
                Log.e(TAG, "Error launching app: ${e.message}")
                sendCommandResult(commandId, "launch_app", false, e.message)
            }
        }
    }

    /**
     * Handle launch_deep_link message - launch deep link via WebSocket
     */
    private fun handleLaunchDeepLink(
        uri: String,
        packageName: String?,
        commandId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val executor = com.aura.aura_ui.executor.CommandExecutor(context)
                
                val payload = mutableMapOf<String, Any?>("uri" to uri)
                if (packageName != null) {
                    payload["package_name"] = packageName
                }
                
                val result = executor.executeCommand(
                    com.aura.aura_ui.data.Command(
                        commandId = commandId,
                        commandType = "launch_deep_link",
                        payload = payload,
                        createdAt = System.currentTimeMillis().toString(),
                    )
                )
                
                // Send result back to backend
                sendCommandResult(commandId, "launch_deep_link", result.success, result.error)
            } catch (e: Exception) {
                Log.e(TAG, "Error launching deep link: ${e.message}")
                sendCommandResult(commandId, "launch_deep_link", false, e.message)
            }
        }
    }
    
    /**
     * Handle task_progress message - update Live Update notification with progress
     */
    private fun handleTaskProgress(
        goal: String,
        tasks: org.json.JSONArray?,
        currentTask: Int,
        totalTasks: Int,
        isComplete: Boolean,
        isAborted: Boolean
    ) {
        scope.launch(Dispatchers.Main) {
            try {
                // Parse task descriptions
                val taskDescriptions = mutableListOf<String>()
                if (tasks != null) {
                    for (i in 0 until tasks.length()) {
                        val task = tasks.getJSONObject(i)
                        taskDescriptions.add(task.optString("description", "Step ${i + 1}"))
                    }
                }

                // Update viewModel with task progress (shows skeleton steps in overlay)
                viewModel.updateTaskProgress(goal, taskDescriptions, currentTask, totalTasks, isComplete || isAborted)

                // Update Live Update notification with progress
                AuraOverlayService.getInstance()?.updateLiveNotificationProgress(
                    goal = goal,
                    current = currentTask,
                    total = totalTasks,
                    isComplete = isComplete,
                    isAborted = isAborted
                )

                // Minimize immediately when automation starts — overlay must not be
                // visible during task execution (it would cover the screen being automated).
                // The overlay restores only when the final goal is complete.
                if (!isComplete && !isAborted && currentTask <= 1) {
                    AuraOverlayService.minimize(context)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying task progress: ${e.message}")
            }
        }
    }

    /**
     * Handle execute_gesture message - execute tap/swipe/scroll via AccessibilityService
     */
    private fun handleExecuteGesture(gesture: org.json.JSONObject, commandId: String?) {
        scope.launch(Dispatchers.IO) {
            try {
                // Minimize overlay during gesture execution
                withContext(Dispatchers.Main) {
                    AuraOverlayService.minimize(context)
                }
                
                val service = com.aura.aura_ui.accessibility.AuraAccessibilityService.instance
                
                if (service == null) {
                    Log.e(TAG, "❌ AccessibilityService not available for gesture")
                    if (commandId != null) {
                        sendGestureAck(commandId, false, "AccessibilityService not available")
                    }
                    return@launch
                }
                
                val action = gesture.getString("action")
                Log.i(TAG, "⚡ Executing gesture: $action, commandId=$commandId")
                
                when (action.lowercase()) {
                    "tap", "click" -> {
                        val x = gesture.optInt("x", -1)
                        val y = gesture.optInt("y", -1)
                        if (x >= 0 && y >= 0) {
                            // Show visual feedback before tap
                            VisualFeedbackHandler.showTapAt(x, y)
                            // Pass commandId to performTap so it sends ack on success/failure
                            service.performTap(x, y, commandId)
                            Log.i(TAG, "✅ Tap dispatched at ($x, $y), commandId=$commandId")
                        } else {
                            Log.e(TAG, "❌ Invalid tap coordinates: x=$x, y=$y")
                            if (commandId != null) {
                                sendGestureAck(commandId, false, "Invalid coordinates: x=$x, y=$y")
                            }
                        }
                    }
                    "swipe" -> {
                        val x1 = gesture.optInt("x1", -1)
                        val y1 = gesture.optInt("y1", -1)
                        val x2 = gesture.optInt("x2", -1)
                        val y2 = gesture.optInt("y2", -1)
                        val duration = gesture.optLong("duration", 300L)
                        if (x1 >= 0 && y1 >= 0 && x2 >= 0 && y2 >= 0) {
                            // Pass commandId so gesture system sends ack on success/failure
                            service.performSwipe(x1, y1, x2, y2, duration, commandId)
                            Log.i(TAG, "✅ Swipe dispatched from ($x1, $y1) to ($x2, $y2), commandId=$commandId")
                        } else {
                            Log.e(TAG, "❌ Invalid swipe coordinates")
                            if (commandId != null) {
                                sendGestureAck(commandId, false, "Invalid swipe coordinates")
                            }
                        }
                    }
                    "long_press" -> {
                        val x = gesture.optInt("x", -1)
                        val y = gesture.optInt("y", -1)
                        val duration = gesture.optLong("duration", 1000L)
                        if (x >= 0 && y >= 0) {
                            // Pass commandId so gesture system sends ack on success/failure
                            service.performLongPress(x, y, duration, commandId)
                            Log.i(TAG, "✅ Long press dispatched at ($x, $y), commandId=$commandId")
                        } else {
                            Log.e(TAG, "❌ Invalid long press coordinates: x=$x, y=$y")
                            if (commandId != null) {
                                sendGestureAck(commandId, false, "Invalid coordinates: x=$x, y=$y")
                            }
                        }
                    }
                    "type", "input", "type_text", "text_input" -> {
                        val text = gesture.optString("text", "")
                        if (text.isNotEmpty()) {
                            // Optional field coords — sent by coordinator to avoid a tap
                            val focusX = gesture.optInt("focus_x", -1)
                            val focusY = gesture.optInt("focus_y", -1)
                            val success = service.performTextInput(text, focusX, focusY)
                            Log.i(TAG, "✅ Text input dispatched: '$text', success=$success, commandId=$commandId")
                            if (commandId != null) {
                                sendGestureAck(commandId, success, if (success) null else "Text input failed")
                            }
                        } else {
                            Log.e(TAG, "❌ Empty text for type action")
                            if (commandId != null) {
                                sendGestureAck(commandId, false, "Empty text provided")
                            }
                        }
                    }
                    "dismiss_keyboard" -> {
                        service.dismissKeyboard()
                        Log.i(TAG, "✅ Dismiss keyboard dispatched, commandId=$commandId")
                        if (commandId != null) {
                            sendGestureAck(commandId, true, null)
                        }
                    }
                    "press_enter" -> {
                        val success = service.performEnterAction()
                        Log.i(TAG, "✅ Press enter dispatched (performEnterAction), commandId=$commandId, success=$success")
                        if (commandId != null) {
                            sendGestureAck(commandId, success, if (success) null else "Enter action failed")
                        }
                    }
                    "press_search" -> {
                        service.pressKeyEvent(android.view.KeyEvent.KEYCODE_SEARCH)
                        Log.i(TAG, "✅ Press search dispatched, commandId=$commandId")
                        if (commandId != null) {
                            sendGestureAck(commandId, true, null)
                        }
                    }
                    "back" -> {
                        service.performBack()
                        Log.i(TAG, "✅ Back dispatched, commandId=$commandId")
                        if (commandId != null) {
                            sendGestureAck(commandId, true, null)
                        }
                    }
                    "home" -> {
                        service.performHome()
                        Log.i(TAG, "✅ Home dispatched, commandId=$commandId")
                        if (commandId != null) {
                            sendGestureAck(commandId, true, null)
                        }
                    }
                    else -> {
                        Log.w(TAG, "⚠️ Unknown gesture action: $action")
                        if (commandId != null) {
                            sendGestureAck(commandId, false, "Unknown action: $action")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error executing gesture: ${e.message}", e)
                if (commandId != null) {
                    sendGestureAck(commandId, false, e.message)
                }
            }
        }
    }

    /**
     * Send gesture acknowledgment back to backend
     */
    private fun sendGestureAck(commandId: String, success: Boolean, error: String?) {
        val response = org.json.JSONObject().apply {
            put("type", "gesture_ack")
            put("command_id", commandId)
            put("success", success)
            if (error != null) {
                put("error", error)
            }
        }
        webSocket?.send(response.toString())
        Log.i(TAG, "📤 Sent gesture ack: command_id=$commandId, success=$success")
    }

    /**
     * Send command result back to backend
     */
    private fun sendCommandResult(
        commandId: String,
        commandType: String,
        success: Boolean,
        error: String?,
    ) {
        val response = org.json.JSONObject().apply {
            put("type", "command_result")
            put("command_id", commandId)
            put("command_type", commandType)
            put("success", success)
            if (error != null) {
                put("error", error)
            }
        }
        webSocket?.send(response.toString())
        Log.i(TAG, "📤 Sent command result: $commandType, success=$success")
    }

    /**
     * Handle request_ui_tree message - send UI tree to backend via Perception Controller
     */
    private fun handleRequestUiTree(requestId: String, reason: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val service = AuraAccessibilityService.instance
                if (service == null) {
                    Log.w(TAG, "AccessibilityService not available for UI tree")
                    sendUiTreeResponse(requestId, null)
                    return@launch
                }

                val uiTree = service.getUITree()
                sendUiTreeResponse(requestId, uiTree)
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing UI tree: ${e.message}")
                sendUiTreeResponse(requestId, null)
            }
        }
    }

    /**
     * Send UI tree response to backend
     */
    private fun sendUiTreeResponse(requestId: String, uiTree: Map<String, Any>?) {
        val service = AuraAccessibilityService.instance
        val screenWidth = service?.getScreenWidth() ?: 1080
        val screenHeight = service?.getScreenHeight() ?: 1920
        
        val response = org.json.JSONObject().apply {
            put("type", "ui_tree_response")
            put("request_id", requestId)
            if (uiTree != null) {
                val uiTreeObj = org.json.JSONObject(uiTree)
                // Always include screen dimensions for backend device_info sync
                uiTreeObj.put("screen_width", screenWidth)
                uiTreeObj.put("screen_height", screenHeight)
                put("ui_tree", uiTreeObj)
            } else {
                put("ui_tree", org.json.JSONObject().apply {
                    put("elements", org.json.JSONArray())
                    put("screen_width", screenWidth)
                    put("screen_height", screenHeight)
                    put("orientation", "portrait")
                    put("timestamp", System.currentTimeMillis())
                })
            }
        }
        webSocket?.send(response.toString())
        Log.i(TAG, "📤 Sent UI tree response: request_id=$requestId, screen=${screenWidth}x${screenHeight}")
    }

    /**
     * Send device info with accurate screen dimensions to backend
     */
    private fun sendDeviceInfo(ws: WebSocket) {
        val service = AuraAccessibilityService.instance
        val screenWidth = service?.getScreenWidth() ?: 1080
        val screenHeight = service?.getScreenHeight() ?: 1920
        val screenCaptureAvailable = service?.isMediaProjectionAvailable() ?: false
        
        val deviceInfo = org.json.JSONObject().apply {
            put("type", "device_info")
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
            put("density_dpi", context.resources.displayMetrics.densityDpi)
            put("device_name", android.os.Build.MODEL)
            put("android_version", android.os.Build.VERSION.RELEASE)
            put("screen_capture_available", screenCaptureAvailable)
        }
        ws.send(deviceInfo.toString())
        Log.i(TAG, "📱 Sent device_info: ${screenWidth}x${screenHeight}, screen_capture=$screenCaptureAvailable")
    }

    /**
     * Handle request_screenshot message - send screenshot to backend via Perception Controller
     */
    private fun handleRequestScreenshot(requestId: String, reason: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val service = AuraAccessibilityService.instance
                if (service == null) {
                    Log.w(TAG, "AccessibilityService not available for screenshot")
                    sendScreenshotResponse(requestId, null)
                    return@launch
                }

                val screenshot = service.captureScreen()
                sendScreenshotResponse(requestId, screenshot)
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screenshot: ${e.message}")
                sendScreenshotResponse(requestId, null)
            }
        }
    }

    /**
     * Send screenshot response to backend
     */
    private fun sendScreenshotResponse(requestId: String, screenshot: String?) {
        val service = AuraAccessibilityService.instance
        // Get screen dimensions from screenCaptureManager via service
        val screenWidth = service?.getScreenWidth() ?: 1080
        val screenHeight = service?.getScreenHeight() ?: 1920
        
        val response = org.json.JSONObject().apply {
            put("type", "screenshot_response")
            put("request_id", requestId)
            put("screenshot_base64", screenshot ?: "")
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
            put("orientation", "portrait")  // Could be detected
            put("timestamp", System.currentTimeMillis())
        }
        webSocket?.send(response.toString())
        Log.i(TAG, "📤 Sent screenshot response: request_id=$requestId, size=${screenshot?.length ?: 0} bytes")
    }

    /**
     * Handle request_screen_capture_permission message - prompt user for MediaProjection permission
     */
    private fun handleRequestScreenCapturePermission() {
        scope.launch(Dispatchers.Main) {
            try {
                // Check if already have permission
                val service = AuraAccessibilityService.instance
                if (service?.isMediaProjectionAvailable() == true) {
                    Log.i(TAG, "📸 Screen capture already available, notifying backend")
                    // Notify backend that permission is already granted so it can update its state
                    AuraAccessibilityService.sendScreenCapturePermissionResult(granted = true)
                    return@launch
                }
                
                // Start MainActivity with flag to request screen capture permission
                // This works even when MainActivity is not in foreground
                val intent = android.content.Intent(context, com.aura.aura_ui.MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("REQUEST_SCREEN_CAPTURE", true)
                }
                context.startActivity(intent)
                Log.i(TAG, "📤 Started MainActivity to request screen capture permission")
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting screen capture permission: ${e.message}", e)
            }
        }
    }

    /**
     * Send step result to backend
     */
    private fun sendStepResult(
        stepId: String,
        success: Boolean,
        error: String?,
        uiAfter: Map<String, Any>?,
    ) {
        val response =
            JSONObject().apply {
                put("type", "step_result")
                put("step_id", stepId)
                put("success", success)
                if (error != null) put("error", error)
                put("ui_after", if (uiAfter != null) JSONObject(uiAfter) else JSONObject())
            }
        webSocket?.send(response.toString())
        Log.i(TAG, "📤 Sent step result: stepId=$stepId, success=$success")
    }

    /**
     * Send start message to backend with TTS voice preference
     */
    private fun sendStartMessage() {
        val voicePrefs = context.getSharedPreferences("aura_voice_settings", Context.MODE_PRIVATE)
        val voiceId = voicePrefs.getString("selected_voice_id", "en-US-AriaNeural") ?: "en-US-AriaNeural"
        
        val message =
            JSONObject().apply {
                put("type", "start")
                put("session_id", sessionId)
                put("voice_id", voiceId)
            }
        webSocket?.send(message.toString())
        Log.d(TAG, "📤 Sent start message with voice: $voiceId")
    }

    /**
     * Send end turn message to backend
     */
    private fun sendEndTurnMessage() {
        val message =
            JSONObject().apply {
                put("type", "end_turn")
            }
        webSocket?.send(message.toString())
    }

    /**
     * Send text command — routes through local Function Gemma first if available.
     * LOCAL_ONLY actions are handled entirely on-device.
     * HYBRID actions execute locally, then send context to backend.
     * Unrecognized commands fall through to backend normally.
     */
    fun sendTextCommand(text: String) {
        if (text.isBlank()) return

        Log.i(TAG, "📝 Sending text command: $text")

        viewModel.addUserMessage(text)
        viewModel.updatePhase(ConversationPhase.THINKING)

        sendToBackend(text)
    }

    /**
     * Send a command to the backend via WebSocket (normal flow).
     */
    private fun sendToBackend(text: String) {
        if (webSocket == null) {
            Log.w(TAG, "WebSocket not connected — cannot send command to backend")
            scope.launch {
                viewModel.addAssistantMessage("Server not connected. Connect to the server and try again.")
                viewModel.updatePhase(ConversationPhase.IDLE)
            }
            return
        }
        val voicePrefs = context.getSharedPreferences("aura_voice_settings", Context.MODE_PRIVATE)
        val voiceId = voicePrefs.getString("selected_voice_id", "en-US-AriaNeural") ?: "en-US-AriaNeural"
        
        val message = JSONObject().apply {
            put("type", "text_input")
            put("text", text)
            put("session_id", sessionId)
            put("voice_id", voiceId)
        }
        webSocket?.send(message.toString())
        Log.i(TAG, "📤 Text command sent to backend with voice: $voiceId")
    }

    /**
     * Send a hybrid command to backend with local execution context.
     * Backend knows what was already done locally and can skip duplicate steps.
     */
    private fun sendHybridToBackend(text: String, localContext: JSONObject) {
        val voicePrefs = context.getSharedPreferences("aura_voice_settings", Context.MODE_PRIVATE)
        val voiceId = voicePrefs.getString("selected_voice_id", "en-US-AriaNeural") ?: "en-US-AriaNeural"
        
        val message = JSONObject().apply {
            put("type", "text_input")
            put("text", text)
            put("session_id", sessionId)
            put("voice_id", voiceId)
            put("local_context", localContext)
        }
        webSocket?.send(message.toString())
        Log.i(TAG, "📤 Hybrid command sent to backend with local context")
    }

    /**
     * Send cancel task message to backend
     */
    fun sendCancelTask() {
        Log.i(TAG, "🚫 Sending cancel_task to backend")
        val message = JSONObject().apply {
            put("type", "cancel_task")
            put("session_id", sessionId)
        }
        webSocket?.send(message.toString())
    }

    /**
     * End conversation
     */
    fun endConversation() {
        if (isRecording.get()) {
            stopCapture()
        }

        val message =
            JSONObject().apply {
                put("type", "end_conversation")
            }
        webSocket?.send(message.toString())

        cleanup()
        viewModel.endSession()
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        webSocket?.close(1000, "Cleanup")
        webSocket = null

        ttsManager.release()
        
        // Reset amplitude
        onAmplitudeUpdate?.invoke(0f)
        
        // Update connection state
        scope.launch {
            viewModel.updateServerConnection(false)
        }

        Log.d(TAG, "Controller cleanup complete")
    }

    /**
     * Calculate audio amplitude for waveform visualization
     */
    private fun calculateAmplitude(buffer: ByteArray, bytesRead: Int): Float {
        if (bytesRead == 0) return 0f
        
        // Convert bytes to shorts (16-bit PCM)
        var sum = 0.0
        var i = 0
        while (i < bytesRead - 1) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += (sample * sample).toDouble()
            i += 2
        }
        
        val rms = kotlin.math.sqrt(sum / (bytesRead / 2))
        // Normalize to 0-1 range
        return (rms.toFloat() / 32767f).coerceIn(0f, 1f)
    }

    /**
     * Handle contact resolution request from backend.
     * Queries Android contacts and returns phone number if found.
     */
    private fun handleResolveContact(contactName: String, requestId: String) {
        try {
            val phoneNumber = ContactResolver.findPhoneNumber(context, contactName)
            
            if (phoneNumber != null) {
                val cleaned = ContactResolver.cleanPhoneNumber(phoneNumber)
                Log.i(TAG, "✅ Contact resolved: $contactName → $cleaned")
                
                // Send success response
                sendContactResolutionResult(requestId, contactName, cleaned, success = true, error = null)
            } else {
                Log.w(TAG, "⚠️ Contact not found: $contactName")
                sendContactResolutionResult(requestId, contactName, null, success = false, error = "Contact not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Contact resolution failed", e)
            sendContactResolutionResult(requestId, contactName, null, success = false, error = e.message)
        }
    }

    /**
     * Send contact resolution result back to backend via WebSocket.
     */
    private fun sendContactResolutionResult(
        requestId: String,
        contactName: String,
        phoneNumber: String?,
        success: Boolean,
        error: String?
    ) {
        val message = JSONObject().apply {
            put("type", "contact_resolution_result")
            put("request_id", requestId)
            put("contact_name", contactName)
            put("phone_number", phoneNumber)
            put("success", success)
            if (error != null) {
                put("error", error)
            }
        }

        webSocket?.send(message.toString())
        Log.i(TAG, "📤 Sent contact resolution result: $contactName → ${phoneNumber ?: "not found"}")
    }
}

/**
 * Simple Voice Activity Detection
 * Uses energy-based detection with improved noise adaptation and dynamic thresholding
 */
class SimpleVAD {
    private val baseEnergyThreshold = 300.0
    private var smoothedEnergy = 0.0
    private val smoothingFactor = 0.6 // Higher smoothing to reduce noise spikes

    // Adaptive noise floor tracking
    private var noiseFloor = 100.0
    private var noiseFloorMax = 100.0 // Track maximum noise floor
    private val noiseAdaptRateFast = 0.05 // Fast adaptation during initialization
    private val noiseAdaptRateSlow = 0.005 // Slow adaptation during speech
    private var frameCount = 0

    // Zero-crossing rate for better speech detection
    private var lastSample: Short = 0
    private val zcThreshold = 50 // Minimum zero crossings for speech

    fun isSpeech(
        audioData: ByteArray,
        length: Int,
    ): Boolean {
        var energy = 0.0
        var zeroCrossings = 0
        var currentSample: Short

        // Calculate RMS energy and zero-crossing rate
        for (i in 0 until length step 2) {
            if (i + 1 < length) {
                currentSample = (audioData[i].toInt() or (audioData[i + 1].toInt() shl 8)).toShort()
                energy += currentSample * currentSample

                // Count zero crossings (sign changes indicate speech frequencies)
                if ((lastSample >= 0 && currentSample < 0) || (lastSample < 0 && currentSample >= 0)) {
                    zeroCrossings++
                }
                lastSample = currentSample
            }
        }

        energy = Math.sqrt(energy / (length / 2))
        frameCount++

        // Smooth energy with exponential moving average
        smoothedEnergy = smoothingFactor * energy + (1 - smoothingFactor) * smoothedEnergy

        // Adaptive noise floor with faster initial calibration
        val adaptRate = if (frameCount < 30) noiseAdaptRateFast else noiseAdaptRateSlow

        // Update noise floor when energy is consistently low (likely background noise)
        if (smoothedEnergy < baseEnergyThreshold * 1.5) {
            noiseFloor = adaptRate * smoothedEnergy + (1 - adaptRate) * noiseFloor
            noiseFloorMax = Math.max(noiseFloorMax, noiseFloor)
        }

        // Dynamic threshold based on noise floor with higher margin for noisy environments
        // Use 3.5x multiplier instead of 2.5x to avoid false positives in noise
        val dynamicThreshold = Math.max(baseEnergyThreshold, noiseFloor * 3.5)

        // Combine energy and zero-crossing rate for robust detection
        // Speech typically has higher energy AND higher zero-crossing rate than noise
        val energyCheck = smoothedEnergy > dynamicThreshold
        val zeroCrossingCheck = zeroCrossings > zcThreshold

        // Require BOTH conditions for speech detection in noisy environments (after calibration)
        val isSpeech =
            if (frameCount > 30 && noiseFloor > 200.0) {
                // Noisy environment: require both energy and zero-crossing criteria
                energyCheck && zeroCrossingCheck
            } else {
                // Quiet environment or still calibrating: energy alone is sufficient
                energyCheck
            }

        return isSpeech
    }

    fun reset() {
        smoothedEnergy = 0.0
        noiseFloor = 100.0
        noiseFloorMax = 100.0
        frameCount = 0
        lastSample = 0
    }
}
