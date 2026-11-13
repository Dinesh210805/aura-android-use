package com.aura.aura_ui.agentcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderOutageTest {

    @Test
    fun `an outage falls back, a rejected key never does`() {
        assertEquals("rate limited", ProviderOutage.reason(RuntimeException("HTTP 429 Too Many Requests")))
        assertEquals("having a server error", ProviderOutage.reason(RuntimeException("503 Service Unavailable")))
        assertEquals("unreachable", ProviderOutage.reason(RuntimeException("wrap", java.net.UnknownHostException("api.x"))))
        assertNull(ProviderOutage.reason(RuntimeException("HTTP 401 invalid api key")))
        assertNull(ProviderOutage.reason(RuntimeException("400 bad request: tools[3] invalid schema")))
    }
}
