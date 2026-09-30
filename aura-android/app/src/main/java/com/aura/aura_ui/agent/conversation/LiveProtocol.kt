package com.aura.aura_ui.agent.conversation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
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

/** The connect-time Live session setup (D7: contextWindowCompression mandatory). */
data class LiveSessionConfig(
    val model: String,
    val voice: String,
    val systemInstruction: String,
    val tools: JsonArray,
    val triggerTokens: Int,
    val slidingWindow: Int,
    val languageCode: String = "en-US",
    val thinkingLevel: String = "minimal",
    val silenceDurationMs: Int = 600,
    val resumeHandle: String? = null,
    /**
     * Gemini's own Google Search, executed server-side inside the session. It is what lets AURA
     * answer "what's in the news" or "is that cafe open" while the user keeps talking, instead of
     * escalating to a phone run that opens a browser on their screen. Uses the same BYOK key.
     *
     * Off: on a free-tier key, gemini-3.1-flash-live-preview answers a setup carrying googleSearch
     * with close 1011 "exceeded your current quota" before setupComplete, so the session never opens
     * and AURA goes silent (measured 2026-09-14; the native-audio model accepted it). Enable only
     * behind a check that the key's plan covers grounding.
     */
    val googleSearch: Boolean = false,
)

/** A parsed inbound Live server frame. Unparsed is the fail-soft sink for malformed/unknown frames. */
sealed interface LiveServerMessage {
    data object SetupComplete : LiveServerMessage
    data class ServerContent(
        val audio: ByteArray? = null,
        val text: String? = null,
        val userTranscript: String? = null,
        val modelTranscript: String? = null,
        val turnComplete: Boolean = false,
        val generationComplete: Boolean = false,
        val interrupted: Boolean = false,
    ) : LiveServerMessage
    data class ToolCall(val calls: List<CompanionToolCall>) : LiveServerMessage
    data class ToolCallCancellation(val ids: List<String>) : LiveServerMessage
    data class SessionResumptionUpdate(val handle: String?, val resumable: Boolean) : LiveServerMessage
    data class Usage(val totalTokens: Int, val promptTokens: Int, val responseTokens: Int) : LiveServerMessage
    data class GoAway(val timeLeftMillis: Long?) : LiveServerMessage
    data class Unparsed(val raw: String) : LiveServerMessage
}

/**
 * Pure Live (BidiGenerateContent) framing. buildSetup serialises a LiveSessionConfig into the setup
 * message; parseServerMessage defensively classifies an inbound frame -- untrusted input, so a
 * malformed/oversized/unknown frame degrades to Unparsed and NEVER throws (adversarial-by-default).
 */
object LiveProtocol {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun buildSetup(config: LiveSessionConfig): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", config.model)
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", config.systemInstruction) }) }
            }
            putJsonArray("tools") {
                if (config.googleSearch) add(buildJsonObject { putJsonObject("googleSearch") {} })
                add(buildJsonObject { put("functionDeclarations", config.tools) })
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") { put("voiceName", config.voice) }
                    }
                    if (config.languageCode.isNotBlank()) put("languageCode", config.languageCode)
                }
                if (config.thinkingLevel.isNotBlank()) {
                    putJsonObject("thinkingConfig") { put("thinkingLevel", config.thinkingLevel) }
                }
            }
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") {
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                    put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                    put("prefixPaddingMs", 20)
                    put("silenceDurationMs", config.silenceDurationMs)
                }
                // Sibling of automaticActivityDetection, not a child of it (docs: RealtimeInputConfig).
                // UNSPECIFIED already defaults to this, but state it so a future default change, or a
                // half-cascade model with different defaults, cannot silently disable barge-in.
                put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
            }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            putJsonObject("contextWindowCompression") {
                put("triggerTokens", config.triggerTokens)
                putJsonObject("slidingWindow") { put("targetTokens", config.slidingWindow) }
            }
            config.resumeHandle?.let { handle ->
                putJsonObject("sessionResumption") { put("handle", handle) }
            }
        }
    }.toString()

    /**
     * A `realtimeInput` mic-audio frame using the current `audio` field (the older `mediaChunks`
     * form is legacy). [b64Pcm] is base64 16 kHz mono 16-bit little-endian PCM.
     */
    fun buildRealtimeAudio(b64Pcm: String): String = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") {
                put("mimeType", "audio/pcm;rate=16000")
                put("data", b64Pcm)
            }
        }
    }.toString()

    /**
     * `audioStreamEnd` — MUST be sent whenever the mic stream pauses >1 s while the session stays up
     * (drive_phone runs, push-to-stop). Flushes the server's cached audio so VAD/turn state doesn't go
     * stale; without it the first turn after a long pause misbehaves (docs: Live API capabilities §VAD).
     */
    fun buildAudioStreamEnd(): String = buildJsonObject {
        putJsonObject("realtimeInput") { put("audioStreamEnd", true) }
    }.toString()

    /**
     * A `clientContent` user turn. Lets a caller drive the session with TEXT instead of `realtimeInput`
     * audio -- the model runs the identical setup + tool loop either way, so this is the seam that makes
     * the whole conversation plane testable without the (untouchable) overlay audio machinery.
     * turnComplete=true tells the model the user is done so it generates a response.
     */
    fun buildClientContentText(text: String): String = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                    },
                )
            }
            put("turnComplete", true)
        }
    }.toString()

    /**
     * A `toolResponse` echoing the call `id` (Google routes the result by id) plus an optional
     * `scheduling` directive (NON_BLOCKING tools like drive_phone use SILENT for progress and
     * INTERRUPT for the final result).
     *
     * ## `scheduling` placement — fixed 2026-08-12
     *
     * It is a field of the **functionResponse** object, a sibling of `response` alongside `id`
     * and `name`. Google's docs: *"The scheduling parameter is placed directly in the
     * FunctionResponse object alongside the id, name, and response fields."*
     *
     * It was previously written INSIDE `response`, where the server never interpreted it — it
     * arrived as an ordinary key in the function's return payload. Every SILENT and INTERRUPT
     * was therefore dropped to the default, with three visible consequences in the device logs:
     *
     *  - progress lines meant to be absorbed quietly were **narrated aloud**, one per agent step;
     *  - the model answered from mid-task progress before the task had finished;
     *  - unable to tell a finished task from a running one, it re-dispatched status polls as NEW
     *    agent runs — one Amazon order query became five full runs and four "anything else?"s.
     *
     * The old unit test asserted the broken placement, which is why this survived: it was
     * written from the implementation rather than from the spec.
     */
    fun buildToolResponse(id: String, name: String, result: String, scheduling: String? = null): String =
        buildJsonObject {
            putJsonObject("toolResponse") {
                putJsonArray("functionResponses") {
                    add(
                        buildJsonObject {
                            if (id.isNotEmpty()) put("id", id)
                            put("name", name)
                            if (scheduling != null) put("scheduling", scheduling)
                            putJsonObject("response") {
                                put("result", result)
                            }
                        },
                    )
                }
            }
        }.toString()

    fun parseServerMessage(text: String): LiveServerMessage = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        when {
            root.containsKey("setupComplete") -> LiveServerMessage.SetupComplete
            root.containsKey("goAway") -> LiveServerMessage.GoAway(null)
            root.containsKey("toolCallCancellation") -> LiveServerMessage.ToolCallCancellation(
                (root["toolCallCancellation"]?.jsonObject?.get("ids")?.jsonArray ?: emptyList())
                    .mapNotNull { it.jsonPrimitive.contentOrNull },
            )
            root.containsKey("sessionResumptionUpdate") -> root["sessionResumptionUpdate"]!!.jsonObject.let {
                LiveServerMessage.SessionResumptionUpdate(
                    handle = it["newHandle"]?.jsonPrimitive?.contentOrNull,
                    resumable = it["resumable"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
            root.containsKey("usageMetadata") -> root["usageMetadata"]!!.jsonObject.let {
                fun n(k: String) = it[k]?.jsonPrimitive?.intOrNull ?: 0
                LiveServerMessage.Usage(n("totalTokenCount"), n("promptTokenCount"), n("responseTokenCount"))
            }
            root.containsKey("toolCall") -> LiveServerMessage.ToolCall(
                (root["toolCall"]?.jsonObject?.get("functionCalls")?.jsonArray ?: emptyList()).map { fc ->
                    val o = fc.jsonObject
                    CompanionToolCall(
                        name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        args = o["args"]?.jsonObject ?: JsonObject(emptyMap()),
                        id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                },
            )
            root.containsKey("serverContent") -> {
                val sc = root["serverContent"]!!.jsonObject
                LiveServerMessage.ServerContent(
                    audio = extractAudio(sc),
                    text = extractText(sc),
                    userTranscript = sc["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull,
                    modelTranscript = sc["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull,
                    turnComplete = sc["turnComplete"]?.jsonPrimitive?.booleanOrNull ?: false,
                    generationComplete = sc["generationComplete"]?.jsonPrimitive?.booleanOrNull ?: false,
                    interrupted = sc["interrupted"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
            else -> LiveServerMessage.Unparsed(text)
        }
    }.getOrElse { LiveServerMessage.Unparsed(text) }

    private fun extractText(serverContent: JsonObject): String? =
        serverContent["modelTurn"]?.jsonObject?.get("parts")?.jsonArray
            ?.firstNotNullOfOrNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }

    private fun extractAudio(serverContent: JsonObject): ByteArray? {
        val b64 = serverContent["modelTurn"]?.jsonObject?.get("parts")?.jsonArray
            ?.firstNotNullOfOrNull { it.jsonObject["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull }
            ?: return null
        return runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
    }
}
