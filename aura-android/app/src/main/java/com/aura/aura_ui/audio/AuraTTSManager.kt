package com.aura.aura_ui.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device TTS using Android's built-in TextToSpeech API.
 *
 * Maps edge-tts style voice IDs (stored in SharedPreferences) to the best available
 * Android voice for that locale and gender. On devices with Google TTS enhanced packs,
 * this delivers QUALITY_VERY_HIGH neural voices — same neural models as Google Cloud TTS.
 *
 * Eliminates all server-side audio generation: no edge_tts, no pydub, no ffmpeg,
 * no base64 audio transfer over WebSocket.
 */
class AuraTTSManager(context: Context) {

    private val TAG = "AuraTTSManager"
    private var tts: TextToSpeech? = null
    private var isReady = false
    private val pendingCallbacks = ConcurrentHashMap<String, () -> Unit>()

    // (locale, gender, index-within-gender) per edge-tts voice ID
    private data class VoiceProfile(val locale: Locale, val gender: String, val index: Int)

    private val voiceProfiles = mapOf(
        "en-US-AriaNeural"        to VoiceProfile(Locale.US,              "female", 0),
        "en-US-JennyNeural"       to VoiceProfile(Locale.US,              "female", 1),
        "en-US-EmmaNeural"        to VoiceProfile(Locale.US,              "female", 2),
        "en-US-GuyNeural"         to VoiceProfile(Locale.US,              "male",   0),
        "en-US-ChristopherNeural" to VoiceProfile(Locale.US,              "male",   1),
        "en-GB-SoniaNeural"       to VoiceProfile(Locale("en", "GB"),     "female", 0),
        "en-GB-RyanNeural"        to VoiceProfile(Locale("en", "GB"),     "male",   0),
        "en-AU-NatashaNeural"     to VoiceProfile(Locale("en", "AU"),     "female", 0),
    )

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                // Route TTS through the voice-communication path (not the default
                // media/music stream). This is what lets the mic's AcousticEchoCanceler
                // use the assistant's own speech as its far-end reference and cancel it —
                // the prerequisite for barge-in that doesn't hear itself. Mirrors
                // PcmStreamPlayer's USAGE_VOICE_COMMUNICATION on the Live path.
                applyAudioRouting()
                Log.i(TAG, "TTS ready — ${tts?.voices?.size ?: 0} voices available (voice-comm routing)")
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
     * Speak [text] using the voice matching [voiceId].
     * [onComplete] is invoked when playback finishes (or immediately if TTS isn't ready).
     * Stops any currently playing utterance before starting.
     */
    fun speak(text: String, voiceId: String, onComplete: (() -> Unit)? = null) {
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
        // Re-applied per utterance, not just at init: the developer recordable-voice switch can
        // be flipped mid-process, and TTS attributes set once at construction would keep the old
        // route until the app restarted — which reads as "the toggle does nothing".
        applyAudioRouting()
        applyVoice(voiceId)
        val utteranceId = UUID.randomUUID().toString()
        if (onComplete != null) pendingCallbacks[utteranceId] = onComplete
        tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Route TTS through the voice-communication path (not the default media/music stream). That
     * is what lets the mic's AcousticEchoCanceler use the assistant's own speech as its far-end
     * reference and cancel it — the prerequisite for barge-in that doesn't hear itself. Mirrors
     * PcmStreamPlayer on the Live path, including the recordable-voice override.
     */
    private fun applyAudioRouting() {
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(RecordableVoiceStore.audioUsage())
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    /** Stop any ongoing utterance and clear pending callbacks. */
    fun stop() {
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
