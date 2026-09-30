package com.aura.aura_ui.agent.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveProtocolTest {
    private val config = LiveSessionConfig(
        model = "models/gemini-x", voice = "Charon",
        systemInstruction = "${Persona.SENTINEL} hello",
        tools = LiveToolDeclarations.functionDeclarations(),
        triggerTokens = 25_000, slidingWindow = 8_000,
    )

    @Test fun `setup carries model, system instruction, tools and compression`() {
        val setup = Json.parseToJsonElement(LiveProtocol.buildSetup(config)).jsonObject["setup"]!!.jsonObject
        assertTrue(setup.containsKey("model"))
        assertTrue(setup.containsKey("systemInstruction"))
        assertTrue(setup.containsKey("tools"))
        assertTrue(setup.containsKey("contextWindowCompression"))
    }

    @Test fun `Google Search is off by default and rides alongside the function declarations when on`() {
        fun tools(cfg: LiveSessionConfig) =
            Json.parseToJsonElement(LiveProtocol.buildSetup(cfg)).jsonObject["setup"]!!.jsonObject["tools"].toString()
        assertFalse(tools(config).contains("googleSearch"))
        assertTrue(tools(config.copy(googleSearch = true)).contains("\"googleSearch\":{}"))
        assertTrue(tools(config.copy(googleSearch = true)).contains("functionDeclarations"))
    }

    @Test fun `setup carries VAD, thinking, transcription, language and resume handle`() {
        val cfg = config.copy(languageCode = "en-IN", silenceDurationMs = 700, resumeHandle = "h-9")
        val setup = Json.parseToJsonElement(LiveProtocol.buildSetup(cfg)).jsonObject["setup"]!!.jsonObject
        val gen = setup["generationConfig"]!!.jsonObject
        assertEquals("minimal", gen["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        assertEquals("en-IN", gen["speechConfig"]!!.jsonObject["languageCode"]!!.jsonPrimitive.content)
        val vad = setup["realtimeInputConfig"]!!.jsonObject["automaticActivityDetection"]!!.jsonObject
        assertEquals(700, vad["silenceDurationMs"]!!.jsonPrimitive.int)
        assertTrue(setup.containsKey("inputAudioTranscription"))
        assertTrue(setup.containsKey("outputAudioTranscription"))
        assertEquals("h-9", setup["sessionResumption"]!!.jsonObject["handle"]!!.jsonPrimitive.content)
    }

    @Test fun `parses a tool call frame into companion calls`() {
        val frame = """{"toolCall":{"functionCalls":[{"name":"drive_phone","args":{"task":"open spotify"}}]}}"""
        val msg = LiveProtocol.parseServerMessage(frame)
        assertTrue(msg is LiveServerMessage.ToolCall)
        assertEquals("drive_phone", (msg as LiveServerMessage.ToolCall).calls.single().name)
    }

    @Test fun `client content text frame carries the user text and completes the turn`() {
        val cc = Json.parseToJsonElement(LiveProtocol.buildClientContentText("open spotify"))
            .jsonObject["clientContent"]!!.jsonObject
        assertTrue("model must be told the turn is finished so it responds", cc["turnComplete"]!!.jsonPrimitive.boolean)
        val turn = cc["turns"]!!.jsonArray.single().jsonObject
        assertEquals("user", turn["role"]!!.jsonPrimitive.content)
        val text = turn["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("open spotify", text)
    }

    @Test fun `tool call frame carries the function call id`() {
        val frame = """{"toolCall":{"functionCalls":[{"id":"fc-1","name":"drive_phone","args":{"task":"x"}}]}}"""
        val msg = LiveProtocol.parseServerMessage(frame) as LiveServerMessage.ToolCall
        assertEquals("fc-1", msg.calls.single().id)
    }

    /**
     * `scheduling` is a field of the **functionResponse** object — a sibling of `response`, next
     * to `id` and `name`. Google: "The scheduling parameter is placed directly in the
     * FunctionResponse object alongside the id, name, and response fields."
     *
     * It used to be written INSIDE `response`, where the server never saw it: every SILENT and
     * INTERRUPT directive was silently downgraded to the default, so progress lines meant to be
     * absorbed quietly were narrated aloud instead, and the model could not tell a finished task
     * from a running one. The old test asserted the broken placement, which is why the bug
     * survived — it was written from the implementation instead of the spec.
     */
    @Test fun `scheduling is a sibling of response, not nested inside it`() {
        val r = Json.parseToJsonElement(LiveProtocol.buildToolResponse("fc-1", "drive_phone", "done", "WHEN_IDLE"))
            .jsonObject["toolResponse"]!!.jsonObject["functionResponses"]!!.jsonArray.single().jsonObject
        assertEquals("fc-1", r["id"]!!.jsonPrimitive.content)
        assertEquals("drive_phone", r["name"]!!.jsonPrimitive.content)
        assertEquals("WHEN_IDLE", r["scheduling"]!!.jsonPrimitive.content)

        val resp = r["response"]!!.jsonObject
        assertEquals("done", resp["result"]!!.jsonPrimitive.content)
        assertNull("scheduling must not leak into the response payload", resp["scheduling"])
    }

    @Test fun `tool response omits scheduling when none is given`() {
        val r = Json.parseToJsonElement(LiveProtocol.buildToolResponse("fc-2", "get_time", "12:00"))
            .jsonObject["toolResponse"]!!.jsonObject["functionResponses"]!!.jsonArray.single().jsonObject
        assertNull(r["scheduling"])
        assertEquals("12:00", r["response"]!!.jsonObject["result"]!!.jsonPrimitive.content)
    }

    @Test fun `parses model audio out, transcripts and turn signals`() {
        val pcm = android.util.Base64.encodeToString(byteArrayOf(1, 2, 3, 4), android.util.Base64.NO_WRAP)
        val frame = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$pcm"}}]},"outputTranscription":{"text":"hi"},"inputTranscription":{"text":"open spotify"},"turnComplete":true}}"""
        val sc = LiveProtocol.parseServerMessage(frame) as LiveServerMessage.ServerContent
        assertEquals(4, sc.audio!!.size)
        assertEquals("hi", sc.modelTranscript)
        assertEquals("open spotify", sc.userTranscript)
        assertTrue(sc.turnComplete)
    }

    @Test fun `parses interrupted barge-in signal`() {
        val sc = LiveProtocol.parseServerMessage("""{"serverContent":{"interrupted":true}}""") as LiveServerMessage.ServerContent
        assertTrue(sc.interrupted)
    }

    @Test fun `parses setupComplete`() {
        assertTrue(LiveProtocol.parseServerMessage("""{"setupComplete":{}}""") is LiveServerMessage.SetupComplete)
    }

    @Test fun `parses tool call cancellation`() {
        val m = LiveProtocol.parseServerMessage("""{"toolCallCancellation":{"ids":["fc-1","fc-2"]}}""")
        assertEquals(listOf("fc-1", "fc-2"), (m as LiveServerMessage.ToolCallCancellation).ids)
    }

    @Test fun `parses session resumption update`() {
        val m = LiveProtocol.parseServerMessage("""{"sessionResumptionUpdate":{"newHandle":"h-7","resumable":true}}""")
        m as LiveServerMessage.SessionResumptionUpdate
        assertEquals("h-7", m.handle); assertTrue(m.resumable)
    }

    @Test fun `parses usage metadata`() {
        val m = LiveProtocol.parseServerMessage("""{"usageMetadata":{"totalTokenCount":120,"promptTokenCount":80,"responseTokenCount":40}}""")
        assertEquals(120, (m as LiveServerMessage.Usage).totalTokens)
    }

    @Test fun `parses goAway`() {
        assertTrue(LiveProtocol.parseServerMessage("""{"goAway":{"timeLeft":"4.5s"}}""") is LiveServerMessage.GoAway)
    }

    @Test fun `malformed json becomes Unparsed, never throws`() {
        assertTrue(LiveProtocol.parseServerMessage("{not json") is LiveServerMessage.Unparsed)
    }

    @Test fun `realtime audio frame uses the current audio field with mime type and payload`() {
        val audio = Json.parseToJsonElement(LiveProtocol.buildRealtimeAudio("QUJD"))
            .jsonObject["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals("QUJD", audio["data"]!!.jsonPrimitive.content)
    }

    @Test fun `audio stream end frame flushes cached server audio on mic pause`() {
        val ri = Json.parseToJsonElement(LiveProtocol.buildAudioStreamEnd())
            .jsonObject["realtimeInput"]!!.jsonObject
        assertTrue(ri["audioStreamEnd"]!!.jsonPrimitive.boolean)
    }
}
