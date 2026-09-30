package com.aura.aura_ui.agent.llm

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The agent must give up on a stalled LLM request.
 *
 * ### The failure this pins (device capture 2026-08-05 20:42)
 *
 * ```
 * 20:42:54.873  ▶ Running goal: open amazon.com in a web browser
 * 20:42:55.147  → POST .../chat/completions (body 63771 chars)
 *               (silence — minutes)
 * ```
 *
 * One request, no response, no error, no retry, forever. `buildHttpClient` installed
 * interceptors and no timeouts at all, so a half-open socket — the ordinary result of a
 * phone changing network mid-request — parked the whole run indefinitely and the UI sat
 * on "working on it".
 *
 * `RetryingLLMClient` could not save it either: it retries *errors* (429/5xx/overloaded),
 * and a hang never produces one. The safety net only catches failures that actually fail.
 *
 * So the requirement is not "be fast" — it is **fail loudly and in bounded time**, because
 * only a failure can reach the retry ladder or an honest message to the user.
 */
class AgentHttpTimeoutTest {

    /**
     * Well under [AgentHttpTimeouts.REQUEST_MS] so the test stays fast, but far longer than a
     * healthy localhost round trip, so passing cannot be an accident of speed.
     */
    private val testBudgetMs = 4_000L

    // The JUnit timeout is the regression guard: if the client's budgets are ever removed
    // again, this fails in 30 s instead of hanging the build the way it hung the phone.
    @Test(timeout = 30_000)
    fun `a request that never gets a response fails instead of hanging`() {
        val server = MockWebServer()
        // Accept the connection, then never answer — a half-open socket.
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        server.start()

        try {
            val client = OpenAiCompatProvider.buildHttpClient(
                profile = ProviderProfile(),
                generation = GenerationConfig(),
                timeouts = AgentHttpTimeouts(requestMs = testBudgetMs, connectMs = testBudgetMs, socketMs = testBudgetMs),
            )
            val startedAt = System.nanoTime()
            try {
                runBlocking {
                    client.post(server.url("/v1/chat/completions").toString()) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"model":"test","messages":[]}""")
                    }
                }
                fail("expected the stalled request to fail, but it returned a response")
            } catch (expected: Exception) {
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                // Must honour the INJECTED budget, not merely fail eventually. OkHttp's
                // own default read timeout (~10 s) already fails a stalled socket, so a
                // loose bound here passes with no configuration at all and proves nothing
                // — measured: 11.6 s unconfigured vs 5.0 s configured.
                assertTrue(
                    "must fail within the configured budget, not OkHttp's default; took ${elapsedMs}ms",
                    elapsedMs < testBudgetMs * 2,
                )
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `a healthy response is not disturbed by the timeouts`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"ok":true}""")
                .build(),
        )
        server.start()

        try {
            val client = OpenAiCompatProvider.buildHttpClient(
                profile = ProviderProfile(),
                generation = GenerationConfig(),
            )
            runBlocking {
                val resp = client.post(server.url("/v1/chat/completions").toString()) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"test","messages":[]}""")
                }
                assertTrue("healthy call must succeed", resp.status.value == 200)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `the shipped budget is generous enough for a real vision turn`() {
        // A vision turn with thinking legitimately runs tens of seconds; the timeout
        // exists to catch DEAD connections, not slow ones. Too tight and it would abort
        // work that was about to succeed — the opposite failure, and a worse one.
        assertTrue(
            "request budget must clear a slow-but-real turn",
            AgentHttpTimeouts().requestMs >= 90_000,
        )
        assertTrue(
            "connect budget must be short — a dead host should not cost a minute",
            AgentHttpTimeouts().connectMs <= 20_000,
        )
    }
}
