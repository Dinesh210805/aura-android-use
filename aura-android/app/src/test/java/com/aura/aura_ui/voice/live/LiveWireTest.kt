package com.aura.aura_ui.voice.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWireTest {

    private fun setupJson(s: LiveWire.Setup) = Json.parseToJsonElement(LiveWire.setup(s)).jsonObject["setup"]!!.jsonObject

    @Test
    fun `setup is locked to 3_8 Live, with no tools, thinking or language code`() {
        val setup = setupJson(LiveWire.Setup("Achird"))
        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        assertFalse(setup.containsKey("tools"))
        val gen = setup["generationConfig"]!!.jsonObject
        assertFalse(gen.containsKey("thinkingConfig"))
        assertFalse(gen["speechConfig"]!!.jsonObject.containsKey("languageCode"))
    }

    @Test
    fun `session resumption is asked for on the first connect`() {
        val setup = setupJson(LiveWire.Setup("Achird"))
        assertTrue(setup.containsKey("sessionResumption"))
        val resumed = setupJson(LiveWire.Setup("Achird", resumeHandle = "h1"))
        assertEquals("h1", resumed["sessionResumption"]!!.jsonObject["handle"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vocabulary goes out only in the full setup`() {
        val full = setupJson(LiveWire.Setup("Achird", vocabulary = listOf("AURA", "Spotify")))
        assertEquals(2, full["inputAudioTranscription"]!!.jsonObject["customVocabulary"]!!.jsonArray.size)
        val minimal = setupJson(LiveWire.Setup("Achird", vocabulary = listOf("AURA"), full = false))
        assertFalse(minimal["inputAudioTranscription"]!!.jsonObject.containsKey("customVocabulary"))
    }

    @Test
    fun `every audio part of a multi-part turn is kept`() {
        val frame = """{"serverContent":{"modelTurn":{"parts":[
            {"inlineData":{"mimeType":"audio/pcm","data":"AAA="}},
            {"inlineData":{"mimeType":"audio/pcm","data":"AQE="}}]}}}"""
        assertEquals(2, LiveWire.parse(frame).count { it is LiveEvent.Audio })
    }

    @Test
    fun `usage beside content does not hide the content`() {
        val frame = """{"usageMetadata":{"totalTokenCount":12},
            "serverContent":{"inputTranscription":{"text":"open spotify"},"turnComplete":true}}"""
        val events = LiveWire.parse(frame)
        assertTrue(events.contains(LiveEvent.Heard("open spotify")))
        assertTrue(events.contains(LiveEvent.TurnComplete))
        assertTrue(events.any { it is LiveEvent.Usage })
    }

    @Test
    fun `interim transcripts, voice activity and goAway time are read`() {
        assertEquals(
            listOf(LiveEvent.HeardPartial("open sp")),
            LiveWire.parse("""{"serverContent":{"interimInputTranscription":{"text":"open sp"}}}"""),
        )
        assertEquals(
            listOf(LiveEvent.UserActivity(started = false)),
            LiveWire.parse("""{"voiceActivity":{"voiceActivityType":"ACTIVITY_END"}}"""),
        )
        assertEquals(listOf(LiveEvent.GoAway(9500)), LiveWire.parse("""{"goAway":{"timeLeft":"9.5s"}}"""))
    }

    @Test
    fun `garbage never throws`() {
        assertTrue(LiveWire.parse("not json").single() is LiveEvent.Unknown)
        assertTrue(LiveWire.parse("{}").single() is LiveEvent.Unknown)
    }

    @Test
    fun `say is a complete user turn prefixed for the relay`() {
        val turn = Json.parseToJsonElement(LiveWire.say("Sent.")).jsonObject["clientContent"]!!.jsonObject
        assertEquals("true", turn["turnComplete"]!!.jsonPrimitive.content)
        val text = turn["turns"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("SAY: Sent.", text)
    }
}
