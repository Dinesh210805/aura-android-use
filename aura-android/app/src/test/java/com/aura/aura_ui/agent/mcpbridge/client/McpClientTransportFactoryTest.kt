package com.aura.aura_ui.agent.mcpbridge.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTransportFactoryTest {
    // The factory holds an HttpClient but authBuilder/transportTypeOf never make a request,
    // so the app's OkHttp engine is fine here (no live network, no MockEngine dependency).
    private val factory = McpClientTransportFactory(HttpClient(OkHttp))

    @Test fun `auth header builder injects bearer when token present`() {
        val rb = HttpRequestBuilder()
        factory.authBuilder("tok-abc").invoke(rb)
        assertEquals("Bearer tok-abc", rb.headers["Authorization"])
    }

    @Test fun `auth header builder is noop when token null`() {
        val rb = HttpRequestBuilder()
        factory.authBuilder(null).invoke(rb)
        assertTrue(rb.headers["Authorization"] == null)
    }

    @Test fun `streamable http config maps to streamable transport type`() {
        val cfg = McpServerConfig(id = "x", displayName = "X", url = "https://x.example.com")
        assertEquals(McpTransportType.STREAMABLE_HTTP, factory.transportTypeOf(cfg))
    }
}
