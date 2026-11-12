package com.aura.aura_ui.voice.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The Gemini 3.8 Live wire format for AURA's voice: AURA's ears and mouth, never its brain.
 *
 * - Contract: pure, no Android. [parse] never throws; an unknown or malformed frame becomes
 *   [LiveEvent.Unknown]. One frame can carry several events (3.8 sends multi-part content, and
 *   `usageMetadata` / `voiceActivity` sit beside the message), so [parse] returns them all, in order.
 * - Why no tools and no thinking: AgentCore decides everything. Live only hears the user and says
 *   the words AgentCore wrote, which it receives as a `SAY:` client turn ([say]). `gemini-3.8-live`
 *   rejects `thinkingConfig`, and a native-audio model takes no `languageCode`, so neither is sent.
 * - Change together: [RELAY_INSTRUCTION] and `LiveVoice`'s output gate, which drops any audio that
 *   does not answer a [say].
 */
object LiveWire {
    const val MODEL = "models/gemini-3.8-live"
    const val ENDPOINT =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /** Mic audio in: 16 kHz mono 16-bit little-endian PCM. */
    const val INPUT_RATE = 16_000

    const val SAY_PREFIX = "SAY: "

    /** How the voice behaves. Short: it is the whole of Live's job. */
    const val RELAY_INSTRUCTION = """You are the voice of AURA, a phone assistant. You are only its ears and its mouth.
Rules:
1. When the person speaks, do not answer, do not acknowledge, stay silent. Another system listens and decides.
2. When you receive a message that starts with "SAY:", speak the text after it, word for word, in a warm natural voice, in the language it is written in. Add nothing, drop nothing, do not comment on it.
3. Never speak at any other time."""

    private val json = Json { ignoreUnknownKeys = true }

    data class Setup(
        val voice: String,
        val vocabulary: List<String> = emptyList(),
        val resumeHandle: String? = null,
        /** False drops every optional field, for a server that rejected the full setup. */
        val full: Boolean = true,
    )

    fun setup(s: Setup): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", MODEL)
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", RELAY_INSTRUCTION) }) }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", s.voice) } }
                }
            }
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") {
                    // Start eagerly, end patiently: a fragmented turn is a fragmented transcript.
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                    put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                    put("prefixPaddingMs", PREFIX_PADDING_MS)
                    put("silenceDurationMs", SILENCE_MS)
                }
                put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
            }
            putJsonObject("inputAudioTranscription") {
                if (s.full && s.vocabulary.isNotEmpty()) {
                    putJsonArray("customVocabulary") { s.vocabulary.take(MAX_VOCABULARY).forEach { add(it) } }
                }
            }
            putJsonObject("outputAudioTranscription") {}
            putJsonObject("contextWindowCompression") {
                put("triggerTokens", TRIGGER_TOKENS)
                putJsonObject("slidingWindow") { put("targetTokens", TARGET_TOKENS) }
            }
            // Sent on the first connect too: without it the server never issues a handle to resume with.
            putJsonObject("sessionResumption") { s.resumeHandle?.let { put("handle", it) } }
        }
    }.toString()

    fun audio(base64Pcm: String): String = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") {
                put("mimeType", "audio/pcm;rate=$INPUT_RATE")
                put("data", base64Pcm)
            }
        }
    }.toString()

    /** The mic paused: the server flushes its buffered audio and closes the user's turn now. */
    fun audioStreamEnd(): String = buildJsonObject { putJsonObject("realtimeInput") { put("audioStreamEnd", true) } }.toString()

    /** Ask the voice to speak [text] exactly. */
    fun say(text: String): String = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", SAY_PREFIX + text) }) }
                    },
                )
            }
            put("turnComplete", true)
        }
    }.toString()

    fun parse(frame: String): List<LiveEvent> = runCatching {
        val root = json.parseToJsonElement(frame).jsonObject
        val out = ArrayList<LiveEvent>(4)
        if (root.containsKey("setupComplete")) out += LiveEvent.SetupComplete
        root.obj("serverContent")?.let { content(it, out) }
        root.obj("voiceActivity")?.let { va ->
            when (va.str("voiceActivityType") ?: va.str("type")) {
                "ACTIVITY_START" -> out += LiveEvent.UserActivity(started = true)
                "ACTIVITY_END" -> out += LiveEvent.UserActivity(started = false)
            }
        }
        root.obj("sessionResumptionUpdate")?.let { u ->
            out += LiveEvent.Resumption(u.str("newHandle"), u.bool("resumable") ?: false)
        }
        root.obj("goAway")?.let { out += LiveEvent.GoAway(durationMs(it.str("timeLeft"))) }
        root.obj("usageMetadata")?.let { out += LiveEvent.Usage(it["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0) }
        if (out.isEmpty()) out += LiveEvent.Unknown(frame.take(UNKNOWN_KEEP))
        out
    }.getOrElse { listOf(LiveEvent.Unknown(frame.take(UNKNOWN_KEEP))) }

    private fun content(sc: JsonObject, out: MutableList<LiveEvent>) {
        sc.obj("modelTurn")?.get("parts")?.jsonArray?.forEach { part ->
            part.jsonObject.obj("inlineData")?.str("data")?.let { b64 ->
                runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()?.let { out += LiveEvent.Audio(it) }
            }
        }
        sc.obj("interimInputTranscription")?.str("text")?.let { out += LiveEvent.HeardPartial(it) }
        sc.obj("inputTranscription")?.str("text")?.let { out += LiveEvent.Heard(it) }
        sc.obj("outputTranscription")?.str("text")?.let { out += LiveEvent.Spoken(it) }
        if (sc.bool("interrupted") == true) out += LiveEvent.Interrupted
        if (sc.bool("generationComplete") == true) out += LiveEvent.GenerationComplete
        if (sc.bool("turnComplete") == true) out += LiveEvent.TurnComplete
    }

    /** "9.5s" → 9500. Null on anything else. */
    internal fun durationMs(text: String?): Long? =
        text?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong() }

    private fun JsonObject.obj(key: String): JsonObject? = runCatching { this[key]?.jsonObject }.getOrNull()
    private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
    private fun JsonObject.bool(key: String): Boolean? = runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

    /** The docs recommend 500–800 ms; a pause mid-sentence must not end the turn. */
    const val SILENCE_MS = 700

    /** Enough leading audio that the first consonant is not clipped. */
    const val PREFIX_PADDING_MS = 200

    const val TRIGGER_TOKENS = 25_000
    const val TARGET_TOKENS = 8_000
    const val MAX_VOCABULARY = 100
    private const val UNKNOWN_KEEP = 400
}

/** One thing the Live server told us. */
sealed interface LiveEvent {
    data object SetupComplete : LiveEvent
    class Audio(val pcm24k: ByteArray) : LiveEvent
    data class HeardPartial(val text: String) : LiveEvent
    data class Heard(val text: String) : LiveEvent
    data class Spoken(val text: String) : LiveEvent
    data object Interrupted : LiveEvent
    data object GenerationComplete : LiveEvent
    data object TurnComplete : LiveEvent
    data class UserActivity(val started: Boolean) : LiveEvent
    data class Resumption(val handle: String?, val resumable: Boolean) : LiveEvent
    data class GoAway(val timeLeftMs: Long?) : LiveEvent
    data class Usage(val totalTokens: Int) : LiveEvent
    data class Unknown(val raw: String) : LiveEvent
}
