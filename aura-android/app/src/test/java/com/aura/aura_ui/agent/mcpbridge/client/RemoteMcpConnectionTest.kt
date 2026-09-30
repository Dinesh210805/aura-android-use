package com.aura.aura_ui.agent.mcpbridge.client

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpConnectionTest {
    private val cfg = McpServerConfig(id = "github", displayName = "GitHub", url = "https://mcp.github.com")

    private class FakeConnector(var outcome: ConnectOutcome) : McpConnector {
        var calls = 0
        override suspend fun connect(config: McpServerConfig): ConnectOutcome { calls++; return outcome }
    }

    @Test fun `enable connects to Connected`() = runTest {
        val conn = RemoteMcpConnection(cfg, FakeConnector(ConnectOutcome.Ok(7, "use me well")))
        conn.enable()
        val s = conn.state.value
        assertTrue(s is McpConnectionState.Connected)
        assertEquals(7, (s as McpConnectionState.Connected).toolCount)
        assertEquals("use me well", s.serverInstructions)
    }

    @Test fun `unauthorized goes to NeedsAuth`() = runTest {
        val conn = RemoteMcpConnection(cfg, FakeConnector(ConnectOutcome.Unauthorized))
        conn.enable()
        assertTrue(conn.state.value is McpConnectionState.NeedsAuth)
    }

    @Test fun `error goes to Failed with attempt count`() = runTest {
        val conn = RemoteMcpConnection(cfg, FakeConnector(ConnectOutcome.Error("dns")))
        conn.enable()
        val s = conn.state.value
        assertTrue(s is McpConnectionState.Failed)
        assertEquals(1, (s as McpConnectionState.Failed).attempt)
    }

    @Test fun `disable always returns Disabled`() = runTest {
        val conn = RemoteMcpConnection(cfg, FakeConnector(ConnectOutcome.Ok(1, null)))
        conn.enable(); conn.disable()
        assertEquals(McpConnectionState.Disabled, conn.state.value)
    }

    @Test fun `onTokenSaved retries after NeedsAuth`() = runTest {
        val connector = FakeConnector(ConnectOutcome.Unauthorized)
        val conn = RemoteMcpConnection(cfg, connector)
        conn.enable()
        assertTrue(conn.state.value is McpConnectionState.NeedsAuth)
        connector.outcome = ConnectOutcome.Ok(3, null)
        conn.onTokenSaved()
        assertTrue(conn.state.value is McpConnectionState.Connected)
    }
}
