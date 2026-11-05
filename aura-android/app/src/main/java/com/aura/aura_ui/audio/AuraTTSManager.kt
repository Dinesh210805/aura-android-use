package com.aura.aura_ui.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.aura.aura_ui.voice.PiperSpeaker
import com.aura.aura_ui.voice.LocalVoices
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place AURA speaks: the selected Piper voice (on-device neural) when downloaded, else Android TextToSpeech.
 *
 * - Contract: [speak] replaces anything playing; `onStart` fires when audio begins (or the utterance is
 *   skipped), `onComplete` when playback ends, never after [stop]. Both may run off the main thread.
 * - Fails: selected voice not downloaded → system TTS; system TTS not bound yet → utterance skipped, `onStart` and
 *   `onComplete` run.
 * - Change together: `voiceProfiles` with `LocalVoices.all` (same ids).
 */
class AuraTTSManager(context: Context) {

    private val appContext = context.applicationContext

    private val TAG = "AuraTTSManager"
    private var tts: TextToSpeech? = null
    private var isReady = false
    private val pendingCallbacks = ConcurrentHashMap<String, () -> Unit>()

    // (locale, gender, index-within-gender) per edge-tts voice ID
    private data class VoiceProfile(val locale: Locale, val gender: String, val index: Int)

    private val voiceProfiles = mapOf(
        "en-US-AriaNeural"        to VoiceProfile(Locale.US,              "female", 0),
        "en-US-JennyNeural"       to VoiceProfile(Locale.US,              "female", 1),
        "en-US-GuyNeural"         to VoiceProfile(Locale.US,              "male",   0),
        "en-US-ChristopherNeural" to VoiceProfile(Locale.US,              "male",   1),
        "en-GB-SoniaNeural"       to VoiceProfile(Locale("en", "GB"),     "female", 0),
        "en-GB-RyanNeural"        to VoiceProfile(Locale("en", "GB"),     "male",   0),
    )

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                applyAudioRouting()
                Log.i(TAG, "TTS ready — ${tts?.voices?.size ?: 0} voices available")
            } else {
                Log.e(TAG, "TTS init failed: status=$status")
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = fireCallback(utteranceId)
            @Deprecated("Deprecated in API level 21")
            override fun onError(utteranceId: String) = fireCallback(utteranceId)
        })
    }

    /**
     * Speak [text] using the voice matching [voiceId]. [cache] keeps the neural audio on disk for
     * replay — only for a small fixed set of phrases (see `PiperSpeaker.speak`).
     */
    fun speak(
        text: String,
        voiceId: String,
        cache: Boolean = false,
        onStart: () -> Unit = {},
        onComplete: (() -> Unit)? = null,
    ) {
        tts?.stop()
        // PiperSpeaker publishes its own caption, word by word as it plays.
        val voice = LocalVoices.byId(voiceId)
        if (PiperSpeaker.speak(appContext, text, voice, cache, onStart) { onComplete?.invoke() }) return
        // System TTS starts within a few ms of the request, so "started" is signalled up front.
        onStart()
        if (!isReady || tts == null) {
            Log.w(TAG, "TTS not ready, skipping utterance")
            onComplete?.invoke()
            return
        }
        tts!!.stop()
        // The status strip shows what AURA *said*, and this is the one place every spoken
        // sentence passes through — publishing here rather than at the call sites is what
        // stops a future speech path from silently missing the strip.
        com.aura.aura_ui.services.AgentStatusRegistry.setSpoken(text)
        applyVoice(voiceId)
        val utteranceId = UUID.randomUUID().toString()
        if (onComplete != null) pendingCallbacks[utteranceId] = onComplete
        tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** Play as media: speaker output, media volume, full-band audio. Must match `PiperSpeaker`. */
    private fun applyAudioRouting() {
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    /** Stop any ongoing utterance and clear pending callbacks. */
    fun stop() {
        PiperSpeaker.stop()
        tts?.stop()
        pendingCallbacks.clear()
    }

    /** Release the TTS engine. Call when the owning component is destroyed. */
    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    // ─── Voice selection ──────────────────────────────────────────────────────

    private fun applyVoice(voiceId: String) {
        val profile = voiceProfiles[voiceId] ?: VoiceProfile(Locale.US, "female", 0)
        val allVoices = tts?.voices
        if (allVoices.isNullOrEmpty()) {
            tts?.language = profile.locale
            return
        }

        // Candidates: match locale, prefer offline, require at least QUALITY_HIGH
        val candidates = allVoices
            .filter { v ->
                v.locale.language == profile.locale.language &&
                v.locale.country  == profile.locale.country  &&
                !v.isNetworkConnectionRequired &&
                v.quality >= Voice.QUALITY_HIGH
            }
            .sortedByDescending { it.quality }

        // Try gender heuristic from voice name (not guaranteed by API but widespread)
        val genderMatches = candidates.filter { v ->
            val name = v.name.lowercase()
            if (profile.gender == "female")
                name.contains("female") || name.contains("#f") || name.contains("-f-")
            else
                (name.contains("male") && !name.contains("female")) ||
                name.contains("#m") || name.contains("-m-")
        }

        val pool = genderMatches.ifEmpty { candidates }
        val chosen = pool.getOrElse(profile.index) { pool.firstOrNull() }

        if (chosen != null) {
            tts?.voice = chosen
            Log.d(TAG, "Voice: ${chosen.name} quality=${chosen.quality} for $voiceId")
        } else {
            tts?.language = profile.locale
            Log.d(TAG, "Locale fallback: ${profile.locale} for $voiceId")
        }
    }

    private fun fireCallback(utteranceId: String) {
        pendingCallbacks.remove(utteranceId)?.invoke()
    }
}
