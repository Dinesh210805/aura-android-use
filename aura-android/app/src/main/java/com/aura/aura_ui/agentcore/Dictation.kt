package com.aura.aura_ui.agentcore

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.aura.aura_ui.agent.voice.LocalSttController
import kotlinx.coroutines.launch

/** Voice typing for the composer: what is said becomes text in the box, nothing is sent. */
@Stable
class Dictation internal constructor() {
    var listening by mutableStateOf(false)
        internal set
    internal var finishing = false
    internal var onToggle: () -> Unit = {}

    fun toggle() = onToggle()
}

/**
 * Remembers a [Dictation] that hands each transcript to [onText].
 *
 * - Contract: uses the on-device Moonshine model when it is downloaded (nothing leaves the phone,
 *   and voice-activity detection ends the capture by itself); otherwise Android's speech
 *   recogniser, preferring offline. Tapping again while listening stops and transcribes.
 * - Fails: no microphone permission asks for it; no recogniser on the phone, or audio too short
 *   to transcribe, shows a toast and leaves the text as it was.
 */
@Composable
fun rememberDictation(onText: (String) -> Unit): Dictation {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deliver by rememberUpdatedState(onText)
    val stt = remember { LocalSttController(context.applicationContext) }
    val dictation = remember { Dictation() }
    DisposableEffect(stt) { onDispose { stt.cancel() } }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun finish() {
        if (dictation.finishing || !dictation.listening) return
        dictation.finishing = true
        scope.launch {
            val heard = runCatching { stt.stopAndTranscribe() }
            dictation.listening = false
            dictation.finishing = false
            heard.onSuccess { if (it.isNotBlank()) deliver(it.trim()) }
                .onFailure { toast(it.message ?: "Could not hear that.") }
        }
    }

    fun startLocal() {
        dictation.listening = true
        stt.start(onSpeechEnd = { scope.launch { finish() } })
    }

    val recognizer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(deliver)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLocal() else toast("AURA needs the microphone to type by voice.")
    }

    dictation.onToggle = {
        when {
            dictation.listening -> finish()
            stt.isModelReady() && !stt.hasMicPermission() -> permission.launch(Manifest.permission.RECORD_AUDIO)
            stt.isModelReady() -> startLocal()
            else -> runCatching {
                recognizer.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
                )
            }.onFailure { toast("Voice typing is not available on this phone.") }
        }
    }
    return dictation
}
