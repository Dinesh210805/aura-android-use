package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.agent.llm.AuthScheme
import com.aura.aura_ui.agent.llm.LlmEndpoint
import com.aura.aura_ui.agent.llm.ModelListStyle
import com.aura.aura_ui.agent.llm.ProviderProfile
import com.aura.aura_ui.agent.llm.VisionInference
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brain lane died on every Gemini user with `HTTP 400: "Missing or invalid Authorization
 * header."` — AURA saying, out loud, that it could not reach its own brain.
 *
 * [LlmEndpoint.authScheme] documents itself as *"How ModelCatalog authenticates the /models
 * fetch. The chat leg is always Bearer"*, and Gemini is the one provider where the two differ:
 * its native `/v1beta/models` requires `x-goog-api-key`, while its OpenAI-compatibility
 * `/v1beta/openai/chat/completions` accepts only `Authorization: Bearer`. [BrainChat] reused
 * the models-fetch scheme for the chat leg, so the models list worked, the chat call did not,
 * and the failure was invisible on Bearer-only providers like Groq.
 */
class BrainChatTest {

    /** Captures the outgoing request and answers with a canned completion — no network. */
    private class Capture(private val status: Int = 200, private val body: String = OK_BODY) : Interceptor {
        var request: Request? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            request = chain.request()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status == 200) "OK" else "Bad Request")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun clientWith(capture: Capture) = OkHttpClient.Builder().addInterceptor(capture).build()

    private fun endpoint(auth: AuthScheme, requiresKey: Boolean = true) = LlmEndpoint(
        id = "gemini",
        displayName = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com",
        chatCompletionsPath = "/v1beta/openai/chat/completions",
        modelsUrl = "https://generativelanguage.googleapis.com/v1beta/models",
        authScheme = auth,
        modelListStyle = ModelListStyle.GEMINI_MODELS,
        visionInference = VisionInference.GEMINI_GENERATION,
        profile = ProviderProfile.GEMINI,
        isBuiltIn = true,
        requiresKey = requiresKey,
    )

    @Test
    fun `the chat leg authenticates with Bearer even when the models fetch uses x-goog-api-key`() {
        val capture = Capture()
        val brain = Brain(endpoint(AuthScheme.X_GOOG_API_KEY), apiKey = "AIzaTESTKEY", modelId = "gemini-3.5-flash-lite")

        runBlocking { BrainChat.complete(brain, "hello", temperature = 0.3, httpClient = clientWith(capture)) }

        assertEquals("Bearer AIzaTESTKEY", capture.request?.header("Authorization"))
        assertNull(
            "Gemini's OpenAI-compat path rejects the header its own /models endpoint requires",
            capture.request?.header("x-goog-api-key"),
        )
    }

    @Test
    fun `a bearer endpoint is unchanged`() {
        val capture = Capture()
        val brain = Brain(endpoint(AuthScheme.BEARER), apiKey = "gsk_test", modelId = "llama")

        runBlocking { BrainChat.complete(brain, "hello", temperature = 0.3, httpClient = clientWith(capture)) }

        assertEquals("Bearer gsk_test", capture.request?.header("Authorization"))
    }

    @Test
    fun `a keyless local endpoint sends no authorization at all`() {
        // Ollama and friends: an empty Bearer would be a 401 invented by us.
        val capture = Capture()
        val brain = Brain(endpoint(AuthScheme.NONE, requiresKey = false), apiKey = "", modelId = "llama3")

        runBlocking { BrainChat.complete(brain, "hello", temperature = 0.3, httpClient = clientWith(capture)) }

        assertNull(capture.request?.header("Authorization"))
    }

    @Test
    fun `the completion text is returned and the model id is sent`() {
        val capture = Capture()
        val brain = Brain(endpoint(AuthScheme.X_GOOG_API_KEY), apiKey = "k", modelId = "gemini-3.5-flash-lite")

        val out = runBlocking {
            BrainChat.complete(brain, "hello", temperature = 0.3, httpClient = clientWith(capture))
        }

        assertEquals("Paris.", out)
        val sent = okio.Buffer().also { capture.request?.body?.writeTo(it) }.readUtf8()
        assertTrue(sent.contains("gemini-3.5-flash-lite"))
    }

    @Test
    fun `a rejected call returns null rather than throwing on a live voice turn`() {
        val capture = Capture(status = 400, body = """{"error":{"message":"Missing or invalid Authorization header."}}""")
        val brain = Brain(endpoint(AuthScheme.BEARER), apiKey = "k", modelId = "m")

        val out = runBlocking {
            BrainChat.complete(brain, "hello", temperature = 0.3, httpClient = clientWith(capture))
        }

        assertNull(out)
    }

    private companion object {
        const val OK_BODY = """{"choices":[{"message":{"role":"assistant","content":"Paris."}}]}"""
    }
}
